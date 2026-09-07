#!/usr/bin/env bash
# Thin curl wrapper for poking a Seafile server during development.
#
# Two things it exists for:
#   1. --noproxy '*'. If an HTTP proxy is configured in the environment it may not be able
#      to reach the Seafile host, and the failure looks like a plain timeout. Every request
#      here bypasses the proxy.
#   2. Token handling. The account token is fetched once and cached, and per-library sync
#      tokens for /seafhttp/ are fetched on demand.
#
# Credentials come from the environment, or from a git-ignored scripts/.env.local:
#   SEAFILE_URL, SEAFILE_USER, SEAFILE_PASSWORD
#
# Usage:
#   scripts/dev-server.sh login                     print (and cache) the account token
#   scripts/dev-server.sh api <path> [curl args]    call /api2 or /api/v2.1 with the account token
#   scripts/dev-server.sh repos                     list libraries
#   scripts/dev-server.sh seafhttp <repo> <path> [curl args]
#                                                   call /seafhttp with that library's sync token
#   scripts/dev-server.sh raw <path> [curl args]    call with no auth at all
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[ -f "$here/.env.local" ] && . "$here/.env.local"

URL="${SEAFILE_URL:-}"
USER_EMAIL="${SEAFILE_USER:-}"
PASSWORD="${SEAFILE_PASSWORD:-}"
CACHE="${TMPDIR:-/tmp}/seafile-dev-token-$(printf '%s' "$URL$USER_EMAIL" | shasum | cut -c1-12)"

if [ -z "$URL" ]; then
  echo "SEAFILE_URL is not set (put it in scripts/.env.local or the environment)" >&2
  exit 2
fi

curl_() { curl -sS -m 120 --noproxy '*' "$@"; }

token() {
  if [ -s "$CACHE" ]; then cat "$CACHE"; return; fi
  if [ -z "$USER_EMAIL" ] || [ -z "$PASSWORD" ]; then
    echo "SEAFILE_USER / SEAFILE_PASSWORD are not set" >&2
    exit 2
  fi
  local t
  t=$(curl_ -X POST "$URL/api2/auth-token/" \
        --data-urlencode "username=$USER_EMAIL" \
        --data-urlencode "password=$PASSWORD" \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
  printf '%s' "$t" > "$CACHE"
  chmod 600 "$CACHE"
  printf '%s' "$t"
}

repo_token() {
  curl_ -H "Authorization: Token $(token)" "$URL/api2/repos/$1/download-info/" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])'
}

cmd="${1:-}"; shift || true
case "$cmd" in
  login)    token; echo ;;
  api)      p="$1"; shift; curl_ -H "Authorization: Token $(token)" "$URL$p" "$@" ;;
  repos)    curl_ -H "Authorization: Token $(token)" "$URL/api2/repos/" "$@" ;;
  seafhttp) r="$1"; p="$2"; shift 2
            rt=$(repo_token "$r")
            curl_ -H "Seafile-Repo-Token: $rt" -H "Authorization: Token $rt" "$URL/seafhttp$p" "$@" ;;
  raw)      p="$1"; shift; curl_ "$URL$p" "$@" ;;
  *)        sed -n '2,25p' "${BASH_SOURCE[0]}" | sed 's|^# \{0,1\}||'; exit 1 ;;
esac
