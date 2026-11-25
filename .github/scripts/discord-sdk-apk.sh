#!/usr/bin/env bash
set -euo pipefail

case "$1" in
  provision)
    if [[ "${DISCORD_SDK_AAR_URL:-}" != https://* ]]; then
      echo 'Set AMAIN2_DISCORD_SDK_AAR_URL to an authorized HTTPS download of the Android AAR.' >&2
      exit 1
    fi
    if [[ ! "${DISCORD_APPLICATION_ID:-}" =~ ^[1-9][0-9]{16,18}$ ]] ||
      [[ "${#DISCORD_APPLICATION_ID}" -eq 19 && "$DISCORD_APPLICATION_ID" > 9223372036854775807 ]]; then
      echo 'Set the MIYORARE_DISCORD_APPLICATION_ID repository variable to a valid Discord Application ID.' >&2
      exit 1
    fi

    aar="$RUNNER_TEMP/discord_partner_sdk.aar"
    umask 077
    if ! curl --silent --fail --location --proto '=https' --proto-redir '=https' \
      --connect-timeout 15 --max-time 120 --max-filesize 40000000 --output "$aar" -- "$DISCORD_SDK_AAR_URL"; then
      echo 'Could not download the Android Discord AAR. Check the URL and its expiry.' >&2
      exit 1
    fi
    # Discord Social SDK C++ 1.11.24259, Android AAR validated in Amain2.
    if ! printf '%s  %s\n' ea4c080cc7c5ddeccab58f3bdf5682a3e18a7a84a815bb0f33609bfcafba3f99 "$aar" \
      | sha256sum --check --status; then
      echo 'The Discord AAR does not match the pinned SHA-256.' >&2
      exit 1
    fi
    {
      printf 'ORG_GRADLE_PROJECT_discordSocialSdkAar=%s\n' "$aar"
      printf 'ORG_GRADLE_PROJECT_discordPresenceAppId=%s\n' "$DISCORD_APPLICATION_ID"
    } >> "$GITHUB_ENV"
    ;;

  verify)
    apk="$2"
    expected_package="$3"
    discord_mode="$4"
    build_tools="$ANDROID_HOME/build-tools/36.0.0"
    package_name=$("$build_tools/aapt2" dump packagename "$apk")
    if [[ "$package_name" != "$expected_package" ]]; then
      echo "::error::Expected APK package $expected_package, found $package_name."
      exit 1
    fi

    signer_report="$RUNNER_TEMP/miyorare-discord-apk-signers.txt"
    if ! "$build_tools/apksigner" verify --print-certs-pem "$apk" > "$signer_report"; then
      echo '::error::Android apksigner rejected the APK signature.'
      exit 1
    fi
    expected_signer=$(keytool -exportcert -keystore release.keystore -storepass:env RELEASE_STORE_PASSWORD \
      -alias "$RELEASE_KEY_ALIAS" | sha256sum | cut -d ' ' -f 1)
    python3 - "$apk" "$signer_report" "$expected_signer" "$discord_mode" <<'PY'
import base64
import hashlib
import re
import sys
from pathlib import Path
from zipfile import ZipFile

apk_path, signer_report, expected_signer, discord_mode = sys.argv[1:]
certificates = re.findall(
    rb"-----BEGIN CERTIFICATE-----\s+(.*?)-----END CERTIFICATE-----",
    Path(signer_report).read_bytes(),
    flags=re.DOTALL,
)
signers = {hashlib.sha256(base64.b64decode(cert)).hexdigest() for cert in certificates}
if signers != {expected_signer}:
    raise SystemExit("APK signer does not match the permanent Miyorare release certificate.")

abis = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
libraries = ("libdiscord_partner_sdk.so", "libmiyorare_discord_sdk.so")
with ZipFile(apk_path) as apk:
    entries = set(apk.namelist())
    if discord_mode == "sdk":
        for abi in abis:
            for library in libraries:
                entry = f"lib/{abi}/{library}"
                if entry not in entries:
                    raise SystemExit(f"Missing Discord native library: {entry}")
                with apk.open(entry) as binary:
                    if binary.read(4) != b"\x7fELF":
                        raise SystemExit(f"Invalid Discord native library: {entry}")
    elif discord_mode == "legacy":
        for entry in entries:
            if entry.startswith("lib/") and entry.rsplit("/", 1)[-1] in libraries:
                raise SystemExit(f"Unexpected Discord SDK library in the legacy build: {entry}")
    else:
        raise SystemExit(f"Unknown Discord APK mode: {discord_mode}")
PY
    echo "Verified APK package $expected_package, permanent signer, and Discord mode $discord_mode."
    ;;

  *)
    echo "Usage: $0 provision | verify APK PACKAGE_ID sdk|legacy" >&2
    exit 2
    ;;
esac
