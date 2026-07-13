#!/system/bin/sh
BACKUP_DIR="/sdcard/Download/wxhook_backup"
WX_BASE="/proc/12950/root/data/data/com.tencent.mm/MicroMsg/6d1f34a5edc49e8b6d238141b2d004f3"
TAG=$(date +%Y%m%d_%H%M%S)

cp /sdcard/Download/EnMicroMsg.db "$BACKUP_DIR/EnMicroMsg_$TAG.db" && chmod 644 "$BACKUP_DIR/EnMicroMsg_$TAG.db"

for dir in image2 voice2 video cdn; do
    if [ -d "$WX_BASE/$dir" ]; then
        mkdir -p "$BACKUP_DIR/$dir"
        cp -r "$WX_BASE/$dir/"* "$BACKUP_DIR/$dir/" 2>/dev/null
        chmod -R 644 "$BACKUP_DIR/$dir/" 2>/dev/null
        echo "Copied $dir"
    fi
done
echo "BACKUP_DONE"
