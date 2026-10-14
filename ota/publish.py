#!/usr/bin/env python3
"""Publish HioneOS app updates for Hione Update (com.hione.update).

Collects the release APKs of HioneOS's own apps from the Gradle build outputs, writes a channel
manifest, signs it with the OTA key from the vault, verifies the signature with the public key,
and stages everything in a directory that mirrors the server. With --upload it also pushes the
staged files to the server that hosts hioneos.falcontechnix.com and checks the live copy.

    # look at what would be published, sign with a throwaway key, touch nothing else
    hioneos/ota/publish.py --channel dev --changelog notes.txt --dry-run

    # real signature, staged in hioneos/ota/out, nothing uploaded (good for a LAN test)
    hioneos/ota/publish.py --channel dev --changelog notes.txt

    # real signature and upload
    hioneos/ota/publish.py --channel stable --changelog notes.txt --upload

Stdlib only, plus the openssl CLI, aapt2 and apksigner. See README.md next to this file.

Key handling: the private keys live only in the vault. Signing pipes `ftvault get` straight into
openssl through bash process substitution, so the key exists only in a pipe, never in a file, an
argument, an environment variable or this process's memory. --dry-run never calls ftvault.
"""
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

# ----------------------------------------------------------------------------------------------
# Configuration. Host and paths are the ones the web installer is deployed with
# (~/Documents/GitHub/webdev/hioneos-site/publish.sh rsyncs hioneos.falcontechnix.com to rieska).
# ----------------------------------------------------------------------------------------------
HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
MODULES_DIR = REPO / "hione-player-kotlin"
BUILD_SCRIPT = REPO / "hioneos" / "build" / "build_hioneos_super.sh"
KEYS_DIR = HERE / "keys"
PUBKEYS = {
    "primary": KEYS_DIR / "ota-signing-primary-p521.pub.pem",
    "backup": KEYS_DIR / "ota-signing-backup-p521.pub.pem",
}
BUILD_TOOLS = Path(os.environ.get("HIONE_BUILD_TOOLS", "/home/reaver/Android/Sdk/build-tools/35.0.0"))
AAPT2 = BUILD_TOOLS / "aapt2"
APKSIGNER = BUILD_TOOLS / "apksigner"

FTVAULT = Path(os.environ.get("FTVAULT", str(Path.home() / ".local/bin/ftvault")))
VAULT_FOLDER = "ft-hioneos-ota"
VAULT_ENTRIES = {
    "primary": "ota-signing-primary-p521.pem",
    "backup": "ota-signing-backup-p521.pem",
}

PUBLIC_ORIGIN = "https://hioneos.falcontechnix.com"
PUBLIC_OTA = PUBLIC_ORIGIN + "/ota"
INSTALLER_URL = PUBLIC_ORIGIN + "/install/"

DEPLOY_HOST = "root@10.10.10.5"                           # rieska, behind Cloudflare
DEPLOY_PATH = "/var/www/html/falcontechnix/hioneos/ota"    # site root is .../falcontechnix/hioneos/
DEPLOY_OWNER = "1000:1000"                                # what the site publish.sh chowns to
CLOUDFLARE_ENV = Path.home() / ".config/cloudflare/.env"  # CF_API_TOKEN, CF_ZONE_ID_FALCONTECHNIX
CF_ZONE_VAR = "CF_ZONE_ID_FALCONTECHNIX"
SITE_PUBLISH = Path.home() / "Documents/GitHub/webdev/hioneos-site/publish.sh"

CHANNELS = ("stable", "beta", "dev")
SCHEMA_VERSION = 1
NEVER_PUBLISH = {"com.caf.fmradio", "com.hione.tv"}  # FM is image-only; TV is for Android TV sets, not the M500 image


def updatable(pkg: str) -> bool:
    if pkg in NEVER_PUBLISH:
        return False
    return pkg.startswith("com.hione.") or pkg == "com.m500.hardware"


# ----------------------------------------------------------------------------------------------
def die(msg: str, code: int = 1):
    print(f"error: {msg}", file=sys.stderr)
    sys.exit(code)


def run(cmd, **kw) -> subprocess.CompletedProcess:
    return subprocess.run(cmd, check=False, text=kw.pop("text", True), capture_output=True, **kw)


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def default_build() -> str:
    m = re.search(r'HIONEOS_VERSION="\$\{HIONEOS_VERSION:-([^}"]+)\}"', BUILD_SCRIPT.read_text())
    if not m:
        die(f"could not read HIONEOS_VERSION from {BUILD_SCRIPT}; pass --build")
    return m.group(1)


def badging(apk: Path) -> dict:
    r = run([str(AAPT2), "dump", "badging", str(apk)])
    if r.returncode != 0:
        die(f"aapt2 could not read {apk}: {r.stderr.strip()}")
    out = r.stdout
    pkg = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not pkg:
        die(f"no package line in aapt2 output for {apk}")
    sdk = re.search(r"(?:minSdkVersion|sdkVersion):'(\d+)'", out)
    return {
        "package": pkg.group(1),
        "versionCode": int(pkg.group(2)),
        "versionName": pkg.group(3),
        "min_sdk": int(sdk.group(1)) if sdk else 0,
    }


def signer_digest(apk: Path, fatal: bool = True):
    """SHA-256 of the single signer certificate. Unsigned or multi-signer: die, or None if not fatal."""
    r = run([str(APKSIGNER), "verify", "--print-certs", str(apk)])
    digests = re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]{64})", r.stdout)
    problem = None
    if r.returncode != 0:
        problem = f"apksigner rejects it: {(r.stderr or r.stdout).strip().splitlines()[0]}"
    elif len(digests) != 1:
        problem = f"{len(digests)} signers; Hione Update only accepts single-signer APKs"
    if problem:
        if fatal:
            die(f"{apk}: {problem}")
        print(f"  skip  {apk.name}  ({problem})")
        return None
    return digests[0]


def check_text_style(label: str, text: str):
    """The user's house style: plain, short, no emojis, no em dashes."""
    bad = []
    if "\u2014" in text:
        bad.append("an em dash")
    if re.search("[\U0001F000-\U0001FAFF\u2600-\u27BF\uFE0F]", text):
        bad.append("an emoji")
    if bad:
        die(f"{label} contains {' and '.join(bad)}. Rewrite it plainly (or pass --allow-style).")


# ----------------------------------------------------------------------------------------------
def collect(args) -> list:
    """Release APKs of HioneOS apps, newest versionCode per package."""
    candidates = [Path(p) for p in args.apk]
    if not args.no_scan:
        candidates += sorted(MODULES_DIR.glob("*/build/outputs/apk/release/*.apk"))
    best = {}
    for apk in candidates:
        if not apk.is_file():
            die(f"{apk} does not exist")
        info = badging(apk)
        pkg = info["package"]
        if pkg in NEVER_PUBLISH:
            if str(apk) in args.apk:
                die(f"{apk} is {pkg}. The FM app only ever ships in the system image.")
            print(f"  skip  {pkg:<22} {apk.name}  (image only, never an app update)")
            continue
        if not updatable(pkg):
            print(f"  skip  {pkg:<22} {apk.name}  (not a HioneOS app)")
            continue
        if args.only and pkg not in args.only:
            continue
        if pkg in args.exclude:
            print(f"  skip  {pkg:<22} {apk.name}  (--exclude)")
            continue
        info["path"] = apk
        info["signer"] = signer_digest(apk, fatal=str(apk) in args.apk)
        if info["signer"] is None:
            continue
        prev = best.get(pkg)
        if prev is None or (info["versionCode"], apk.stat().st_mtime) > (prev["versionCode"], prev["path"].stat().st_mtime):
            best[pkg] = info
    if args.only:
        missing = set(args.only) - set(best)
        if missing:
            die(f"--only names packages with no release APK: {', '.join(sorted(missing))}")
    if not best:
        die("no HioneOS release APKs found. Build them first (./gradlew :<module>:assembleRelease).")
    return [best[k] for k in sorted(best)]


def stage_apks(apks: list, out: Path, expect_signer):
    signers = {}
    for a in apks:
        signers.setdefault(a["signer"], []).append(a["package"])
    if len(signers) != 1:
        lines = "\n".join(f"    {d}: {', '.join(p)}" for d, p in signers.items())
        die("the APKs are not all signed with one key (a missing keystore makes Gradle fall back "
            "to the debug key). Devices would refuse the odd ones out:\n" + lines)
    signer = next(iter(signers))
    if expect_signer and signer != expect_signer.lower():
        die(f"APKs are signed by {signer}, not the expected {expect_signer}")

    for a in apks:
        rel = f"apks/{a['package']}/{a['versionCode']}/{a['package']}-{a['versionCode']}.apk"
        dest = out / "ota" / rel
        sha = sha256_file(a["path"])
        if dest.exists():
            if sha256_file(dest) != sha:
                die(f"{a['package']} versionCode {a['versionCode']} is already staged with different bytes. "
                    "Published APKs are immutable: bump versionCode and rebuild.")
        else:
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(a["path"], dest)
        a.update(rel=rel, sha256=sha, size=dest.stat().st_size)
    return signer


def collect_revocations(args, chan_dir: Path) -> list:
    """--revoke PKG=VC[:reason] entries, plus the ones the channel's previous staged manifest
    already carried (a manifest is the whole state, so a revocation must stay listed until no
    device can still be on that version). --drop-revocations starts clean."""
    rev = {}
    prev = chan_dir / "manifest.json"
    if prev.exists() and not args.drop_revocations:
        try:
            for r in json.loads(prev.read_bytes()).get("revoked") or []:
                rev[(r["package"], int(r["versionCode"]))] = r.get("reason", "")
        except (ValueError, KeyError):
            die(f"could not read revocations from {prev}; fix it or pass --drop-revocations")
    for item in args.revoke:
        m = re.match(r"^([\w.]+)=(\d+)(?::(.*))?$", item)
        if not m:
            die(f"--revoke wants PKG=VERSIONCODE[:reason], got {item}")
        pkg, vc, reason = m.group(1), int(m.group(2)), (m.group(3) or "").strip()
        if not updatable(pkg):
            die(f"--revoke {pkg}: not a package Hione Update manages")
        rev[(pkg, vc)] = reason
    out = [{"package": p, "versionCode": v, "reason": r} for (p, v), r in sorted(rev.items())]
    for r in out:
        if r["reason"] and not args.allow_style:
            check_text_style(f"the revoke reason for {r['package']}", r["reason"])
        print(f"  revoke {r['package']} {r['versionCode']}" + (f"  ({r['reason']})" if r["reason"] else ""))
    return out


def build_manifest(args, apks: list, revoked: list) -> dict:
    changelog = Path(args.changelog).read_text(encoding="utf-8").strip()
    if not args.allow_style:
        check_text_style("the changelog", changelog)
    system_update = None
    if args.system_update_build:
        notes = Path(args.system_update_notes).read_text(encoding="utf-8").strip() if args.system_update_notes else ""
        if notes and not args.allow_style:
            check_text_style("the system update notes", notes)
        system_update = {
            "required": bool(args.system_update_required),
            "build": args.system_update_build,
            "notes": notes,
            "installer_url": args.installer_url,
        }
    entries = []
    for a in apks:
        url = f"{args.url_base.rstrip('/')}/{a['rel']}" if args.url_base else f"../{a['rel']}"
        entries.append({
            "package": a["package"],
            "versionCode": a["versionCode"],
            "versionName": a["versionName"],
            "url": url,
            "size": a["size"],
            "sha256": a["sha256"],
            "min_sdk": a["min_sdk"],
            "requires_build": args.requires_build.get(a["package"]),
        })
    return {
        "schema_version": SCHEMA_VERSION,
        "channel": args.channel,
        "build": args.build,
        "min_build": args.min_build,
        "published_at": dt.datetime.now(dt.timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "changelog": changelog,
        "system_update": system_update,
        "apks": entries,
        "revoked": revoked,
    }


# ----------------------------------------------------------------------------------------------
# Signing. The only place ftvault is called.
# ----------------------------------------------------------------------------------------------
def sign_with_vault(manifest: Path, key: str) -> bytes:
    """DER signature over the exact bytes of [manifest]. The private key only ever travels
    ftvault -> pipe -> openssl. Nothing here sees it, stores it or prints it."""
    if not FTVAULT.exists():
        die(f"{FTVAULT} not found")
    script = (
        'set -euo pipefail; set +x; '
        'openssl dgst -sha512 -sign <("$HIONE_FTVAULT" get "$HIONE_VAULT_FOLDER" "$HIONE_VAULT_ENTRY") '
        '-binary "$HIONE_MANIFEST"'
    )
    env = dict(os.environ,
               HIONE_FTVAULT=str(FTVAULT), HIONE_VAULT_FOLDER=VAULT_FOLDER,
               HIONE_VAULT_ENTRY=VAULT_ENTRIES[key], HIONE_MANIFEST=str(manifest))
    # stdin/stderr stay on the terminal so a vault or key passphrase prompt still works;
    # stdout is the signature only.
    r = subprocess.run(["bash", "-c", script], env=env, stdout=subprocess.PIPE, check=False)
    if r.returncode != 0 or not r.stdout:
        die(f"signing with the {key} key failed (exit {r.returncode}); nothing was written")
    return r.stdout


def sign_with_throwaway(manifest: Path, tmp: Path):
    """Dry run: a fresh P-521 key in a temp dir, deleted afterwards. Never the vault."""
    k, pub = tmp / "throwaway.pem", tmp / "throwaway.pub.pem"
    for cmd in (["openssl", "ecparam", "-name", "secp521r1", "-genkey", "-noout", "-out", str(k)],
                ["openssl", "ec", "-in", str(k), "-pubout", "-out", str(pub)]):
        r = run(cmd)
        if r.returncode != 0:
            die(f"{' '.join(cmd[:2])} failed: {r.stderr.strip()}")
    r = run(["openssl", "dgst", "-sha512", "-sign", str(k), "-binary", str(manifest)], text=False)
    if r.returncode != 0:
        die("throwaway signing failed")
    return r.stdout, pub


def openssl_verify(manifest: Path, der: bytes, pub: Path) -> bool:
    with tempfile.NamedTemporaryFile(suffix=".der") as f:
        f.write(der)
        f.flush()
        r = run(["openssl", "dgst", "-sha512", "-verify", str(pub), "-signature", f.name, str(manifest)])
        return r.returncode == 0 and "Verified OK" in r.stdout


def which_key(manifest: Path, der: bytes):
    for name, pub in PUBKEYS.items():
        if openssl_verify(manifest, der, pub):
            return name
    return None


def sig_text(der: bytes) -> bytes:
    """manifest.json.sig format: base64 of the DER signature, one line, trailing newline."""
    return base64.b64encode(der) + b"\n"


def read_sig(p: Path) -> bytes:
    return base64.b64decode(b"".join(p.read_bytes().split()))


# ----------------------------------------------------------------------------------------------
# Upload
# ----------------------------------------------------------------------------------------------
def ssh(cmd: str) -> subprocess.CompletedProcess:
    return run(["ssh", "-o", "BatchMode=yes", DEPLOY_HOST, cmd])


def check_site_publish():
    """The site's publish.sh rsyncs the whole site with --delete. Without an exclude for /ota it
    deletes every published update the next time the web installer is published."""
    if not SITE_PUBLISH.exists():
        return
    text = SITE_PUBLISH.read_text()
    if not re.search(r"--exclude[ =]['\"]?/?ota\b", text):
        die(f"{SITE_PUBLISH} runs rsync --delete over the whole site and would wipe /ota/ next time.\n"
            "  Add  --exclude /ota  to its rsync line first, e.g.\n"
            "    rsync -az --delete --exclude publish.sh --exclude lost+found --exclude /ota \"$here/\" root@10.10.10.5:/var/www/html/falcontechnix/hioneos/\n"
            "  (or pass --skip-site-check if you have handled it another way)")


def upload(args, out: Path, channel: str, apks: list):
    chan_dir = out / "ota" / channel
    manifest, sig = chan_dir / "manifest.json", chan_dir / "manifest.json.sig"
    if (out / "DRY_RUN").exists() or not sig.exists():
        die("the staged channel has no real signature (dry run?). Run without --dry-run first.")
    key = which_key(manifest, read_sig(sig))
    if not key:
        die("the staged manifest does not verify with either public key; refusing to upload")
    if not args.skip_site_check:
        check_site_publish()

    r = ssh("true")
    if r.returncode != 0:
        die(f"cannot ssh to {DEPLOY_HOST} ({r.stderr.strip()}). Nothing was uploaded.")

    # Immutability: an APK path that already exists on the server must hold the same bytes.
    rels = [a["rel"] for a in apks]
    r = ssh(f"cd {DEPLOY_PATH} 2>/dev/null && sha256sum " + " ".join(f"'{p}'" for p in rels) + " 2>/dev/null; true")
    remote = {}
    for line in r.stdout.splitlines():
        parts = line.split(None, 1)
        if len(parts) == 2:
            remote[parts[1].strip().lstrip("*")] = parts[0]
    for a in apks:
        if a["rel"] in remote and remote[a["rel"]] != a["sha256"]:
            die(f"the server already has {a['rel']} with different bytes. Bump versionCode; published APKs never change.")

    print(f"uploading to {DEPLOY_HOST}:{DEPLOY_PATH}")
    r = ssh(f"mkdir -p {DEPLOY_PATH}/apks {DEPLOY_PATH}/{channel}/.incoming")
    if r.returncode != 0:
        die(f"mkdir on server failed: {r.stderr.strip()}")
    # APKs first and never overwritten, never deleted. A manifest must not go live before its APKs.
    r = subprocess.run(["rsync", "-a", "--ignore-existing", "--chmod=D755,F644",
                        f"{out}/ota/apks/", f"{DEPLOY_HOST}:{DEPLOY_PATH}/apks/"])
    if r.returncode != 0:
        die("rsync of the APKs failed; the manifest was not uploaded")
    r = subprocess.run(["rsync", "-a", "--chmod=F644", str(manifest), str(sig),
                        f"{DEPLOY_HOST}:{DEPLOY_PATH}/{channel}/.incoming/"])
    if r.returncode != 0:
        die("rsync of the manifest failed")
    # Swap both in together so a client never pairs a new manifest with an old signature for long.
    r = ssh(f"cd {DEPLOY_PATH}/{channel} && mv -f .incoming/manifest.json.sig manifest.json.sig.new && "
            f"mv -f .incoming/manifest.json manifest.json.new && "
            f"mv -f manifest.json.new manifest.json && mv -f manifest.json.sig.new manifest.json.sig && "
            f"rmdir .incoming; chown -R {DEPLOY_OWNER} {DEPLOY_PATH}")
    if r.returncode != 0:
        die(f"putting the manifest in place failed: {r.stderr.strip()}")

    purge_cloudflare([f"{PUBLIC_OTA}/{channel}/manifest.json", f"{PUBLIC_OTA}/{channel}/manifest.json.sig"])
    verify_live(channel, expect_bytes=manifest.read_bytes())


def purge_cloudflare(urls: list):
    if not CLOUDFLARE_ENV.exists():
        print(f"  note: {CLOUDFLARE_ENV} missing, skipped the Cloudflare purge")
        return
    env = {}
    for line in CLOUDFLARE_ENV.read_text().splitlines():
        m = re.match(r"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$", line)
        if m:
            env[m.group(1)] = m.group(2).strip().strip("'\"")
    token, zone = env.get("CF_API_TOKEN"), env.get(CF_ZONE_VAR)
    if not token or not zone:
        print("  note: Cloudflare token or zone id missing, skipped the purge")
        return
    req = urllib.request.Request(
        f"https://api.cloudflare.com/client/v4/zones/{zone}/purge_cache",
        data=json.dumps({"files": urls}).encode(), method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            ok = json.load(resp).get("success")
    except Exception as e:  # noqa: BLE001
        ok = f"failed ({e.__class__.__name__})"
    print(f"  cloudflare purge: {ok}")


def fetch(url: str, headers=None, method="GET"):
    req = urllib.request.Request(url, headers={"User-Agent": "hioneos-ota-publish", "Cache-Control": "no-cache", **(headers or {})}, method=method)
    return urllib.request.urlopen(req, timeout=30)


def verify_live(channel: str, expect_bytes=None, base=PUBLIC_OTA):
    """Fetch the live manifest + signature and check them, then range-probe every APK."""
    murl = f"{base}/{channel}/manifest.json"
    print(f"checking {murl}")
    try:
        body = fetch(murl).read()
        sig = fetch(murl + ".sig").read()
    except urllib.error.URLError as e:
        die(f"could not fetch the live manifest: {e}")
    if expect_bytes is not None and body != expect_bytes:
        die("the live manifest differs from the one just uploaded (cache?). Check Cloudflare.")
    with tempfile.TemporaryDirectory() as td:
        mp = Path(td) / "manifest.json"
        mp.write_bytes(body)
        key = which_key(mp, base64.b64decode(b"".join(sig.split())))
    if not key:
        die("the live manifest does NOT verify. Devices will ignore it.")
    m = json.loads(body)
    print(f"  signature ok ({key} key), {len(m['apks'])} apk(s), published {m['published_at']}")
    for a in m["apks"]:
        url = urllib.parse.urljoin(murl, a["url"])
        try:
            with fetch(url, headers={"Range": "bytes=0-0"}) as r:
                status, total = r.status, (r.headers.get("Content-Range") or "").rsplit("/", 1)[-1]
        except urllib.error.URLError as e:
            die(f"{url}: {e}")
        if status != 206 or total != str(a["size"]):
            die(f"{url}: expected 206 with total {a['size']}, got {status} / {total!r} (range requests needed for resume)")
        print(f"  ok  {a['package']} {a['versionCode']}  {a['size']} bytes, ranges work")


# ----------------------------------------------------------------------------------------------
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--channel", choices=CHANNELS, help="channel to publish (stable, beta, dev)")
    ap.add_argument("--changelog", help="plain text file shown on devices (no emojis, no em dashes)")
    ap.add_argument("--build", help="HioneOS build these apps belong to (default: HIONEOS_VERSION in build_hioneos_super.sh)")
    ap.add_argument("--min-build", help="oldest HioneOS build allowed to take these app updates (default: --build)")
    ap.add_argument("--requires-build", action="append", default=[], metavar="PKG=BUILD",
                    help="per-APK minimum build, repeatable")
    ap.add_argument("--system-update-build", help="announce a full image of this build")
    ap.add_argument("--system-update-notes", help="text file for the system update card")
    ap.add_argument("--system-update-required", action="store_true", help="mark the image as needed, not just available")
    ap.add_argument("--installer-url", default=INSTALLER_URL)
    ap.add_argument("--key", choices=("primary", "backup"), default="primary", help="vault key to sign with")
    ap.add_argument("--out", default=str(HERE / "out"), help="staging dir, mirrors the server (default hioneos/ota/out)")
    ap.add_argument("--apk", action="append", default=[], help="extra APK path, repeatable")
    ap.add_argument("--no-scan", action="store_true", help="only use --apk files, do not scan build outputs")
    ap.add_argument("--only", action="append", default=[], metavar="PKG", help="publish only these packages")
    ap.add_argument("--exclude", action="append", default=[], metavar="PKG", help="leave these packages out")
    ap.add_argument("--expect-signer", help="SHA-256 of the platform certificate every APK must carry")
    ap.add_argument("--url-base", help="write absolute APK URLs under this base (default: relative ../apks/..., which works on any host)")
    ap.add_argument("--revoke", action="append", default=[], metavar="PKG=VC[:reason]",
                    help="pull a published release: devices running it go back to their image copy")
    ap.add_argument("--drop-revocations", action="store_true", help="do not carry revocations over from the staged manifest")
    ap.add_argument("--allow-style", action="store_true", help="skip the no-emoji/no-em-dash check")
    ap.add_argument("--dry-run", action="store_true", help="sign with a throwaway key; never touches the vault or the server")
    ap.add_argument("--upload", action="store_true", help=f"push to {DEPLOY_HOST}:{DEPLOY_PATH} and verify the live copy")
    ap.add_argument("--skip-site-check", action="store_true", help="upload even if the site publish.sh would delete /ota")
    ap.add_argument("--verify-live", metavar="CHANNEL", help="only check the live manifest, signature and APK ranges, then exit")
    args = ap.parse_args()

    if args.verify_live:
        verify_live(args.verify_live)
        return
    if not args.channel or not args.changelog:
        ap.error("--channel and --changelog are required")
    if args.dry_run and args.upload:
        ap.error("--dry-run and --upload do not mix")
    for t in (AAPT2, APKSIGNER):
        if not t.exists():
            die(f"{t} not found (set HIONE_BUILD_TOOLS)")
    for p in PUBKEYS.values():
        if not p.exists():
            die(f"public key {p} missing")
    args.build = args.build or default_build()
    args.min_build = args.min_build or args.build
    rb = {}
    for item in args.requires_build:
        if "=" not in item:
            die(f"--requires-build wants PKG=BUILD, got {item}")
        k, v = item.split("=", 1)
        rb[k] = v
    args.requires_build = rb

    out = Path(args.out).resolve()
    (out / "ota").mkdir(parents=True, exist_ok=True)
    marker = out / "DRY_RUN"

    print(f"HioneOS OTA publish: channel {args.channel}, build {args.build}, min_build {args.min_build}")
    apks = collect(args)
    signer = stage_apks(apks, out, args.expect_signer)
    print(f"  signer certificate {signer}")
    for a in apks:
        print(f"  add   {a['package']:<22} {a['versionName']:<16} vc {a['versionCode']:<6} {a['size']:>10} bytes")

    chan_dir = out / "ota" / args.channel
    chan_dir.mkdir(parents=True, exist_ok=True)
    revoked = collect_revocations(args, chan_dir)
    clash = {(r["package"], r["versionCode"]) for r in revoked} & {(a["package"], a["versionCode"]) for a in apks}
    if clash:
        die("these are both offered and revoked: " + ", ".join(f"{p} {v}" for p, v in sorted(clash))
            + ". Leave them out with --exclude, or publish a higher versionCode.")
    m = build_manifest(args, apks, revoked)
    manifest = chan_dir / "manifest.json"
    manifest.write_bytes(json.dumps(m, indent=2, ensure_ascii=False).encode("utf-8") + b"\n")
    sig_path = chan_dir / "manifest.json.sig"
    dry_sig_path = chan_dir / "manifest.json.sig.DRYRUN"

    if args.dry_run:
        with tempfile.TemporaryDirectory() as td:
            der, pub = sign_with_throwaway(manifest, Path(td))
            if not openssl_verify(manifest, der, pub):
                die("dry run: the throwaway signature did not verify")
            if which_key(manifest, der):
                die("dry run: a real public key accepted a throwaway signature; something is very wrong")
            tampered = Path(td) / "tampered.json"
            tampered.write_bytes(manifest.read_bytes().replace(b'"schema_version"', b'"schema_versioN"', 1))
            if openssl_verify(tampered, der, pub):
                die("dry run: a tampered manifest verified")
        dry_sig_path.write_bytes(sig_text(der))
        if sig_path.exists():
            sig_path.unlink()  # a stale real signature must not sit next to a changed manifest
        marker.write_text("staged by --dry-run: signed with a throwaway key, devices will reject it\n")
        print("dry run ok: signature round-trips, real keys reject it, tampering is caught")
        print(f"  staged in {out}/ota (signature in {dry_sig_path.name}; NOT uploadable)")
        return

    der = sign_with_vault(manifest, args.key)
    key = which_key(manifest, der)
    if key != args.key:
        manifest.unlink()
        die(f"the new signature verifies with {key or 'neither public key'}, expected {args.key}. "
            "Is the vault entry the private half of keys/ota-signing-" + args.key + "-p521.pub.pem?")
    sig_path.write_bytes(sig_text(der))
    if dry_sig_path.exists():
        dry_sig_path.unlink()
    if marker.exists():
        marker.unlink()
    print(f"signed with the {key} key and verified: {sig_path}")

    if args.upload:
        upload(args, out, args.channel, apks)
        print(f"published: {PUBLIC_OTA}/{args.channel}/manifest.json")
    else:
        print(f"staged in {out}/ota. Test it on the LAN with serve_local.py, or re-run with --upload.")


if __name__ == "__main__":
    main()
