// test_chunked.cpp - Test zstd streaming with 256KB chunks
// Build: g++ -std=c++17 -o test_chunked test_chunked.cpp -lzstd
// Run: ./test_chunked /sdcard/.../archive.tar.zst

#include <cstdio>
#include <cstring>
#include <string>
#include <zstd.h>

struct ustar_header {
    char name[100]; char mode[8]; char uid[8]; char gid[8];
    char size[12]; char mtime[12]; char chksum[8]; char typeflag;
    char linkname[100]; char magic[6]; char version[2];
    char uname[32]; char gname[32]; char devmajor[8]; char devminor[8];
    char prefix[155]; char padding[12];
};

int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "Usage: %s <archive>\n", argv[0]); return 1; }
    
    FILE* f = fopen(argv[1], "rb");
    if (!f) { perror("fopen"); return 1; }
    
    const size_t BUF = 256 * 1024;
    char inbuf[BUF];
    char outbuf[2 * BUF];
    
    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (!dctx) { fclose(f); return 1; }
    
    int total_entries = 0;
    int chunks = 0;
    int errors = 0;
    
    while (true) {
        size_t nread = fread(inbuf, 1, sizeof(inbuf), f);
        if (nread == 0) break;
        if (ferror(f)) break;
        chunks++;
        bool last = feof(f) != 0;
        
        ZSTD_inBuffer ib = {inbuf, nread, 0};
        ZSTD_outBuffer ob = {outbuf, sizeof(outbuf), 0};
        
        while (true) {
            ob.pos = 0;
            size_t err = ZSTD_decompressStream(dctx, &ob, &ib);
            if (ZSTD_isError(err)) {
                fprintf(stderr, "CHUNK %d: ZSTD ERROR at ib=%zu/%zu: %s\n", 
                        chunks, ib.pos, ib.size, ZSTD_getErrorName(err));
                errors++;
                ZSTD_DCtx_reset(dctx, ZSTD_reset_session_and_parameters);
                ib.pos = ib.size;
                break;
            }
            if (ob.pos > 0) {
                // Scan tar headers from this buffer
                const char* p = outbuf;
                size_t remain = ob.pos;
                while (remain >= 512) {
                    const ustar_header* h = (const ustar_header*)p;
                    if (h->name[0] == '\0') { /* EOT */ break; }
                    if (memcmp(h->magic, "ustar", 5) == 0) {
                        total_entries++;
                        if (total_entries <= 3 || total_entries % 1000 == 0) {
                            uint64_t es = 0;
                            for (int i = 0; i < 12 && h->size[i] >= '0' && h->size[i] <= '7'; i++)
                                es = (es << 3) | (h->size[i] - '0');
                            fprintf(stderr, "chunk=%d entry[%d] type=%c size=%lu magic=%.5s\n", 
                                    chunks, total_entries, h->typeflag, (unsigned long)es, h->magic);
                        }
                    }
                    p += 512;
                    remain -= 512;
                }
            }
            if (last && err == 0) break;
            if (ob.pos == 0 && ib.pos >= ib.size) break;
        }
        if (last) {
            fprintf(stderr, "LAST CHUNK: entries=%d errors=%d\n", total_entries, errors);
            break;
        }
    }
    
    ZSTD_freeDCtx(dctx);
    fclose(f);
    fprintf(stderr, "FINAL: entries=%d errors=%d chunks=%d\n", total_entries, errors, chunks);
    return errors > 0 ? 1 : 0;
}
