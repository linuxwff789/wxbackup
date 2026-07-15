#include <archive.h>
#include <archive_entry.h>
#include <jni.h>
#include <zstd.h>
#include <android/log.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

namespace {
constexpr const char* TAG = "wxhook:archive";
constexpr size_t BUFFER_SIZE = 128 * 1024;

void log_error(const char* stage, const char* detail) {
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: %s", stage, detail != nullptr ? detail : "unknown");
}

std::string jstring_to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ?: "");
    if (chars != nullptr) env->ReleaseStringUTFChars(value, chars);
    return result;
}

bool write_file(struct archive* writer, const char* source_path, const char* archive_path) {
    struct stat st {};
    if (lstat(source_path, &st) != 0) {
        log_error("lstat", strerror(errno));
        return false;
    }

    archive_entry* entry = archive_entry_new();
    archive_entry_set_pathname(entry, archive_path);
    archive_entry_copy_stat(entry, &st);
    archive_entry_set_uid(entry, 0);
    archive_entry_set_gid(entry, 0);

    if (archive_write_header(writer, entry) != ARCHIVE_OK) {
        log_error("write header", archive_error_string(writer));
        archive_entry_free(entry);
        return false;
    }

    if (S_ISREG(st.st_mode)) {
        int input = open(source_path, O_RDONLY | O_CLOEXEC);
        if (input < 0) {
            log_error("open", strerror(errno));
            archive_entry_free(entry);
            return false;
        }
        char buffer[BUFFER_SIZE];
        bool ok = true;
        ssize_t read_bytes;
        while ((read_bytes = read(input, buffer, sizeof(buffer))) > 0) {
            if (archive_write_data(writer, buffer, static_cast<size_t>(read_bytes)) < 0) {
                log_error("write data", archive_error_string(writer));
                ok = false;
                break;
            }
        }
        if (read_bytes < 0) {
            log_error("read", strerror(errno));
            ok = false;
        }
        close(input);
        archive_entry_free(entry);
        return ok;
    }

    archive_entry_free(entry);
    return true;
}

bool write_tree(struct archive* writer, const char* source_path, const char* archive_path) {
    archive* disk = archive_read_disk_new();
    archive_read_disk_set_standard_lookup(disk);
    if (archive_read_disk_open(disk, source_path) != ARCHIVE_OK) {
        log_error("open source", archive_error_string(disk));
        archive_read_free(disk);
        return false;
    }

    archive_entry* entry = nullptr;
    const std::string root(source_path);
    const std::string destination_root(archive_path);
    bool ok = true;
    while (archive_read_next_header2(disk, entry) == ARCHIVE_OK) {
        const char* full_path = archive_entry_sourcepath(entry);
        if (full_path == nullptr) {
            ok = false;
            break;
        }
        std::string relative(full_path);
        if (relative.rfind(root, 0) != 0) {
            ok = false;
            break;
        }
        relative.erase(0, root.size());
        if (!relative.empty() && relative.front() == '/') relative.erase(0, 1);
        const std::string archive_name = relative.empty() ? destination_root : destination_root + "/" + relative;
        archive_entry_set_pathname(entry, archive_name.c_str());
        archive_entry_set_uid(entry, 0);
        archive_entry_set_gid(entry, 0);
        if (archive_write_header(writer, entry) != ARCHIVE_OK) {
            log_error("write tree header", archive_error_string(writer));
            ok = false;
            break;
        }
        if (archive_entry_filetype(entry) == AE_IFDIR && archive_read_disk_descend(disk) != ARCHIVE_OK) {
            log_error("descend", archive_error_string(disk));
            ok = false;
            break;
        }
        if (archive_entry_size(entry) > 0) {
            char buffer[BUFFER_SIZE];
            ssize_t n;
            while ((n = archive_read_data(disk, buffer, sizeof(buffer))) > 0) {
                if (archive_write_data(writer, buffer, static_cast<size_t>(n)) < 0) { ok = false; break; }
            }
            if (!ok || n < 0) { ok = false; break; }
        }
    }
    archive_read_close(disk);
    archive_read_free(disk);
    return ok;
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativeArchive_writeTarZstd(
    JNIEnv* env, jobject, jstring output, jstring pairsPath) {
    const std::string output_path = jstring_to_utf8(env, output);
    const std::string pairs_file = jstring_to_utf8(env, pairsPath);
    if (output_path.empty() || pairs_file.empty()) return -1;

    // Read pairs file: one tab-separated pair per line (source_path\tarchive_path)
    FILE* pf = fopen(pairs_file.c_str(), "re");
    if (!pf) { log_error("open pairs file", strerror(errno)); return -1; }
    std::vector<std::pair<std::string, std::string>> pairs;
    char line[4096];
    while (fgets(line, sizeof(line), pf)) {
        char* tab = strchr(line, '\t');
        if (!tab) continue;
        *tab = '\0';
        std::string src(line);
        std::string arc(tab + 1);
        if (arc.back() == '\n') arc.pop_back();
        if (src.empty() || arc.empty() || arc.front() == '/') continue;
        pairs.emplace_back(std::move(src), std::move(arc));
    }
    fclose(pf);
    if (pairs.empty()) return -1;

    // Step 1: write raw tar (no compression)
    const std::string tmp_path = output_path + ".tmp.tar";
    archive* writer = archive_write_new();
    archive_write_set_format_pax_restricted(writer);
    archive_write_add_filter_none(writer);
    if (archive_write_open_filename(writer, tmp_path.c_str()) != ARCHIVE_OK) {
        log_error("open tmp tar", archive_error_string(writer));
        archive_write_free(writer);
        return -2;
    }

    bool ok = true;
    for (const auto& pair : pairs) {
        const std::string& source_path = pair.first;
        const std::string& archive_path = pair.second;
        struct stat st {};
        if (lstat(source_path.c_str(), &st) != 0) {
            log_error("lstat source", source_path.c_str());
            ok = false;
            break;
        }
        ok = S_ISDIR(st.st_mode) ? write_tree(writer, source_path.c_str(), archive_path.c_str())
                                 : write_file(writer, source_path.c_str(), archive_path.c_str());
    }

    int close_result = archive_write_close(writer);
    archive_write_free(writer);
    if (!ok || close_result != ARCHIVE_OK) {
        unlink(tmp_path.c_str());
        return -3;
    }

    // Step 2: compress tar with static libzstd (simplified streaming)
    FILE* in = fopen(tmp_path.c_str(), "rb");
    if (!in) { log_error("compress open input", strerror(errno)); unlink(tmp_path.c_str()); return -4; }

    ZSTD_CCtx* cctx = ZSTD_createCCtx();
    if (!cctx) { log_error("zstd create cctx", "OOM"); fclose(in); unlink(tmp_path.c_str()); return -5; }
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_compressionLevel, 3);
    ZSTD_CCtx_setParameter(cctx, ZSTD_c_checksumFlag, 1);

    FILE* out = fopen(output_path.c_str(), "wb");
    if (!out) { log_error("compress open output", strerror(errno)); ZSTD_freeCCtx(cctx); fclose(in); unlink(tmp_path.c_str()); return -6; }

    bool compress_ok = true;
    std::vector<char> inbuf(256 * 1024);
    std::vector<char> obuf(ZSTD_CStreamOutSize());
    ZSTD_inBuffer zibuf = {inbuf.data(), 0, 0};
    ZSTD_outBuffer zobuf = {obuf.data(), obuf.size(), 0};

    while (true) {
        zibuf.size = fread(inbuf.data(), 1, inbuf.size(), in);
        zibuf.pos = 0;
        if (ferror(in)) { log_error("compress read input", strerror(errno)); compress_ok = false; break; }
        bool last = feof(in) != 0;
        ZSTD_EndDirective mode = last ? ZSTD_e_end : ZSTD_e_continue;

        while (true) {
            zobuf.pos = 0;
            size_t remaining = ZSTD_compressStream2(cctx, &zobuf, &zibuf, mode);
            if (ZSTD_isError(remaining)) {
                log_error("zstd compress", ZSTD_getErrorName(remaining));
                compress_ok = false;
                break;
            }
            if (zobuf.pos > 0 && fwrite(obuf.data(), 1, zobuf.pos, out) != zobuf.pos) {
                log_error("compress write output", strerror(errno));
                compress_ok = false;
                break;
            }
            if (!last) break;
            if (remaining == 0) break;
        }
        if (!compress_ok) break;
        if (last) break;
    }

    int cerr = ferror(out);
    ZSTD_freeCCtx(cctx);
    fclose(out);
    fclose(in);
    unlink(tmp_path.c_str());

    if (!compress_ok || cerr) {
        log_error("compress failed", cerr ? "write error" : "compression error");
        unlink(output_path.c_str());
        return -7;
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativeArchive_verifyTarZstd(JNIEnv* env, jobject, jstring input) {
    const std::string input_path = jstring_to_utf8(env, input);

    // Decompress .zst into memory with libzstd
    FILE* f = fopen(input_path.c_str(), "rb");
    if (!f) return -1;

    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (!dctx) { fclose(f); return -1; }

    std::vector<char> inbuf(256 * 1024);
    std::vector<char> outbuf(ZSTD_DStreamOutSize());

    // Read the entire compressed file into a buffer
    std::vector<char> compressed;
    while (true) {
        size_t n = fread(inbuf.data(), 1, inbuf.size(), f);
        if (n == 0) break;
        compressed.insert(compressed.end(), inbuf.data(), inbuf.data() + n);
    }
    fclose(f);

    // Decompress into memory
    std::vector<char> decompressed;
    ZSTD_outBuffer zobuf = {outbuf.data(), outbuf.size(), 0};
    ZSTD_inBuffer zibuf = {compressed.data(), compressed.size(), 0};

    while (zibuf.pos < zibuf.size) {
        zobuf.pos = 0;
        size_t err = ZSTD_decompressStream(dctx, &zobuf, &zibuf);
        if (ZSTD_isError(err)) { ZSTD_freeDCtx(dctx); return -4; }
        if (zobuf.pos > 0)
            decompressed.insert(decompressed.end(), outbuf.data(), outbuf.data() + zobuf.pos);
        if (err == 0) break;
    }
    ZSTD_freeDCtx(dctx);

    // Read tar from memory
    archive* reader = archive_read_new();
    archive_read_support_format_tar(reader);
    archive_read_support_filter_none(reader);
    if (archive_read_open_memory(reader, decompressed.data(), decompressed.size()) != ARCHIVE_OK) {
        archive_read_free(reader);
        return -3;
    }
    archive_entry* entry = nullptr;
    int entries = 0;
    while (archive_read_next_header(reader, &entry) == ARCHIVE_OK) {
        ++entries;
        if (archive_read_data_skip(reader) != ARCHIVE_OK) { archive_read_free(reader); return -2; }
    }
    const int result = archive_read_close(reader);
    archive_read_free(reader);
    return result == ARCHIVE_OK && entries > 0 ? entries : -5;
}
