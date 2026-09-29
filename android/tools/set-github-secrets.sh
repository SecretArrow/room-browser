#!/usr/bin/env bash
# Configures GitHub repository secrets for release signing.
# The keystore is NEVER committed — it is uploaded encrypted to GitHub secrets.
#
# Usage:
#   tools/set-github-secrets.sh <owner/repo> /path/to/release.jks \
#        [store_password] [key_alias] [key_password]
#
# Requires: gh CLI (authenticated) or a GITHUB_TOKEN with repo admin,
# plus jq + base64.
set -euo pipefail

REPO="${1:?owner/repo required}"
KEYSTORE="${2:?keystore path required}"
STORE_PASS="${3:-$(openssl rand -hex 16)}"
ALIAS="${4:-roombrowser}"
KEY_PASS="${5:-$STORE_PASS}"

if [ ! -f "$KEYSTORE" ]; then
  echo "Keystore not found: $KEYSTORE" >&2
  exit 1
fi

KEYSTORE_B64=$(base64 -w 0 "$KEYSTORE")

set_secret() {
  local name="$1" value="$2"
  if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    printf '%s' "$value" | gh secret set "$name" --repo "$REPO"
  else
    echo "gh CLI not authenticated — set secret $name manually" >&2
  fi
}

set_secret ROOMBROWSER_KEYSTORE_B64 "$KEYSTORE_B64"
set_secret ROOMBROWSER_STORE_PASSWORD "$STORE_PASS"
set_secret ROOMBROWSER_KEY_ALIAS "$ALIAS"
set_secret ROOMBROWSER_KEY_PASSWORD "$KEY_PASS"

echo "Secrets configured for $REPO."
echo "Keep a safe offline copy of the keystore and passwords!"
