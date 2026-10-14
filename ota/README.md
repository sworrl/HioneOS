# HioneOS over-the-air updates

Two tiers, one device app (Hione Update, `com.hione.update`, module `hione-player-kotlin/hioneos-update`)
and a static backend at `https://hioneos.falcontechnix.com/ota/`.

| Tier | What | How it gets on the device |
|---|---|---|
| App updates | Newer builds of HioneOS's own system apps: `com.hione.*` and `com.m500.hardware` | Hione Update installs them silently as `/data` updates over the image copies |
| System updates | Everything else: the FM app, driver patches, overlays, build props, debloat, new partitions, new apps | A full image through the web installer (`https://hioneos.falcontechnix.com/install/`) on a PC. Hione Update shows a card with the link and a QR code |

**The FM app (`com.caf.fmradio`) is never an app update.** A `/data` copy loses the shared linker
namespace its JNI needs and the tuner stops working. The device app refuses it whatever a manifest
says, and `publish.py` refuses to list it.

A future on-device A/B path (`android.os.UpdateEngine` with a `payload.bin`) has a documented seam in
`hioneos-update/.../ota/SystemUpdatePath.kt` (`AbUpdateEnginePath`). It is not implemented.

```
hioneos/ota/
  keys/ota-signing-primary-p521.pub.pem   public keys, embedded in the app at build time
  keys/ota-signing-backup-p521.pub.pem
  publish.py                              collect APKs, write + sign + verify manifest, stage, upload
  serve_local.py                          LAN test server with Range support
  out/                                    staging tree, mirrors the server (created by publish.py)
```

## Server layout

```
/ota/
  stable/manifest.json          signed list for the channel
  stable/manifest.json.sig      signature over the exact bytes of manifest.json
  beta/...
  dev/...
  apks/<package>/<versionCode>/<package>-<versionCode>.apk     immutable, never overwritten
```

Deployed to `root@10.10.10.5:/var/www/html/falcontechnix/hioneos/ota/` (rieska, behind Cloudflare),
the same host and webroot that `~/Documents/GitHub/webdev/hioneos-site/publish.sh` uses for the site
and the web installer. Files are chowned `1000:1000` like the site.

**Before the first upload:** that site `publish.sh` runs `rsync --delete` over the whole
`hioneos/` webroot, so it would delete `/ota/` the next time the site is published. Add
`--exclude /ota` to its rsync line. `publish.py --upload` refuses to run until it is there.

## manifest.json (schema_version 1)

```json
{
  "schema_version": 1,
  "channel": "stable",
  "build": "0.2.0",
  "min_build": "0.1.16",
  "published_at": "2026-10-10T12:00:00Z",
  "changelog": "Plain text. Short. No emojis, no em dashes.",
  "system_update": {
    "required": false,
    "build": "0.2.0",
    "notes": "What the full image brings.",
    "installer_url": "https://hioneos.falcontechnix.com/install/"
  },
  "apks": [
    {
      "package": "com.hione.player",
      "versionCode": 2315,
      "versionName": "2.0.315",
      "url": "../apks/com.hione.player/2315/com.hione.player-2315.apk",
      "size": 114908795,
      "sha256": "64 lowercase hex",
      "min_sdk": 26,
      "requires_build": null
    }
  ],
  "revoked": [
    { "package": "com.hione.settings", "versionCode": 12, "reason": "Broke Wi-Fi settings" }
  ]
}
```

| Field | Meaning |
|---|---|
| `schema_version` | The app refuses any number above the one it knows (1). |
| `channel` | Must equal the channel the device asked for, or the manifest is ignored (stops a channel swap). |
| `build` | The HioneOS build these APKs were built with (`HIONEOS_VERSION`). Informational. |
| `min_build` | Oldest HioneOS build allowed to take these app updates. A device below it installs none of them and shows "system update needed". |
| `published_at` | UTC, ISO 8601. The device remembers the newest one it accepted per channel and refuses an older manifest (no replay or freeze back to old builds). |
| `changelog` | Shown on the device as is. `publish.py` refuses emojis and em dashes. |
| `system_update` | Optional. Shown when `build` is newer than the device's `ro.hioneos.version`. `required` changes the wording to "needed". The link carries `?build=<build>`. |
| `apks[].url` | Relative to the manifest URL (default), or absolute with `--url-base`. Relative works unchanged on the real host and a LAN test server. |
| `apks[].size`, `sha256` | Checked after download, before anything else. |
| `apks[].min_sdk` | From `aapt2 dump badging`. |
| `apks[].requires_build` | Optional per-APK minimum build (`--requires-build PKG=BUILD`). |
| `revoked` | Optional. Pulled releases, see "Rolling back a release". |

Build versions compare as dot-separated numbers ("0.1.16" < "0.2.0"); anything after `-` is ignored.

## Signatures

* ECDSA on P-521 (secp521r1), SHA-512 (`SHA512withECDSA`), over the exact bytes of `manifest.json`
  as served. Never re-serialize a manifest after signing.
* `manifest.json.sig` is **base64 text** of the DER-encoded signature that
  `openssl dgst -sha512 -sign` produces, one line plus a newline. Whitespace is ignored.
* The device accepts a signature from either embedded key (primary or backup). It shows which one
  signed in About.
* Check one by hand:
  ```bash
  base64 -d manifest.json.sig > /tmp/sig.der
  openssl dgst -sha512 -verify hioneos/ota/keys/ota-signing-primary-p521.pub.pem -signature /tmp/sig.der manifest.json
  ```

## What the device checks, in order

1. Fetch `manifest.json` and `.sig`. Verify the signature with an embedded key. Only then parse.
   (A mismatch is retried once after 3 s: an upload swaps the two files one after the other.)
2. `schema_version` known, `channel` matches, `published_at` not older than the last accepted.
3. Per APK: package on the allowlist (`com.hione.*`, `com.m500.hardware`, never `com.caf.fmradio`),
   installed **as a system app in this image** (Hione Update never adds apps), `versionCode` newer
   than installed, `requires_build` / `min_build` / `min_sdk` satisfied, not revoked, not held.
4. Download to the app cache with resume (`.part` file + `Range: bytes=N-`, `Content-Range`
   checked), size capped at the manifest size.
5. Size and SHA-256 equal the manifest's. Otherwise the file is deleted.
6. `PackageManager.getPackageArchiveInfo`: package name and versionCode equal the manifest's, and
   the set of signing certificate SHA-256 digests equals the installed app's. Anything else is
   refused before a session is opened.
7. The current `/data` copy (if any) is kept for rollback, then a PackageInstaller session commits.

Silent install: the app is `sharedUserId="android.uid.system"` and platform-signed (same pattern as
`hione-sysbridge`), so it runs in `system_app` (network allowed) and PackageInstaller lets the system
UID commit without user action. If `STATUS_PENDING_USER_ACTION` ever comes back anyway, the
confirmation screen opens if Hione Update is on screen, otherwise a notification asks for a tap.
Hione Update installs itself last, because that ends its own process.

## Rollback on the device

Android does not downgrade a `/data` update on a user build (`ro.debuggable=0` here), so:

* **Back to image version**: uninstall the `/data` update (`PackageInstaller.uninstall`, flags 0,
  which removes the update and restores the system copy). Always available for an updated app.
* **Back to the previous version** (when the app was already an update before the last one):
  Hione Update kept that APK in `files/rollback/<pkg>/`. It re-verifies it (stored SHA-256 + signer),
  uninstalls the update, then installs the kept APK on top of the image copy, which is an upgrade
  as far as Android is concerned.

Either way the version walked away from is **held**: automatic installs skip it and anything older,
and the next higher release clears the hold. "Install anyway" overrides it. App data is kept; an
older version may not read everything a newer one wrote.

## Rolling back a release (publisher side)

Publishing a manifest that lists an older versionCode does **nothing** for devices that already
took the newer one: they will not downgrade. The real options:

1. **Roll forward (preferred).** Revert the code, bump `versionCode` above the bad one, rebuild,
   publish. Every device updates normally.
2. **Revoke.** `publish.py --revoke com.hione.settings=12:"Broke Wi-Fi settings"`. Devices running
   exactly that versionCode as a `/data` update uninstall the update on their next check (back to
   the image copy), hold that version, and post a notification. Revocations carry over to later
   manifests of the channel from the staged copy until `--drop-revocations`. Combine with 1 when the
   fix is ready.
3. **Stop offering it.** Publish without it (`--exclude PKG`). Devices that already have it keep it.
4. **Tell users** to open Hione Update > Installed HioneOS apps > Back to image version.

## Keys

The private keys exist **only** in the vault:

```
~/.local/bin/ftvault get ft-hioneos-ota ota-signing-primary-p521.pem
~/.local/bin/ftvault get ft-hioneos-ota ota-signing-backup-p521.pem
```

`publish.py` signs with

```bash
openssl dgst -sha512 -sign <("$FTVAULT" get ft-hioneos-ota ota-signing-primary-p521.pem) -binary manifest.json
```

run through `bash -c`. Process substitution hands openssl a pipe (`/dev/fd/N`): the key is never in a
file, an argument, an environment variable, or Python's memory, and nothing echoes it. Then the
signature is verified with `keys/*.pub.pem` and the publish stops if it does not verify with the
key that was asked for. `--dry-run` never calls ftvault; it signs with a throwaway P-521 key made
in a temp dir and deleted, checks the round trip, checks that the real keys reject it and that a
tampered manifest fails, and writes `manifest.json.sig.DRYRUN` plus an `out/DRY_RUN` marker so the
result cannot be uploaded.

The app gets the public keys from `hioneos/ota/keys/` at build time (Gradle task `copyOtaKeys`,
exact file names only, and it refuses a file that is not a public-key PEM).

### Rotation

Normal state: sign with primary, backup unused but trusted by every device.

* **Planned rotation, or primary lost or leaked:**
  1. Sign with the backup now: `publish.py ... --key backup`. Devices already accept it.
  2. Make a new key straight into the vault, never on disk:
     ```bash
     openssl ecparam -name secp521r1 -genkey -noout | ftvault set ft-hioneos-ota ota-signing-next-p521.pem
     openssl ec -pubout -in <(ftvault get ft-hioneos-ota ota-signing-next-p521.pem) > /tmp/next.pub.pem
     ```
  3. In `hioneos/ota/keys/`: the old backup's public key becomes `ota-signing-primary-p521.pub.pem`,
     the new one becomes `ota-signing-backup-p521.pub.pem`. In the vault, move the entries the same
     way, pipe to pipe:
     ```bash
     ftvault get ft-hioneos-ota ota-signing-backup-p521.pem | ftvault set ft-hioneos-ota ota-signing-primary-p521.pem
     ftvault get ft-hioneos-ota ota-signing-next-p521.pem   | ftvault set ft-hioneos-ota ota-signing-backup-p521.pem
     ```
     `publish.py` refuses to publish if a vault entry does not match its public key file, so a
     half-done swap cannot sign anything. Rebuild Hione Update with a higher
     versionCode and publish it, signed with the (old) backup key, which is now the primary.
  4. Once devices run the new Hione Update, the old primary is no longer trusted anywhere that
     updated. Devices that never update keep trusting it, so after a leak also consider revoking
     anything signed in the gap.
* **Both keys lost:** devices cannot be told anything new over the air. Ship new keys in a full
  image (rebuild Hione Update, reflash with the web installer).

## Publishing

Build the APKs first (versionCodes bumped for whatever changed), then:

```bash
cd ~/Documents/GitHub/m500
$EDITOR /tmp/notes.txt                       # plain text, no emojis, no em dashes

# 1. dry run: shows what goes in, signs with a throwaway key, touches no vault and no server
hioneos/ota/publish.py --channel dev --changelog /tmp/notes.txt --dry-run

# 2. real signature, staged in hioneos/ota/out (test it on the LAN, see below)
hioneos/ota/publish.py --channel dev --changelog /tmp/notes.txt

# 3. real signature and upload
hioneos/ota/publish.py --channel stable --changelog /tmp/notes.txt --upload
```

What it does: scans `hione-player-kotlin/*/build/outputs/apk/release/*.apk`, reads each with
`aapt2 dump badging`, keeps HioneOS packages only (skips the FM app and anything unsigned), takes the
highest versionCode per package, requires every APK to have one signer and all of them the same
certificate (`--expect-signer SHA256` pins it), copies them into `out/ota/apks/...` (refuses if
that versionCode is already staged with different bytes), writes the manifest, signs, verifies.

Useful options:

| Option | |
|---|---|
| `--build`, `--min-build` | default: `HIONEOS_VERSION` from `build_hioneos_super.sh`, and `min_build` = `build`. Lower `--min-build` only when the APKs really run on older images. |
| `--only PKG`, `--exclude PKG`, `--apk PATH`, `--no-scan` | choose what goes in |
| `--requires-build PKG=BUILD` | per-APK minimum build |
| `--system-update-build 0.2.0 [--system-update-required] [--system-update-notes FILE]` | announce a full image |
| `--revoke PKG=VC[:reason]`, `--drop-revocations` | pull a release |
| `--key backup` | sign with the backup key |
| `--url-base https://...` | absolute APK URLs instead of relative |
| `--verify-live CHANNEL` | fetch the live manifest, check its signature, range-probe every APK |

`--upload` then: checks ssh to `root@10.10.10.5`; checks that no APK path already on the server
holds different bytes; rsyncs APKs with `--ignore-existing` (never overwritten, never deleted);
uploads `manifest.json` + `.sig` into `<channel>/.incoming/` and moves both into place in one ssh
command; chowns; purges the two manifest URLs from Cloudflare with the token in
`~/.config/cloudflare/.env` (`CF_API_TOKEN`, `CF_ZONE_ID_FALCONTECHNIX`, the same file the site
publish uses); then runs `--verify-live`. Nothing in `/ota/` is ever deleted by the script.

## Server requirements

Static files, HTTPS, and single byte-range requests (`206` + `Content-Range`) for resume. nginx and
Apache serve ranges for static files by default; Cloudflare passes them through and serves ranges
from cache.

Cache headers (manifest must never be stale, APKs never change):

nginx:
```nginx
location ^~ /ota/ {
    location ~ /manifest\.json(\.sig)?$ { add_header Cache-Control "no-cache" always; }
    location ~ \.apk$ {
        types { application/vnd.android.package-archive apk; }
        add_header Cache-Control "public, max-age=31536000, immutable" always;
    }
}
```

Apache (`.htaccess` in `/ota/`, needs `AllowOverride` and mod_headers):
```apache
<FilesMatch "manifest\.json(\.sig)?$">
  Header set Cache-Control "no-cache"
</FilesMatch>
<FilesMatch "\.apk$">
  ForceType application/vnd.android.package-archive
  Header set Cache-Control "public, max-age=31536000, immutable"
</FilesMatch>
```

Cloudflare: `.json` and `.sig` are not cached by default, which is what we want; a Cache Rule
"URI path starts with `/ota/` and ends with `manifest.json` or `manifest.json.sig`: bypass cache"
makes it explicit. APKs may be cached at the edge for as long as you like (their paths are
immutable). Do not turn on anything that rewrites bodies (Auto Minify, Rocket Loader, email
obfuscation) for `/ota/`: the signature covers the exact bytes.

## Testing on the LAN before the real host

```bash
hioneos/ota/publish.py --channel dev --changelog /tmp/notes.txt     # real signature, staged only
hioneos/ota/serve_local.py --throttle 300                            # prints http://<pc-ip>:8000/ota/
```

On the device: Hione Update > About > Test server, enter the printed URL, Use this server, pick the
dev channel. The manifest still has to carry a real signature. Changing the server resets the
"never go backwards" timestamps, since a different server has its own history. `--throttle` (KB/s)
makes progress visible and leaves time to kill Wi-Fi mid-download to test resume.

## Adding Hione Update to the image

`hioneos/build/build_hioneos_super.sh` must build `:hioneos-update` and inject
`HioneUpdate.apk` into `system/app/HioneUpdate/` (not priv-app: as the system UID with the platform
signature it already holds INSTALL_PACKAGES and DELETE_PACKAGES, and priv-app would only add a
privapp-permissions allowlist to keep in step with `ro.control_privapp_permissions=enforce`).
