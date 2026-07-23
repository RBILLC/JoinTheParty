#!/usr/bin/env bash
# Re-fetches the vendored Spotify SDK AARs (android/app/libs/) from the
# public GitHub release. The AARs are committed to the repo; this script is
# the provenance record + upgrade path (bump TAG, run, rebuild, commit).
set -euo pipefail

TAG="v0.8.0-appremote_v2.1.0-auth"
BASE="https://github.com/spotify/android-sdk/releases/download/${TAG}"
DEST="$(dirname "$0")/../android/app/libs"

mkdir -p "${DEST}"
curl -fL -o "${DEST}/spotify-app-remote-release-0.8.0.aar" \
  "${BASE}/spotify-app-remote-release-0.8.0.aar"
curl -fL -o "${DEST}/spotify-auth-release-2.1.0.aar" \
  "${BASE}/spotify-auth-release-2.1.0.aar"
echo "Fetched Spotify SDKs (${TAG}) into ${DEST}"
