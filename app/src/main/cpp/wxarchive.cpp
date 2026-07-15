#include <jni.h>
#include <zstd.h>
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

// POSIX ustar header (512 bytes)
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
    char padding[12];   // pad to 512
};

static_assert(sizeof(ustar_header) == 512, "ustar header must be 512 bytes");

// Write-optimized tar + zstd streaming
class TarZstdWriter {
    FILE* out_ = nullptr;
    ZSTD_CCtx* cctx_ = nullptr;
    std::vector<char> blocks_;  // 512-byte block buffer
    static constexpr size_t RECORD_BYTES = 20 * 512; // 10KB per flush (GNU tar default)

public:
    ~TarZstdWriter() { close(); }

    bool open(const char* path) {
        out_ = fopen(path, "wb");
        if (!out_) return false;
        cctx_ = ZSTD_createCCtx();
        if (!cctx_) { fclose(out_); out_ = nullptr; return false; }
        ZSTD_CCtx_setParameter(cctx_, ZSTD_c_compressionLevel, 3);
        ZSTD_CCtx_setParameter(cctx_, ZSTD_c_checksumFlag, 1);
        blocks_.reserve(RECORD_BYTES * 2);
        return true;
    }

    // Emit one 512-byte block (buffered, flushes when record is full)
    void write_block(const char* data) {
        blocks_.insert(blocks_.end(), data, data + 512);
        if (blocks_.size() >= RECORD_BYTES) flush_blocks();
    }

    // Write zero blocks (for EOT: two zero blocks)
    void write_zero_blocks(int count) {
        static const char zero[512] = {};
        for (int i = 0; i < count; i++) write_block(zero);
    }

    // Write file data, padded to 512
    bool write_file_data(int fd, off_t size) {
        char buf[128 * 1024];
        off_t remaining = size;
        while (remaining > 0) {
            ssize_t n = read(fd, buf, sizeof(buf) < (size_t)remaining ? sizeof(buf) : (size_t)remaining);
            if (n <= 0) return false;
            remaining -= n;
            // Write full 512-byte chunks
            const char* p = buf;
            ssize_t written = n;
            while (written >= 512) {
                write_block(p);
                p += 512;
                written -= 512;
            }
            // Write partial last block, padded with zeros
            if (written > 0) {
                char pad[512] = {};
                memcpy(pad, p, written);
                write_block(pad);
            }
        }
        return true;
    }

    // Flush buffered blocks through zstd
    bool flush_blocks() {
        if (blocks_.empty()) return true;
        ZSTD_inBuffer ibuf = {blocks_.data(), blocks_.size(), 0};
        while (ibuf.pos < ibuf.size) {
            char obuf[ZSTD_CStreamOutSize()];
            ZSTD_outBuffer ob = {obuf, sizeof(obuf), 0};
            size_t err = ZSTD_compressStream2(cctx_, &ob, &ibuf, ZSTD_e_continue);
            if (ZSTD_isError(err)) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "zstd flush: %s", ZSTD_getErrorName(err)); return false; }
            if (ob.pos > 0 && fwrite(obuf, 1, ob.pos, out_) != ob.pos) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "write: %s", strerror(errno)); return false; }
        }
        blocks_.clear();
        return true;
    }

    // Flush all + end zstd frame
    bool close() {
        if (!out_) return true;
        bool ok = true;
        if (cctx_) {
            ok = flush_blocks();
            if (ok) {
                ZSTD_inBuffer ibuf = {nullptr, 0, 0};
                while (true) {
                    char obuf[ZSTD_CStreamOutSize()];
                    ZSTD_outBuffer ob = {obuf, sizeof(obuf), 0};
                    size_t err = ZSTD_compressStream2(cctx_, &ob, &ibuf, ZSTD_e_end);
                    if (ZSTD_isError(err)) { ok = false; break; }
                    if (ob.pos > 0 && fwrite(obuf, 1, ob.pos, out_) != ob.pos) { ok = false; break; }
                    if (err == 0) break;
                }
            }
            ZSTD_freeCCtx(cctx_); cctx_ = nullptr;
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
        // POSIX ustar: prefix(155) + "/" + name(100), split at a '/' boundary
        // Find the last '/' within the first 100 chars for the name part
        size_t split = nl > 255 ? 255 : nl - 100;
        // Back up to a '/' boundary
        while (split > 0 && name[split - 1] != '/') split--;
        if (split == 0) { split = nl > 255 ? 155 : nl - 100; } // fallback
        size_t prefix_len = split;
        if (prefix_len > 155) prefix_len = 155;
        // If no good split, truncate the name part
        size_t name_len = nl - prefix_len;
        if (name[prefix_len] == '/') { prefix_len++; name_len--; }
        if (name_len > 100) name_len = 100;
        if (prefix_len > 0) memcpy(h->prefix, name, prefix_len);
        if (name_len > 0) memcpy(h->name, name + prefix_len, name_len);
    }
    oct(mode & 07777, h->mode, 8);
    oct(0, h->uid, 8);
    oct(0, h->gid, 8);
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

// ── file entry ──

static bool write_entry(TarZstdWriter* tw, const char* src, const char* arc) {
    struct stat st;
    if (lstat(src, &st) != 0) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "lstat %s: %s", src, strerror(errno)); return false; }
    ustar_header hdr;
    char type = S_ISDIR(st.st_mode) ? '5' : '0';
    fill_header(&hdr, arc, type == '5' ? 0 : (uint64_t)st.st_size, st.st_mode, type);
    tw->write_block((const char*)&hdr);

    if (type == '5') return true; // dirs have no data
    int fd = open(src, O_RDONLY | O_CLOEXEC);
    if (fd < 0) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "open %s: %s", src, strerror(errno)); return false; }
    bool ok = tw->write_file_data(fd, st.st_size);
    close(fd);
    return ok;
}

// ── directory tree (GNU tar style: opendir/readdir) ──

static bool write_tree(TarZstdWriter* tw, const char* dir_src, const char* dir_arc) {
    // Write the directory entry itself first
    ustar_header hdr;
    fill_header(&hdr, dir_arc, 0, S_IFDIR | 0755, '5');
    tw->write_block((const char*)&hdr);

    DIR* d = opendir(dir_src);
    if (!d) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "opendir %s: %s", dir_src, strerror(errno)); return false; }

    // Collect entries (skip . and ..)
    std::vector<std::string> entries;
    struct dirent* de;
    while ((de = readdir(d))) {
        if (strcmp(de->d_name, ".") == 0 || strcmp(de->d_name, "..") == 0) continue;
        entries.push_back(de->d_name);
    }
    closedir(d);

    // Sort for deterministic order
    std::sort(entries.begin(), entries.end());

    bool ok = true;
    for (const auto& name : entries) {
        std::string full_src = std::string(dir_src) + "/" + name;
        std::string full_arc = std::string(dir_arc) + "/" + name;
        struct stat st;
        if (lstat(full_src.c_str(), &st) != 0) continue; // skip broken
        if (S_ISDIR(st.st_mode)) {
            ok = write_tree(tw, full_src.c_str(), full_arc.c_str());
        } else if (S_ISREG(st.st_mode)) {
            ok = write_entry(tw, full_src.c_str(), full_arc.c_str());
        } // skip non-regular, non-dir
        if (!ok) break;
    }
    return ok;
}

// ── JNI ──

extern "C" JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativeArchive_writeTarZstd(
    JNIEnv* env, jobject, jstring output_, jstring pairsPath_) {

    const char* output = env->GetStringUTFChars(output_, nullptr);
    const char* pairs_path = env->GetStringUTFChars(pairsPath_, nullptr);
    if (!output || !pairs_path) { if (output) env->ReleaseStringUTFChars(output_, output); return -1; }

    FILE* pf = fopen(pairs_path, "re");
    if (!pf) { __android_log_print(ANDROID_LOG_ERROR, "wxhook:archive", "open pairs: %s", strerror(errno)); env->ReleaseStringUTFChars(output_, output); env->ReleaseStringUTFChars(pairsPath_, pairs_path); return -1; }

    // Read all pairs into memory
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
    env->ReleaseStringUTFChars(pairsPath_, pairs_path);

    if (pairs.empty()) { env->ReleaseStringUTFChars(output_, output); return -1; }

    TarZstdWriter tw;
    if (!tw.open(output)) { env->ReleaseStringUTFChars(output_, output); return -2; }

    bool ok = true;
    for (const auto& p : pairs) {
        struct stat st;
        if (lstat(p.first.c_str(), &st) != 0) {
            __android_log_print(ANDROID_LOG_WARN, "wxhook:archive", "skip %s: %s", p.first.c_str(), strerror(errno));
            continue;
        }
        if (S_ISDIR(st.st_mode)) {
            ok = write_tree(&tw, p.first.c_str(), p.second.c_str());
        } else {
            ok = write_entry(&tw, p.first.c_str(), p.second.c_str());
        }
        if (!ok) break;
    }

    // End-of-archive: two zero blocks
    if (ok) {
        tw.write_zero_blocks(2);
    }

    bool closed = tw.close();
    env->ReleaseStringUTFChars(output_, output);

    if (!ok) return -3;
    if (!closed) return -4;
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativeArchive_verifyTarZstd(JNIEnv* env, jobject, jstring input_) {
    const char* input = env->GetStringUTFChars(input_, nullptr);
    if (!input) return -1;

    FILE* f = fopen(input, "rb");
    if (!f) { env->ReleaseStringUTFChars(input_, input); return -1; }

    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (!dctx) { fclose(f); env->ReleaseStringUTFChars(input_, input); return -1; }

    std::vector<char> inbuf(256 * 1024);
    std::vector<char> outbuf(ZSTD_DStreamOutSize());

    // Decompress to memory
    std::vector<char> decompressed;
    ZSTD_outBuffer ob = {outbuf.data(), outbuf.size(), 0};
    ZSTD_inBuffer ib = {inbuf.data(), 0, 0};

    while (true) {
        ib.size = fread(inbuf.data(), 1, inbuf.size(), f);
        ib.pos = 0;
        if (ferror(f)) break;
        bool last = feof(f) != 0;
        while (true) {
            ob.pos = 0;
            size_t err = ZSTD_decompressStream(dctx, &ob, &ib);
            if (ZSTD_isError(err)) { ZSTD_freeDCtx(dctx); fclose(f); env->ReleaseStringUTFChars(input_, input); return -4; }
            if (ob.pos > 0) decompressed.insert(decompressed.end(), outbuf.data(), outbuf.data() + ob.pos);
            if (last && err == 0) break;
            if (!last && ib.pos >= ib.size) break;
        }
        if (last) break;
    }
    ZSTD_freeDCtx(dctx);
    fclose(f);

    // Count tar entries from decompressed data
    int entries = 0;
    size_t pos = 0;
    while (pos + 512 <= decompressed.size()) {
        const ustar_header* h = reinterpret_cast<const ustar_header*>(decompressed.data() + pos);
        // Check if this is a valid-looking header (magic or zero block)
        if (h->name[0] == '\0') {
            // Count consecutive zero blocks for EOT detection
            int zeros = 1;
            while (pos + (zeros + 1) * 512 <= decompressed.size()) {
                const char* next = decompressed.data() + pos + zeros * 512;
                if (next[0] != '\0') break;
                zeros++;
            }
            // Two zero blocks = EOT
            break;
        }
        if (memcmp(h->magic, "ustar", 5) != 0 && h->typeflag != '0' && h->typeflag != '5') {
            // Corrupt or non-standard header
            break;
        }
        entries++;

        // Parse size
        uint64_t size = 0;
        for (int i = 0; i < 12 && h->size[i]; i++) size = (size << 3) | (h->size[i] - '0');
        // Move past header + data (padded to 512)
        pos += 512;
        if (size > 0) {
            size_t data_blocks = (size + 511) / 512;
            pos += data_blocks * 512;
        }
    }

    env->ReleaseStringUTFChars(input_, input);
    return entries > 0 ? entries : -5;
}
