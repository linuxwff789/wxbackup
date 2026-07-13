#!/system/bin/sh
# Ensure sqlcipher + libs are available at /data/local/ for the app
SRC=/data/data/com.termux/files/usr
DST=/data/local
BIN=sqlcipher
LIBS="libz.so.1 libcrypto.so.3 libedit.so libncursesw.so.6"

if [ ! -f "$DST/$BIN" ]; then
  cp "$SRC/bin/$BIN" "$DST/$BIN"
  chmod 755 "$DST/$BIN"
  echo "copied $BIN"
fi
for lib in $LIBS; do
  if [ ! -f "$DST/$lib" ]; then
    cp "$SRC/lib/$lib" "$DST/$lib"
    chmod 644 "$DST/$lib"
    echo "copied $lib"
  fi
done
echo "wxhook init done"