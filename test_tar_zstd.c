// test_tar_zstd.c - Standalone test for zstd decompression + tar scanning
// Build: gcc -o test_tar_zstd test_tar_zstd.c -lzstd
// Run: ./test_tar_zstd /sdcard/.../archive.tar.zst "hash/file_manifest.json"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zstd.h>

// Tar header structure (ustar format)
typedef struct {
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
} ustar_header;

int main(int argc, char** argv) {
    if (argc < 3) {
        fprintf(stderr, "Usage: %s <archive.tar.zst> <target_file>\n", argv[0]);
        return 1;
    }
    const char* archive_path = argv[1];
    const char* target = argv[2];
    
    // Open and read the compressed file
    FILE* f = fopen(archive_path, "rb");
    if (!f) { perror("fopen"); return 1; }
    fseek(f, 0, SEEK_END);
    long fsize = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (fsize <= 0) { fprintf(stderr, "Empty file\n"); fclose(f); return 1; }
    
    char* compressed = (char*)malloc(fsize);
    if (!compressed) { fprintf(stderr, "malloc failed\n"); fclose(f); return 1; }
    size_t read_size = fread(compressed, 1, fsize, f);
    fclose(f);
    fprintf(stderr, "Read %zu bytes (file size %ld)\n", read_size, fsize);
    
    // Check zstd magic
    if (fsize >= 4 && compressed[0] == 0x28 && compressed[1] == 0xB5 && 
        compressed[2] == 0x2F && compressed[3] == 0xFD) {
        fprintf(stderr, "Zstd magic OK\n");
    } else {
        fprintf(stderr, "NOT zstd format (magic: %02x %02x %02x %02x)\n",
                compressed[0], compressed[1], compressed[2], compressed[3]);
    }
    
    // Check content size
    unsigned long long content_size = ZSTD_getFrameContentSize(compressed, fsize);
    fprintf(stderr, "ZSTD_getFrameContentSize: %llu (0x%llx)\n", content_size, content_size);
    if (content_size == ZSTD_CONTENTSIZE_ERROR)
        fprintf(stderr, "  -> ZSTD_CONTENTSIZE_ERROR\n");
    if (content_size == ZSTD_CONTENTSIZE_UNKNOWN)
        fprintf(stderr, "  -> ZSTD_CONTENTSIZE_UNKNOWN (no content size in frame)\n");
    
    // Streaming decompression
    ZSTD_DCtx* dctx = ZSTD_createDCtx();
    if (!dctx) { fprintf(stderr, "ZSTD_createDCtx failed\n"); free(compressed); return 1; }
    
    char outbuf[262144]; // 256KB
    ZSTD_outBuffer ob = {outbuf, sizeof(outbuf), 0};
    ZSTD_inBuffer ib = {compressed, (size_t)fsize, 0};
    
    size_t total_decompressed = 0;
    int frame_count = 0;
    int error_count = 0;
    
    while (ib.pos < ib.size) {
        ob.pos = 0;
        size_t ret = ZSTD_decompressStream(dctx, &ob, &ib);
        if (ZSTD_isError(ret)) {
            fprintf(stderr, "ZSTD error at pos %zu/%zu: %s\n", 
                    ib.pos, ib.size, ZSTD_getErrorName(ret));
            error_count++;
            if (error_count > 5) { fprintf(stderr, "Too many errors, aborting\n"); break; }
            ZSTD_DCtx_reset(dctx, ZSTD_reset_session_and_parameters);
            continue;
        }
        total_decompressed += ob.pos;
        if (ret == 0) {
            frame_count++;
            fprintf(stderr, "Frame %d ended, total decompressed: %zu, ib: %zu/%zu\n", 
                    frame_count, total_decompressed, ib.pos, ib.size);
        }
    }
    ZSTD_freeDCtx(dctx);
    
    fprintf(stderr, "--- Decompression done: %zu bytes, %d frames, %d errors ---\n", 
            total_decompressed, frame_count, error_count);
            
    // Re-decompress for tar scanning (now we know it should work)
    // Actually, let's just decompress the entire thing into memory
    ZSTD_DCtx* dctx2 = ZSTD_createDCtx();
    if (!dctx2) { free(compressed); return 1; }
    
    // For simplicity, decompress in chunks and build a string
    // We'll do a two-pass approach: first find the target, then extract it
    // But for now, let's just decompress into a large buffer (we can realloc)
    size_t cap = 64 * 1024 * 1024; // 64MB initial
    size_t len = 0;
    char* decompressed = (char*)malloc(cap);
    if (!decompressed) { free(compressed); ZSTD_freeDCtx(dctx2); return 1; }
    
    ib = (ZSTD_inBuffer){compressed, (size_t)fsize, 0};
    while (ib.pos < ib.size) {
        ob.pos = 0;
        size_t ret = ZSTD_decompressStream(dctx2, &ob, &ib);
        if (ZSTD_isError(ret)) { 
            ZSTD_DCtx_reset(dctx2, ZSTD_reset_session_and_parameters);
            continue; 
        }
        if (ob.pos > 0) {
            if (len + ob.pos > cap) {
                cap *= 2;
                decompressed = (char*)realloc(decompressed, cap);
                if (!decompressed) { free(compressed); ZSTD_freeDCtx(dctx2); return 1; }
            }
            memcpy(decompressed + len, outbuf, ob.pos);
            len += ob.pos;
        }
        if (ret == 0 && ob.pos == 0) break;
    }
    ZSTD_freeDCtx(dctx2);
    free(compressed);
    
    fprintf(stderr, "Full decompress: %zu bytes (alloc %zu)\n", len, cap);
    
    // Now scan tar headers
    size_t pos = 0;
    int entries = 0;
    int found = 0;
    
    while (pos + 512 <= len) {
        ustar_header* h = (ustar_header*)(decompressed + pos);
        if (h->name[0] == '\0') break;
        if (memcmp(h->magic, "ustar", 5) != 0) { pos += 512; continue; }
        
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
        
        entries++;
        if (entries <= 3 || entries % 1000 == 0 || strcmp(path, target) == 0) {
            fprintf(stderr, "  [%d] type=%c path=%s size=%lu pl=%zu\n", 
                    entries, h->typeflag, path, (unsigned long)entry_size, pl);
        }
        
        if (strcmp(path, target) == 0) {
            if (h->typeflag == '0' || h->typeflag == '\0') {
                size_t data_start = pos + 512;
                fprintf(stderr, "FOUND! data at %zu, size %lu\n", data_start, (unsigned long)entry_size);
                if (data_start + entry_size <= len) {
                    // Print first 200 bytes of content
                    fprintf(stderr, "Content preview: %.*s\n", 200, decompressed + data_start);
                }
            } else {
                fprintf(stderr, "FOUND but typeflag=%c (not regular)\n", h->typeflag);
            }
            found = 1;
            break;
        }
        
        size_t skip = 512 + entry_size + ((512 - (entry_size % 512)) % 512);
        pos += skip;
    }
    
    fprintf(stderr, "\nSummary: %d tar entries, %s\n", entries, found ? "TARGET FOUND" : "TARGET NOT FOUND");
    
    free(decompressed);
    return found ? 0 : 1;
}
