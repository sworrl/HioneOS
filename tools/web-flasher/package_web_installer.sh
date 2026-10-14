#!/usr/bin/env bash
# Package a HioneOS fastboot image set into the static web installer (web/images + manifest.json).
#
# Produces exactly what web/install.html + web/js/install.js consume: a static images/ dir and
# a manifest.json with authoritative byte sizes. Handles the super.img size problem correctly:
# if super exceeds the per-flash chunk budget it is converted to sparse and split into
# super.N.img pieces (each a standalone sparse image carrying its own block offsets — the same
# thing CLI `fastboot flash super` streams), so the browser never range-splits a raw image.
#
# Usage:
#   package_web_installer.sh <image_dir> <version> [chunk_mb]
#     <image_dir>  dir holding boot.img init_boot.img magisk_patched_init_boot.img dtbo.img
#                  vendor_boot.img vbmeta.img vbmeta_system.img super.img
#     <version>    e.g. 0.9.223
#     [chunk_mb]   max per-flash chunk in MB (default 256; keep <= device max-download-size)
set -euo pipefail

IMG_DIR="${1:?image dir required}"
VERSION="${2:?version required}"
CHUNK_MB="${3:-256}"
CHUNK_BYTES=$(( CHUNK_MB * 1024 * 1024 ))

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB="$HERE/web"
OUT_IMAGES="$WEB/images"
MANIFEST="$WEB/manifest.json"

mkdir -p "$OUT_IMAGES"
sz() { stat -c%s "$1"; }
have() { command -v "$1" >/dev/null 2>&1; }

# Boot-chain images: partition name -> source file. Flashed to both A/B slots.
declare -A CHAIN=(
  [boot]=boot.img
  [dtbo]=dtbo.img
  [vendor_boot]=vendor_boot.img
  [vbmeta]=vbmeta.img
  [vbmeta_system]=vbmeta_system.img
)

json_imgs_install=""   # accumulates the "install" profile image entries
add_entry() { # partition name slot size sparse variant
  local part="$1" name="$2" slot="$3" size="$4" sparse="$5" variant="$6"
  local extra=""
  [ "$sparse" = "1" ] && extra+=', "sparse": true'
  [ -n "$variant" ] && extra+=", \"variant\": \"$variant\""
  json_imgs_install+="      { \"name\": \"$name\", \"partition\": \"$part\", \"target_slot\": \"$slot\", \"size\": $size$extra },
"
}

echo "==> Copying boot-chain images"
for part in "${!CHAIN[@]}"; do
  src="$IMG_DIR/${CHAIN[$part]}"
  [ -f "$src" ] || { echo "!! missing $src" >&2; exit 1; }
  cp -f "$src" "$OUT_IMAGES/${CHAIN[$part]}"
  add_entry "$part" "${CHAIN[$part]}" "both" "$(sz "$src")" 0 ""
done

echo "==> init_boot (stock + magisk-patched variants)"
cp -f "$IMG_DIR/init_boot.img" "$OUT_IMAGES/init_boot.img"
add_entry "init_boot" "init_boot.img" "both" "$(sz "$IMG_DIR/init_boot.img")" 0 "stock"
if [ -f "$IMG_DIR/magisk_patched_init_boot.img" ]; then
  cp -f "$IMG_DIR/magisk_patched_init_boot.img" "$OUT_IMAGES/magisk_patched_init_boot.img"
  add_entry "init_boot" "magisk_patched_init_boot.img" "both" "$(sz "$IMG_DIR/magisk_patched_init_boot.img")" 0 "root"
fi

echo "==> super (sparse-split if larger than ${CHUNK_MB} MB)"
SUPER="$IMG_DIR/super.img"
[ -f "$SUPER" ] || { echo "!! missing $SUPER" >&2; exit 1; }
SUPER_SZ=$(sz "$SUPER")
if [ "$SUPER_SZ" -le "$CHUNK_BYTES" ]; then
  cp -f "$SUPER" "$OUT_IMAGES/super.img"
  add_entry "super" "super.img" "none" "$SUPER_SZ" 0 ""
else
  # Convert raw -> sparse, then split into <=CHUNK sparse pieces. Prefer simg2simg (splits a
  # sparse image into size-bounded sparse files); fall back to img2simg -s chunking.
  have img2simg  || { echo "!! need 'img2simg' (android-sdk-libsparse-utils) to split super" >&2; exit 1; }
  tmp_sparse="$(mktemp --suffix=.simg)"
  echo "   img2simg super.img -> sparse"
  img2simg "$SUPER" "$tmp_sparse" 4096
  if have simg2simg; then
    echo "   simg2simg split @ ${CHUNK_BYTES} bytes"
    simg2simg "$tmp_sparse" "$OUT_IMAGES/super.img" "$CHUNK_BYTES"   # emits super.img.0, .1, ...
    i=0
    for f in "$OUT_IMAGES"/super.img.*; do
      mv -f "$f" "$OUT_IMAGES/super.$i.img"
      add_entry "super" "super.$i.img" "none" "$(sz "$OUT_IMAGES/super.$i.img")" 1 ""
      i=$((i+1))
    done
    echo "   split into $i sparse chunk(s)"
  else
    echo "!! 'simg2simg' not found — cannot auto-split. Install android-sdk-libsparse-utils," >&2
    echo "   or flash super via CLI: fastboot flash super super.img (uses -S auto-split)." >&2
    rm -f "$tmp_sparse"; exit 1
  fi
  rm -f "$tmp_sparse"
fi

echo "==> Writing manifest.json"
cat > "$MANIFEST" <<JSON
{
  "version": "$VERSION",
  "device": "HiBy M500 (HIONE)",
  "images_base": "images/",
  "profiles": {
    "root": {
      "label": "Root (Magisk init_boot, keep data)",
      "wipe_data": false,
      "images": [
        { "name": "magisk_patched_init_boot.img", "partition": "init_boot", "target_slot": "both", "size": $( [ -f "$OUT_IMAGES/magisk_patched_init_boot.img" ] && sz "$OUT_IMAGES/magisk_patched_init_boot.img" || echo 0 ) }
      ]
    },
    "install": {
      "label": "Full HioneOS install (wipes data)",
      "wipe_data": true,
      "images": [
$(printf '%s' "$json_imgs_install" | sed '$ s/,$//')
      ]
    }
  }
}
JSON

echo "==> Done. Static installer ready:"
echo "    page:     $WEB/install.html"
echo "    manifest: $MANIFEST"
echo "    images:   $OUT_IMAGES ($(ls -1 "$OUT_IMAGES" | wc -l) files)"
echo "    Serve $WEB over https and open install.html."
