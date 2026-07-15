#include <jni.h>
#include <zstd.h>
#include <zlib.h>
#include <android/log.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

// ── ustar header (512 bytes, POSIX.1-1988) ──
struct ustar_header {
    char name[100];
    char mode[8];
    char uid[8];
    char gid[8];
    char size[12];
    char mtime[12];
    char chksum[8];
    char typeflag;
    char linkname[100];
    char magic[6];      // "ustar\0"
    char version[2];    // "00"
    char uname[32];
    char gname[32];
    char devmajor[8];
    char devminor[8];
    char prefix[155];
    char padding[12];
};
static_assert(sizeof(ustar_header) == 512, "ustar header must be 512 bytes");

// ── Block buffer + streaming compressor (zstd or gzip) ──
class TarWriter {
    FILE* out_ = nullptr;
    int mode_; // 0=none (raw tar), 1=zstd, 2=gzip
    union {
        ZSTD_CCtx* zstd_;
        z_stream gz_;
    };
    bool gz_init_ = false;
    std::vector<char> blocks_;
    static constexpr size_t RECORD_BYTES = 20 * 512; // 10KB flush

    bool compress_and_write(const char* data, size_t size, int flush_mode) {
        if (mode_ == 1) {
            ZSTD_inBuffer ib = {data, size, 0};
            bool first = true;
            while (ib.pos < ib.size || (flush_mode == 2 && first)) {
                first = false;
                char obuf[ZSTD_CStreamOutSize()];
                ZSTD_outBuffer ob = {obuf, sizeof(obuf), 0};
                ZSTD_EndDirective ed = (flush_mode == 2) ? ZSTD_e_end : ZSTD_e_continue;
                size_t err = ZSTD_compressStream2(zstd_, &ob, &ib, ed);
                if (ZSTD_isError(err)) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "zstd: %s", ZSTD_getErrorName(err)); return false; }
                if (ob.pos > 0 && fwrite(obuf, 1, ob.pos, out_) != ob.pos) return false;
                if (flush_mode == 2 && err == 0) break;
                if (flush_mode != 2 && ib.pos >= ib.size) break;
            }
        } else if (mode_ == 2) {
            gz_.next_in = (Bytef*)data;
            gz_.avail_in = size;
            bool first = true;
            while (gz_.avail_in > 0 || (flush_mode == 2 && first)) {
                first = false;
                char obuf[64 * 1024];
                gz_.next_out = (Bytef*)obuf;
                gz_.avail_out = sizeof(obuf);
                int ret = deflate(&gz_, flush_mode == 2 ? Z_FINISH : Z_NO_FLUSH);
                if (ret == Z_STREAM_ERROR) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "gzip deflate error"); return false; }
                size_t produced = sizeof(obuf) - gz_.avail_out;
                if (produced > 0 && fwrite(obuf, 1, produced, out_) != produced) return false;
                if (flush_mode == 2 && ret == Z_STREAM_END) break;
                if (flush_mode != 2 && gz_.avail_in == 0) break;
            }
        }
        return true;
    }

public:
    TarWriter() { memset(&gz_, 0, sizeof(gz_)); }
    ~TarWriter() { close(); }

    bool open(const char* path, int mode) {
        out_ = fopen(path, "wb");
        if (!out_) return false;
        mode_ = mode;
        blocks_.reserve(RECORD_BYTES * 2);
        if (mode_ == 1) {
            zstd_ = ZSTD_createCCtx();
            if (!zstd_) { fclose(out_); out_ = nullptr; return false; }
            ZSTD_CCtx_setParameter(zstd_, ZSTD_c_compressionLevel, 3);
            ZSTD_CCtx_setParameter(zstd_, ZSTD_c_checksumFlag, 1);
        } else if (mode_ == 2) {
            gz_.zalloc = Z_NULL; gz_.zfree = Z_NULL; gz_.opaque = Z_NULL;
            if (deflateInit2(&gz_, 6, Z_DEFLATED, 15 | 16, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
                __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "gzip deflateInit2 failed");
                fclose(out_); out_ = nullptr; return false;
            }
            gz_init_ = true;
        }
        return true;
    }

    void write_block(const char* data) {
        blocks_.insert(blocks_.end(), data, data + 512);
        if (blocks_.size() >= RECORD_BYTES) flush_blocks();
    }

    void write_zero_blocks(int count) {
        static const char zero[512] = {};
        for (int i = 0; i < count; i++) write_block(zero);
    }

    bool write_file_data(int fd, off_t size) {
        char buf[128 * 1024];
        off_t remaining = size;
        while (remaining > 0) {
            ssize_t n = read(fd, buf, sizeof(buf) < (size_t)remaining ? sizeof(buf) : (size_t)remaining);
            if (n <= 0) return false;
            remaining -= n;
            const char* p = buf;
            ssize_t written = n;
            while (written >= 512) { write_block(p); p += 512; written -= 512; }
            if (written > 0) { char pad[512] = {}; memcpy(pad, p, written); write_block(pad); }
        }
        return true;
    }

    bool flush_blocks() {
        if (blocks_.empty()) return true;
        bool ok = compress_and_write(blocks_.data(), blocks_.size(), 0);
        blocks_.clear();
        return ok;
    }

    bool close() {
        if (!out_) return true;
        bool ok = flush_blocks();
        if (ok && mode_ == 1) {
            ok = compress_and_write(nullptr, 0, 2);
            ZSTD_freeCCtx(zstd_);
        } else if (ok && mode_ == 2) {
            ok = compress_and_write(nullptr, 0, 2);
            if (gz_init_) deflateEnd(&gz_);
        } else if (gz_init_) {
            deflateEnd(&gz_);
        }
        fclose(out_); out_ = nullptr;
        return ok;
    }
};

// ── ustar helpers ──
static void oct(uint64_t v, char* p, size_t n) {
    p[--n] = '\0';
    while (n--) { p[n] = '0' + (v & 7); v >>= 3; }
}

static void fill_header(ustar_header* h, const char* name, uint64_t size, mode_t mode, char type) {
    memset(h, 0, sizeof(*h));
    size_t nl = strlen(name);
    if (nl <= 100) {
        memcpy(h->name, name, nl);
    } else {
        // POSIX ustar: split at '/' so prefix + "/" + name fits (155 + 1 + 100 = 256)
        // Find the last '/' where split <= 155 and nl - split - 1 <= 100
        size_t split = 0;
        for (size_t i = 0; i < nl; i++) {
            if (name[i] == '/' && i <= 155 && nl - i - 1 <= 100) split = i;
        }
        if (split == 0) {
            // No valid split found; use first 100 chars for name
            memcpy(h->name, name, 100);
            return;
        }
        memcpy(h->prefix, name, split);
        memcpy(h->name, name + split + 1, nl - split - 1);
    }
    oct(mode & 07777, h->mode, 8);
    oct(0, h->uid, 8); oct(0, h->gid, 8);
    oct(size, h->size, 12);
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    oct((uint64_t)ts.tv_sec, h->mtime, 12);
    h->typeflag = type;
    memcpy(h->magic, "ustar", 5); h->magic[5] = '\0';
    h->version[0] = '0'; h->version[1] = '0';
    unsigned ck = 0;
    for (size_t i = 0; i < 512; i++) ck += (i >= 148 && i < 156) ? ' ' : ((const unsigned char*)h)[i];
    oct(ck, h->chksum, 7); h->chksum[7] = ' ';
}

static bool write_entry(TarWriter* tw, const char* src, const char* arc) {
    struct stat st;
    if (lstat(src, &st) != 0) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "lstat %s: %s", src, strerror(errno)); return false; }
    ustar_header hdr;
    char type = S_ISDIR(st.st_mode) ? '5' : '0';
    fill_header(&hdr, arc, type == '5' ? 0 : (uint64_t)st.st_size, st.st_mode, type);
    tw->write_block((const char*)&hdr);
    if (type == '5') return true;
    int fd = open(src, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "open %s: %s", src, strerror(errno)); return false; }
    bool ok = tw->write_file_data(fd, st.st_size);
    close(fd);
    return ok;
}

static bool write_tree(TarWriter* tw, const char* dir_src, const char* dir_arc) {
    ustar_header hdr;
    fill_header(&hdr, dir_arc, 0, S_IFDIR | 0755, '5');
    tw->write_block((const char*)&hdr);
    DIR* d = opendir(dir_src);
    if (!d) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "opendir %s: %s", dir_src, strerror(errno)); return false; }
    std::vector<std::string> entries;
    struct dirent* de;
    while ((de = readdir(d))) { if (de->d_name[0] == '.' && (de->d_name[1] == '\0' || (de->d_name[1] == '.' && de->d_name[2] == '\0'))) continue; entries.push_back(de->d_name); }
    closedir(d);
    std::sort(entries.begin(), entries.end());
    bool ok = true;
    for (const auto& name : entries) {
        std::string full_src = std::string(dir_src) + "/" + name;
        std::string full_arc = std::string(dir_arc) + "/" + name;
        struct stat st;
        if (lstat(full_src.c_str(), &st) != 0) continue;
        ok = S_ISDIR(st.st_mode) ? write_tree(tw, full_src.c_str(), full_arc.c_str()) : write_entry(tw, full_src.c_str(), full_arc.c_str());
        if (!ok) break;
    }
    return ok;
}

// ── shared write logic ──
static int do_write_tar(const char* output, const char* pairs_path, int mode) {
    FILE* pf = fopen(pairs_path, "re");
    if (!pf) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "open pairs: %s", strerror(errno)); return -1; }
    std::vector<std::pair<std::string, std::string>> pairs;
    char line[4096];
    while (fgets(line, sizeof(line), pf)) {
        char* tab = strchr(line, '\t');
        if (!tab) continue;
        *tab = '\0';
        std::string src(line);
        std::string arc(tab + 1);
        if (!arc.empty() && arc.back() == '\n') arc.pop_back();
        if (src.empty() || arc.empty() || arc.front() == '/') continue;
        pairs.emplace_back(std::move(src), std::move(arc));
    }
    fclose(pf);
    if (pairs.empty()) return -1;

    TarWriter tw;
    if (!tw.open(output, mode)) { return -2; }
    int skipped = 0;
    for (const auto& p : pairs) {
        struct stat st;
        if (lstat(p.first.c_str(), &st) != 0) {
            __android_log_print(ANDROID_LOG_WARN, "wxhook:archive", "skip missing %s", p.first.c_str());
            skipped++;
            continue;
        }
        bool ok = S_ISDIR(st.st_mode) ? write_tree(&tw, p.first.c_str(), p.second.c_str()) : write_entry(&tw, p.first.c_str(), p.second.c_str());
        if (!ok) {
            __android_log_print(ANDROID_LOG_WARN, "wxhook:archive", "write failed %s", p.first.c_str());
            skipped++;
        }
    }
    tw.write_zero_blocks(2);
    bool closed = tw.close();
    if (!closed) return -4;
    __android_log_print(ANDROID_LOG_INFO, "wxhook:archive", "done: %zu entries, %d skipped", pairs.size(), skipped);
    return 0;
}

// ── JNI write ──
extern "C" JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativeArchive_writeTar(
    JNIEnv* env, jobject, jstring output_, jstring pairsPath_, jboolean useZstd_) {
    const char* output = env->GetStringUTFChars(output_, nullptr);
    const char* pairs_path = env->GetStringUTFChars(pairsPath_, nullptr);
    if (!output || !pairs_path) {
        if (output) env->ReleaseStringUTFChars(output_, output);
        return -1;
    }
    int mode = useZstd_ ? 1 : 2;
    int result = do_write_tar(output, pairs_path, mode);
    env->ReleaseStringUTFChars(output_, output);
    env->ReleaseStringUTFChars(pairsPath_, pairs_path);
    return result;
}

// ── auto-detect compression by file header ──
static int detect_compression(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return -1;
    unsigned char magic[4] = {};
    size_t n = fread(magic, 1, 4, f);
    fclose(f);
    if (n < 2) return -1;
    if (magic[0] == 0x28 && magic[1] == 0xB5) return 1; // zstd
    if (magic[0] == 0x1F && magic[1] == 0x8B) return 2; // gzip
    return 0; // raw
}

// ── verify: streaming decompress + count tar entries ──
extern "C" JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativeArchive_verifyTar(JNIEnv* env, jobject, jstring input_) {
    const char* input = env->GetStringUTFChars(input_, nullptr);
    if (!input) return -1;

    int comp = detect_compression(input);
    if (comp < 0) { env->ReleaseStringUTFChars(input_, input); return -1; }

    FILE* f = fopen(input, "rb");
    if (!f) { env->ReleaseStringUTFChars(input_, input); return -1; }

    // Streaming decompress: count entries by scanning decompressed 512-byte blocks
    const size_t BUF = 256 * 1024;
    char inbuf[BUF];
    char outbuf[2 * BUF];

    // We'll pass decompressed blocks through a 512-byte block scanner
    bool ok = true;
    int entries = 0;
    unsigned char partial[512] = {};
    size_t partial_len = 0;

    auto scan_tar_blocks = [&](const char* data, size_t size) {
        size_t off = 0;
        // If we have a partial block from before, complete it
        if (partial_len > 0) {
            size_t need = 512 - partial_len;
            if (size < need) {
                memcpy(partial + partial_len, data, size);
                partial_len += size;
                return;
            }
            memcpy(partial + partial_len, data, need);
            off = need;
            partial_len = 0;
            // Process the completed block
            const ustar_header* h = reinterpret_cast<const ustar_header*>(partial);
            if (h->name[0] != '\0' && memcmp(h->magic, "ustar", 5) == 0) entries++;
        }
        // Scan full 512-byte blocks
        while (off + 512 <= size) {
            const ustar_header* h = reinterpret_cast<const ustar_header*>(data + off);
            if (h->name[0] == '\0') return; // EOT
            if (memcmp(h->magic, "ustar", 5) == 0) entries++;
            off += 512;
        }
        // Save remaining partial block
        if (off < size) {
            memcpy(partial, data + off, size - off);
            partial_len = size - off;
        }
    };

    if (comp == 1) { // zstd
        ZSTD_DCtx* dctx = ZSTD_createDCtx();
        if (!dctx) { fclose(f); env->ReleaseStringUTFChars(input_, input); return -1; }
        ZSTD_outBuffer ob = {outbuf, sizeof(outbuf), 0};
        ZSTD_inBuffer ib = {inbuf, 0, 0};
        while (true) {
            ib.size = fread(inbuf, 1, sizeof(inbuf), f);
            ib.pos = 0;
            if (ferror(f)) { ok = false; break; }
            bool last = feof(f) != 0;
            while (true) {
                ob.pos = 0;
                size_t err = ZSTD_decompressStream(dctx, &ob, &ib);
                if (ZSTD_isError(err)) { ok = false; break; }
                if (ob.pos > 0) scan_tar_blocks(outbuf, ob.pos);
                if (last && err == 0) break;
                if (ob.pos == 0 && ib.pos >= ib.size) break;
            }
            if (!ok) break;
            if (last) break;
        }
        ZSTD_freeDCtx(dctx);
    } else if (comp == 2) { // gzip
        z_stream strm;
        memset(&strm, 0, sizeof(strm));
        if (inflateInit2(&strm, 15 | 16) != Z_OK) { fclose(f); env->ReleaseStringUTFChars(input_, input); return -1; }
        while (true) {
            strm.avail_in = fread(inbuf, 1, sizeof(inbuf), f);
            strm.next_in = (Bytef*)inbuf;
            if (ferror(f)) { ok = false; break; }
            bool last = feof(f) != 0;
            do {
                strm.avail_out = sizeof(outbuf);
                strm.next_out = (Bytef*)outbuf;
                int ret = inflate(&strm, Z_NO_FLUSH);
                if (ret == Z_STREAM_ERROR || ret == Z_DATA_ERROR) { ok = false; break; }
                size_t produced = sizeof(outbuf) - strm.avail_out;
                if (produced > 0) scan_tar_blocks(outbuf, produced);
                if (ret == Z_STREAM_END) { last = true; break; }
            } while (strm.avail_out == 0);
            if (!ok) break;
            if (last) break;
        }
        inflateEnd(&strm);
    } else { // raw tar
        while (true) {
            size_t n = fread(inbuf, 1, sizeof(inbuf), f);
            if (n == 0) break;
            scan_tar_blocks(inbuf, n);
        }
    }
    fclose(f);
    env->ReleaseStringUTFChars(input_, input);
    if (!ok) return -4;
    return entries > 0 ? entries : -5;
}
