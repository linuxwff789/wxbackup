#include <archive.h>
#include <archive_entry.h>
#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <string>
#include <sys/stat.h>
#include <unistd.h>

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

bool is_safe_name(const std::string& name) {
    return !name.empty() && name.front() != '/' && name.find("../") == std::string::npos && name != "..";
}
} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativeArchive_writeTarZstd(
    JNIEnv* env, jobject, jstring output, jobjectArray pairs) {
    const std::string output_path = jstring_to_utf8(env, output);
    if (output_path.empty() || pairs == nullptr || env->GetArrayLength(pairs) == 0 || env->GetArrayLength(pairs) % 2 != 0) return -1;

    archive* writer = archive_write_new();
    archive_write_set_format_pax_restricted(writer);
    archive_write_add_filter_zstd(writer);
    archive_write_set_filter_option(writer, "zstd", "compression-level", "3");
    if (archive_write_open_filename(writer, output_path.c_str()) != ARCHIVE_OK) {
        log_error("open output", archive_error_string(writer));
        archive_write_free(writer);
        return -2;
    }

    bool ok = true;
    const jsize length = env->GetArrayLength(pairs);
    for (jsize index = 0; index < length && ok; index += 2) {
        auto source = static_cast<jstring>(env->GetObjectArrayElement(pairs, index));
        auto target = static_cast<jstring>(env->GetObjectArrayElement(pairs, index + 1));
        const std::string source_path = jstring_to_utf8(env, source);
        const std::string archive_path = jstring_to_utf8(env, target);
        env->DeleteLocalRef(source);
        env->DeleteLocalRef(target);
        struct stat st {};
        if (!is_safe_name(archive_path) || lstat(source_path.c_str(), &st) != 0) { ok = false; break; }
        ok = S_ISDIR(st.st_mode) ? write_tree(writer, source_path.c_str(), archive_path.c_str())
                                 : write_file(writer, source_path.c_str(), archive_path.c_str());
    }

    const int close_result = archive_write_close(writer);
    archive_write_free(writer);
    if (!ok || close_result != ARCHIVE_OK) {
        unlink(output_path.c_str());
        return -3;
    }
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_nous_wxhook_backup_NativeArchive_verifyTarZstd(JNIEnv* env, jobject, jstring input) {
    const std::string input_path = jstring_to_utf8(env, input);
    archive* reader = archive_read_new();
    archive_read_support_filter_zstd(reader);
    archive_read_support_format_tar(reader);
    if (archive_read_open_filename(reader, input_path.c_str(), BUFFER_SIZE) != ARCHIVE_OK) {
        archive_read_free(reader);
        return -1;
    }
    archive_entry* entry = nullptr;
    int entries = 0;
    while (archive_read_next_header(reader, &entry) == ARCHIVE_OK) {
        ++entries;
        if (archive_read_data_skip(reader) != ARCHIVE_OK) { archive_read_free(reader); return -2; }
    }
    const int result = archive_read_close(reader);
    archive_read_free(reader);
    return result == ARCHIVE_OK && entries > 0 ? entries : -3;
}
