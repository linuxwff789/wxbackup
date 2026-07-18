// test_streaming.cpp — Debug streaming tar.zst extraction
// Build: g++ -std=c++17 -o test_streaming test_streaming.cpp -lzstd
// Run: ./test_streaming /sdcard/.../archive.tar.zst "hash/file_manifest.json"

#include <cstdio>
#include <cstring>
#include <string>
#include <zstd.h>

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
    char magic[6];
    char version[2];
    char uname[32];
    char gname[32];
    char devmajor[8];
    char devminor[8];
    char prefix[155];
    char padding[12];
};

static int detect_compression(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "detect_compression: fopen failed\n"); return -1; }
    unsigned char magic[4] = {};
    size_t n = fread(magic, 1, 4, f);
    fclose(f);
    if (n < 2) return -1;
    if (magic[0] == 0x28 && magic[1] == 0xB5) return 1;
    if (magic[0] == 0x1F && magic[1] == 0x8B) return 2;
    return 0;
}

struct TarReader {
    std::string result;
    const char* target = nullptr;
    bool found = false;
    bool complete = false;
    off_t content_remaining = 0;
    size_t content_padding = 0;
    size_t entry_padding = 0;
    size_t maxSize = 0;
    int entries_scanned = 0;

    void feed(const char* data, size_t size) {
        size_t off = 0;
        while (off < size) {
            if (content_remaining > 0) {
                size_t take = (size_t)content_remaining < (size - off)
                    ? (size_t)content_remaining : (size - off);
                if (found) {
                    if (maxSize == 0) {
                        result.append(data + off, take);
                    } else {
                        result.append(data + off, take);
                        if (result.size() > maxSize)
                            result.erase(0, result.size() - maxSize);
                    }
                }
                off += take;
                content_remaining -= take;
                if (content_remaining == 0) {
                    content_padding = entry_padding;
                    if (content_padding <= size - off) {
                        off += content_padding;
                        content_padding = 0;
                        complete = found;
                        found = false;
                    }
                }
                continue;
            }
            if (content_padding > 0) {
                size_t take = content_padding < size - off ? content_padding : size - off;
                off += take;
                content_padding -= take;
                if (content_padding == 0) {
                    complete = found;
                    found = false;
                }
                continue;
            }
            // At header boundary
            if (off + 512 > size) {
                fprintf(stderr, "  feed: need more data (off=%zu, size=%zu, off+512=%zu)\n", off, size, off+512);
                break;
            }
            const ustar_header* h = reinterpret_cast<const ustar_header*>(data + off);
            if (h->name[0] == '\0') {
                fprintf(stderr, "  feed: EOT at off=%zu\n", off);
                return;
            }
            if (memcmp(h->magic, "ustar", 5) != 0) {
                fprintf(stderr, "  feed: bad magic at off=%zu, skipping 512\n", off);
                off += 512;
                continue;
            }

            uint64_t entry_size = 0;
            for (int i = 0; i < 12 && h->size[i] >= '0' && h->size[i] <= '7'; i++)
                entry_size = (entry_size << 3) | (h->size[i] - '0');

            // Build full path
            char path[512];
            size_t pl = 0;
            if (h->prefix[0]) {
                memcpy(path, h->prefix, 155);
                pl = 155;
                while (pl > 0 && (path[pl-1] == '\0' || path[pl-1] == ' ')) pl--;
                path[pl++] = '/';
            }
            memcpy(path + pl, h->name, 100);
            size_t nl = 100;
            while (nl > 0 && (path[pl+nl-1] == '\0' || path[pl+nl-1] == ' ')) nl--;
            pl += nl;
            path[pl] = '\0';

            entries_scanned++;
            bool is_match = (strcmp(path, target) == 0);
            fprintf(stderr, "  [%d] type=%c path=%s size=%lu match=%d\n",
                    entries_scanned, h->typeflag, path, (unsigned long)entry_size, is_match);

            off += 512;

            if (is_match) {
                found = true;
                fprintf(stderr, "  >> FOUND! entry_size=%lu\n", (unsigned long)entry_size);
            }
            content_remaining = (off_t)entry_size;
            entry_padding = (512 - (entry_size % 512)) % 512;
        }
    }
};

// Streaming read: same logic as wxarchive.cpp
static std::string read_file_from_tar_streaming(const char* path, const char* target) {
    FILE* f = fopen(path, "rb");
    if (!f) { fprintf(stderr, "fopen failed: %s\n", path); return ""; }

    const size_t BUF = 256 * 1024;
    char inbuf[BUF];
    char outbuf[2 * BUF];

    TarReader tr;
    tr.target = target;

    auto scan = [&](const char* data, size_t size) { tr.feed(data, size); };

    int comp = detect_compression(path);
    fprintf(stderr, "comp=%d\n", comp);

    if (comp == 1) {
        ZSTD_DCtx* dctx = ZSTD_createDCtx();
        if (!dctx) { fclose(f); return ""; }

        ZSTD_outBuffer ob = {outbuf, sizeof(outbuf), 0};
        int chunk = 0;
        while (true) {
            size_t nread = fread(inbuf, 1, sizeof(inbuf), f);
            if (nread == 0) break;
            if (ferror(f)) break;
            bool last = feof(f) != 0;
            chunk++;
            fprintf(stderr, "chunk[%d]: read=%zu last=%d\n", chunk, nread, last);

            ZSTD_inBuffer ib = {inbuf, nread, 0};
            int inner = 0;
            while (true) {
                ob.pos = 0;
                size_t err = ZSTD_decompressStream(dctx, &ob, &ib);
                if (ZSTD_isError(err)) {
                    fprintf(stderr, "  inner[%d]: ZSTD ERROR: %s\n", inner, ZSTD_getErrorName(err));
                    ZSTD_DCtx_reset(dctx, ZSTD_reset_session_and_parameters);
                    ib.pos = ib.size;
                    break;
                }
                if (ob.pos > 0) scan(outbuf, ob.pos);
                fprintf(stderr, "  inner[%d]: ob.pos=%zu ib.pos=%zu/%zu err=%zu complete=%d\n",
                        inner, ob.pos, ib.pos, ib.size, err, tr.complete);
                inner++;
                if (tr.complete) { fprintf(stderr, "  inner: complete break\n"); break; }
                if (last && err == 0) { fprintf(stderr, "  inner: last+err0 break\n"); break; }
                if (ob.pos == 0 && ib.pos >= ib.size) {
                    fprintf(stderr, "  inner: no-output+input-exhausted break\n");
                    break;
                }
            }
            if (tr.complete) break;
            if (last) {
                fprintf(stderr, "chunk[%d]: last chunk done, complete=%d result_len=%zu\n",
                        chunk, tr.complete, tr.result.size());
                break;
            }
        }
        ZSTD_freeDCtx(dctx);
    } else {
        fprintf(stderr, "unsupported compression: %d\n", comp);
    }

    fclose(f);
    fprintf(stderr, "final: entries_scanned=%d found=%d complete=%d result_len=%zu\n",
            tr.entries_scanned, tr.found, tr.complete, tr.result.size());
    return tr.result;
}

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <archive> <target>\n", argv[0]);
        return 1;
    }

    fprintf(stderr, "--- Testing streaming tar.zst extraction ---\n");
    fprintf(stderr, "archive: %s\n", argv[1]);
    fprintf(stderr, "target: %s\n", argv[2]);

    std::string result = read_file_from_tar_streaming(argv[1], argv[2]);

    fprintf(stderr, "--- Result: %zu bytes ---\n", result.size());
    if (!result.empty()) {
        fprintf(stderr, "First 200: %.200s\n", result.c_str());
        fprintf(stderr, "Last 200: ");
        if (result.size() > 200)
            fprintf(stderr, "%s\n", result.c_str() + result.size() - 200);
        else
            fprintf(stderr, "%s\n", result.c_str());
    }

    return result.empty() ? 1 : 0;
}
