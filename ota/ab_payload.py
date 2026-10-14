#!/usr/bin/env python3
"""Build a signed A/B system update payload (payload.bin) from a HioneOS super image.

The payload carries everything the wire flash (flash_hioneos_keepdata.sh) puts on the device:
the seven dynamic partitions inside super (system, system_ext, product, vendor, odm,
system_dlkm, vendor_dlkm) plus boot, init_boot, vendor_boot, dtbo, vbmeta and vbmeta_system.
update_engine on the M500 writes it to the inactive slot and switches slots on the next boot.

    # full payload of the image that was just built, signed with a throwaway key (no vault)
    hioneos/ota/ab_payload.py --super hioneos/out/hioneos_system_bundle.img --dry-run

    # real one, signed with the primary payload key from the vault
    hioneos/ota/ab_payload.py --super hioneos/out/hioneos_system_bundle.img

    # delta from the image a device runs now (much smaller download)
    hioneos/ota/ab_payload.py --super new_bundle.img --source-super old_bundle.img

Output goes to hioneos/ota/out/system/<build>/<name>/ (full, or from-<source build>):
payload.bin, payload_properties.txt (the four update_engine headers) and payload.json (what
publish.py --system-payload reads).

Same dynamic partition setup as HiBy's own OTAs for this device (read from their 1.00 and 1.20
payloads): group qti_dynamic_partitions of 5367267328 bytes, Virtual A/B with compression, gz,
COW version 2. Tools: delta_generator, lpunpack and ota_extractor from AOSP's otatools.zip
(android14, Apache-2.0), unpacked under --otatools.

Key handling: the private RSA-4096 keys live only in the vault (ft-hioneos-ota,
ab-payload-{primary,backup}-rsa4096.pem). Signing pipes `ftvault get` into openssl through bash
process substitution, the same way publish.py signs manifests. --dry-run never calls ftvault.
"""
import argparse
import base64
import hashlib
import io
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
HIONEOS = REPO / "hioneos"
FW_DIR = REPO / "m500-system-archive" / "firmware" / "extracted_1.00"
OUT_DIR = HIONEOS / "out"
KEYS_DIR = HERE / "keys"
OTATOOLS = Path(os.environ.get("HIONE_OTATOOLS", "/home/reaver/hioneos-scratch/otatools/tools"))
WORK_ROOT = Path(os.environ.get("HIONE_AB_WORK", "/home/reaver/hioneos-scratch/abota/work"))

FTVAULT = Path(os.environ.get("FTVAULT", str(Path.home() / ".local/bin/ftvault")))
VAULT_FOLDER = "ft-hioneos-ota"
VAULT_ENTRIES = {"primary": "ab-payload-primary-rsa4096.pem", "backup": "ab-payload-backup-rsa4096.pem"}
PUBKEYS = {"primary": KEYS_DIR / "ab-payload-primary.pub.pem", "backup": KEYS_DIR / "ab-payload-backup.pub.pem"}
CERTS = {"primary": KEYS_DIR / "ab-payload-primary.x509.pub.pem", "backup": KEYS_DIR / "ab-payload-backup.x509.pub.pem"}
SIGNATURE_SIZE = 512  # RSA-4096

# What super holds, as lpmake in build_hioneos_super.sh lays it out (all in slot _a).
DYNAMIC = ["system", "system_ext", "product", "vendor", "odm", "system_dlkm", "vendor_dlkm"]
GROUP = "qti_dynamic_partitions"
GROUP_SIZE = 5367267328  # super (5371461632) minus 4 MiB, as in HiBy's payloads
# Static partitions, from the same files the wire flash uses. recovery is left out on purpose:
# the wire flash never writes it, so the device's copy is whatever the factory put there.
STATIC = {
    "boot": FW_DIR / "boot.img",
    "init_boot": FW_DIR / "init_boot.img",
    "vendor_boot": FW_DIR / "vendor_boot.img",
    "dtbo": FW_DIR / "dtbo.img",
    "vbmeta": OUT_DIR / "vbmeta_disabled.img",
    "vbmeta_system": OUT_DIR / "vbmeta_system_disabled.img",
}
# Partition sizes on the M500 (bytes). An image bigger than its partition would fail on device.
STATIC_LIMITS = {"boot": 100663296, "init_boot": 8388608, "vendor_boot": 100663296, "dtbo": 8388608,
                 "vbmeta": 65536, "vbmeta_system": 65536}
OTACERTS_IN_SYSTEM = "system/etc/security/otacerts.zip"


def die(msg: str, code: int = 1):
    print(f"!! {msg}", file=sys.stderr)
    sys.exit(code)


def run(cmd, **kw) -> subprocess.CompletedProcess:
    kw.setdefault("capture_output", True)
    kw.setdefault("text", True)
    return subprocess.run([str(c) for c in cmd], check=False, **kw)


def must(cmd, what: str, **kw) -> subprocess.CompletedProcess:
    r = run(cmd, **kw)
    if r.returncode != 0:
        tail = "\n".join(((r.stderr or "") + (r.stdout or "")).strip().splitlines()[-15:])
        die(f"{what} failed (exit {r.returncode})\n{tail}")
    return r


def tool(name: str) -> Path:
    p = OTATOOLS / "bin" / name
    if not p.exists():
        die(f"{p} not found. Unpack AOSP otatools.zip (android14) into {OTATOOLS} or set HIONE_OTATOOLS")
    return p


def tool_env() -> dict:
    return dict(os.environ, LD_LIBRARY_PATH=str(OTATOOLS / "lib64"), PATH=f"{OTATOOLS / 'bin'}:{os.environ['PATH']}")


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def debugfs_cat(img: Path, path: str) -> bytes:
    r = subprocess.run(["debugfs", "-R", f"cat {path}", str(img)], capture_output=True, check=False)
    return r.stdout if r.returncode == 0 else b""


def cert_pubkey_der(pem: bytes) -> bytes:
    r = subprocess.run(["openssl", "x509", "-noout", "-pubkey"], input=pem, capture_output=True, check=False)
    if r.returncode != 0:
        return b""
    r2 = subprocess.run(["openssl", "pkey", "-pubin", "-outform", "DER"], input=r.stdout, capture_output=True, check=False)
    return r2.stdout if r2.returncode == 0 else b""


def pubkey_der(pem_path: Path) -> bytes:
    r = run(["openssl", "pkey", "-pubin", "-in", pem_path, "-outform", "DER"], text=False)
    return r.stdout if r.returncode == 0 else b""


def otacerts_keys(system_img: Path) -> list:
    """Public keys (DER) of every x509.pem in the image's otacerts.zip, which is what
    update_engine checks payload signatures against."""
    blob = debugfs_cat(system_img, OTACERTS_IN_SYSTEM)
    if not blob:
        return []
    keys = []
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        for n in z.namelist():
            if n.endswith("x509.pem"):
                k = cert_pubkey_der(z.read(n))
                if k:
                    keys.append(k)
    return keys


def build_prop(system_img: Path) -> dict:
    props = {}
    for line in debugfs_cat(system_img, "system/build.prop").decode("utf-8", "replace").splitlines():
        if "=" in line and not line.startswith("#"):
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    return props


def unpack_super(super_img: Path, dest: Path) -> dict:
    """lpunpack the _a partitions of a HioneOS super image. Returns {name: path}."""
    dest.mkdir(parents=True, exist_ok=True)
    stamp = dest / ".source"
    ident = f"{super_img.resolve()}:{super_img.stat().st_size}:{int(super_img.stat().st_mtime)}"
    have = {n: dest / f"{n}_a.img" for n in DYNAMIC}
    if not (stamp.exists() and stamp.read_text() == ident and all(p.exists() for p in have.values())):
        for p in dest.glob("*.img"):
            p.unlink()
        print(f"  lpunpack {super_img.name} -> {dest}")
        args = [tool("lpunpack")]
        for n in DYNAMIC:
            args += ["-p", f"{n}_a"]
        must(args + [super_img, dest], "lpunpack", env=tool_env())
        stamp.write_text(ident)
    for n, p in have.items():
        if not p.exists() or p.stat().st_size == 0:
            die(f"{super_img} has no {n}_a partition")
    return have


def gather(super_img: Path, work: Path) -> dict:
    parts = unpack_super(super_img, work)
    images = {}
    for n in ("boot", "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system"):
        p = STATIC[n]
        if not p.exists():
            die(f"{p} missing")
        if p.stat().st_size > STATIC_LIMITS[n]:
            die(f"{p} is {p.stat().st_size} bytes, more than the {n} partition holds ({STATIC_LIMITS[n]})")
        images[n] = p
    images.update(parts)
    return images


def write_dynamic_info(path: Path):
    path.write_text(
        f"super_partition_groups={GROUP}\n"
        f"{GROUP}_size={GROUP_SIZE}\n"
        f"{GROUP}_partition_list={' '.join(sorted(DYNAMIC))}\n"
        "virtual_ab=true\n"
        "virtual_ab_compression=true\n"
        "virtual_ab_compression_method=gz\n"
    )


# ----------------------------------------------------------------------------------------------
# Signing
# ----------------------------------------------------------------------------------------------
def sign_hashes_vault(key: str, payload_hash: Path, metadata_hash: Path, payload_sig: Path, metadata_sig: Path):
    """PKCS#1 v1.5 / SHA-256 signatures over the two hashes delta_generator wrote, the same
    openssl call AOSP's payload_signer.py makes. The key goes vault -> pipe -> openssl only."""
    if not FTVAULT.exists():
        die(f"{FTVAULT} not found")
    script = (
        'set -euo pipefail; set +x; '
        'openssl pkeyutl -sign -inkey <("$HIONE_FTVAULT" get "$HIONE_VAULT_FOLDER" "$HIONE_VAULT_ENTRY") '
        '-pkeyopt digest:sha256 -in "$HIONE_PH" -out "$HIONE_PS"; '
        'openssl pkeyutl -sign -inkey <("$HIONE_FTVAULT" get "$HIONE_VAULT_FOLDER" "$HIONE_VAULT_ENTRY") '
        '-pkeyopt digest:sha256 -in "$HIONE_MH" -out "$HIONE_MS"'
    )
    env = dict(os.environ, HIONE_FTVAULT=str(FTVAULT), HIONE_VAULT_FOLDER=VAULT_FOLDER,
               HIONE_VAULT_ENTRY=VAULT_ENTRIES[key], HIONE_PH=str(payload_hash), HIONE_PS=str(payload_sig),
               HIONE_MH=str(metadata_hash), HIONE_MS=str(metadata_sig))
    r = subprocess.run(["bash", "-c", script], env=env, check=False)
    if r.returncode != 0:
        die(f"signing with the {key} payload key failed (exit {r.returncode})")


def sign_hashes_throwaway(tmp: Path, payload_hash: Path, metadata_hash: Path, payload_sig: Path, metadata_sig: Path) -> Path:
    k, pub = tmp / "throwaway.pem", tmp / "throwaway.pub.pem"
    must(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:4096", "-out", k], "throwaway key")
    must(["openssl", "pkey", "-in", k, "-pubout", "-out", pub], "throwaway public key")
    for h, s in ((payload_hash, payload_sig), (metadata_hash, metadata_sig)):
        must(["openssl", "pkeyutl", "-sign", "-inkey", k, "-pkeyopt", "digest:sha256", "-in", h, "-out", s], "throwaway signing")
    k.unlink()
    return pub


def verify_payload(payload: Path, pub: Path) -> bool:
    r = run([tool("delta_generator"), f"--in_file={payload}", f"--public_key={pub}"], env=tool_env())
    return r.returncode == 0


# ----------------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--super", required=True, help="the HioneOS super image (OUTPUT_SUPER of build_hioneos_super.sh)")
    ap.add_argument("--source-super", help="super image the devices run now: makes a delta instead of a full payload")
    ap.add_argument("--build", help="HioneOS build of --super (default: ro.hioneos.version in its system/build.prop)")
    ap.add_argument("--source-build", help="HioneOS build of --source-super (default: read from it)")
    ap.add_argument("--key", choices=("primary", "backup"), default="primary", help="vault key to sign with")
    ap.add_argument("--out", help="output dir (default hioneos/ota/out/system/<build>/<full|from-X>)")
    ap.add_argument("--threads", type=int, default=4, help="delta_generator threads (default 4)")
    ap.add_argument("--no-apply-test", action="store_true", help="skip applying the payload on the PC with ota_extractor")
    ap.add_argument("--keep-work", action="store_true", help="keep the unpacked partitions for the next run")
    ap.add_argument("--allow-foreign-otacerts", action="store_true",
                    help="build even if the image does not trust the HioneOS payload keys (testing only: "
                         "a device on that image could never take another A/B update)")
    ap.add_argument("--dry-run", action="store_true", help="sign with a throwaway key; never touches the vault")
    args = ap.parse_args()

    super_img = Path(args.super).resolve()
    if not super_img.exists():
        die(f"{super_img} not found")
    for p in list(PUBKEYS.values()) + list(CERTS.values()):
        if not p.exists():
            die(f"{p} missing")
    for t in ("delta_generator", "lpunpack", "ota_extractor"):
        tool(t)

    WORK_ROOT.mkdir(parents=True, exist_ok=True)
    tag = hashlib.sha256(str(super_img).encode()).hexdigest()[:10]
    work_new = WORK_ROOT / f"new-{tag}"
    images = gather(super_img, work_new)

    props = build_prop(images["system"])
    build = args.build or props.get("ro.hioneos.version")
    if not build:
        die("no ro.hioneos.version in the image; pass --build")
    if args.build and props.get("ro.hioneos.version") and args.build != props["ro.hioneos.version"]:
        die(f"--build {args.build} but the image says ro.hioneos.version={props['ro.hioneos.version']}")

    # The new image must trust our payload keys, or a device that takes it is stuck: the next
    # payload would be refused and only a wire flash would get it further.
    trusted = otacerts_keys(images["system"])
    ours = {n: pubkey_der(p) for n, p in PUBKEYS.items()}
    if not any(k in trusted for k in ours.values()):
        msg = (f"{OTACERTS_IN_SYSTEM} in this image does not hold a HioneOS payload key "
               "(build it with HIONEOS_AB_OTA=1)")
        if args.allow_foreign_otacerts or args.dry_run:
            print(f"  WARNING: {msg}")
        else:
            die(msg)
    else:
        print("  image trusts the HioneOS payload keys: " + ", ".join(n for n, k in ours.items() if k in trusted))

    src_images, source_build = None, None
    if args.source_super:
        src = Path(args.source_super).resolve()
        if not src.exists():
            die(f"{src} not found")
        stag = hashlib.sha256(str(src).encode()).hexdigest()[:10]
        src_images = gather(src, WORK_ROOT / f"src-{stag}")
        source_build = args.source_build or build_prop(src_images["system"]).get("ro.hioneos.version")
        if not source_build:
            die("no ro.hioneos.version in the source image; pass --source-build")

    # Disk: the unpacked images are already here; the payload needs up to about half their size
    # and the apply test one partition at a time. Leave room for everything else on the disk.
    total_new = sum(p.stat().st_size for p in images.values())
    need = total_new // 2 + max(p.stat().st_size for p in images.values()) + (2 << 30)
    free = shutil.disk_usage(WORK_ROOT).free
    if free < need:
        die(f"{WORK_ROOT} has {free >> 30} GB free, this needs about {need >> 30} GB more (HIONE_AB_WORK moves it)")

    name = f"from-{source_build}" if source_build else "full"
    out = Path(args.out).resolve() if args.out else HERE / "out" / "system" / build / name
    out.mkdir(parents=True, exist_ok=True)
    payload = out / "payload.bin"
    final_json = out / "payload.json"
    for f in (payload, out / "payload_properties.txt", final_json, out / "DRY_RUN"):
        if f.exists():
            f.unlink()

    order = ["boot", "init_boot", "vendor_boot", "dtbo", "vbmeta", "vbmeta_system"] + DYNAMIC
    print(f"HioneOS A/B payload: build {build}, {name}, {len(order)} partitions")
    for n in order:
        print(f"  {n:<14} {images[n].stat().st_size:>12}  {images[n]}")

    max_ts = int(time.time())
    device_floor = int(props.get("ro.build.date.utc", "0") or 0)
    if max_ts < device_floor:
        die("clock is behind ro.build.date.utc; update_engine would refuse the payload")

    with tempfile.TemporaryDirectory(dir=WORK_ROOT) as td:
        td = Path(td)
        info = td / "dynamic_partitions_info.txt"
        write_dynamic_info(info)
        unsigned = td / "payload.unsigned.bin"
        gen = [tool("delta_generator"), f"--out_file={unsigned}",
               "--partition_names=" + ":".join(order),
               "--new_partitions=" + ":".join(str(images[n]) for n in order),
               "--major_version=2", f"--max_timestamp={max_ts}",
               f"--dynamic_partition_info_file={info}", f"--max_threads={args.threads}"]
        if src_images:
            gen += ["--old_partitions=" + ":".join(str(src_images[n]) for n in order), "--minor_version=8"]
        print("  generating (this takes a while)...")
        t0 = time.time()
        must(gen, "delta_generator", env=tool_env())
        print(f"  generated in {int(time.time() - t0)} s, {unsigned.stat().st_size} bytes unsigned")

        ph, mh, ps, ms = td / "payload.hash", td / "metadata.hash", td / "payload.sig", td / "metadata.sig"
        must([tool("delta_generator"), f"--in_file={unsigned}", f"--signature_size={SIGNATURE_SIZE}",
              f"--out_hash_file={ph}", f"--out_metadata_hash_file={mh}"], "hashing the payload", env=tool_env())
        if args.dry_run:
            check_pub = sign_hashes_throwaway(td, ph, mh, ps, ms)
        else:
            sign_hashes_vault(args.key, ph, mh, ps, ms)
            check_pub = PUBKEYS[args.key]
        for s in (ps, ms):
            if s.stat().st_size != SIGNATURE_SIZE:
                die(f"{s.name} is {s.stat().st_size} bytes, expected {SIGNATURE_SIZE}")
        must([tool("delta_generator"), f"--in_file={unsigned}", f"--signature_size={SIGNATURE_SIZE}",
              f"--payload_signature_file={ps}", f"--metadata_signature_file={ms}", f"--out_file={payload}"],
             "inserting the signatures", env=tool_env())

        # Verify the way the device will: payload + metadata signature against the key.
        if not verify_payload(payload, check_pub):
            payload.unlink()
            die("the signed payload does not verify with the key it was signed with")
        if args.dry_run:
            for n, p in PUBKEYS.items():
                if verify_payload(payload, p):
                    die(f"dry run: the real {n} key accepted a throwaway signature")
        else:
            other = "backup" if args.key == "primary" else "primary"
            if verify_payload(payload, PUBKEYS[other]):
                die("the payload verifies with the wrong key too; something is very wrong")
            if PUBKEYS[args.key] and ours[args.key] not in trusted and not args.allow_foreign_otacerts:
                die(f"the image does not trust the {args.key} key it was just signed with")
        print(f"  signature ok ({'throwaway' if args.dry_run else args.key} key)")

    props_file = out / "payload_properties.txt"
    must([tool("delta_generator"), f"--in_file={payload}", f"--properties_file={props_file}"],
         "writing payload_properties.txt", env=tool_env())
    headers = [l.strip() for l in props_file.read_text().splitlines() if l.strip()]
    hd = dict(h.split("=", 1) for h in headers)
    for k in ("FILE_HASH", "FILE_SIZE", "METADATA_HASH", "METADATA_SIZE"):
        if k not in hd:
            die(f"payload_properties.txt has no {k}")
    size = payload.stat().st_size
    if int(hd["FILE_SIZE"]) != size:
        die("FILE_SIZE does not match payload.bin")
    sha = sha256_file(payload)
    if base64.b64encode(bytes.fromhex(sha)).decode() != hd["FILE_HASH"]:
        die("FILE_HASH does not match payload.bin")

    # Apply it on the PC with update_engine's own code (ota_extractor) and compare every
    # partition with what went in. A delta is applied on top of the source images. One
    # partition at a time, so the test never needs more than the biggest one in extra space.
    if not args.no_apply_test:
        print("  apply test: applying the payload with ota_extractor, one partition at a time...")
        with tempfile.TemporaryDirectory(dir=WORK_ROOT) as xd:
            xd = Path(xd)
            base = [tool("ota_extractor"), f"--payload={payload}", f"--output_dir={xd}"]
            if src_images:
                ind = xd / "in"
                ind.mkdir()
                for n in order:
                    os.symlink(src_images[n], ind / f"{n}.img")
                base.append(f"--input_dir={ind}")
            for n in order:
                want = images[n]
                want_sha = sha256_file(want)
                must(base + [f"--partitions={n}"], f"ota_extractor ({n})", env=tool_env())
                got = xd / f"{n}.img"
                if not got.exists():
                    die(f"apply test: {n} missing from the extracted payload")
                ok = got.stat().st_size == want.stat().st_size and sha256_file(got) == want_sha
                got.unlink()
                if not ok:
                    die(f"apply test: {n} does not match its image after applying the payload")
                print(f"    {n:<14} ok")
        print("  apply test ok: every partition comes out byte for byte")

    meta = {
        "build": build,
        "type": "delta" if src_images else "full",
        "source_build": source_build,
        "file": "payload.bin",
        "offset": 0,
        "size": size,
        "sha256": sha,
        "headers": headers,
        "partitions": order,
        "max_timestamp": max_ts,
        "signed_with": "throwaway" if args.dry_run else args.key,
        "created": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    final_json.write_text(json.dumps(meta, indent=2) + "\n")
    if args.dry_run:
        (out / "DRY_RUN").write_text("signed with a throwaway key: devices refuse it, publish.py refuses to list it\n")
    if not args.keep_work:
        shutil.rmtree(work_new, ignore_errors=True)
        if src_images:
            shutil.rmtree(WORK_ROOT / f"src-{stag}", ignore_errors=True)
    print(f"done: {payload} ({size} bytes)")
    print(f"      {final_json}")


if __name__ == "__main__":
    main()
