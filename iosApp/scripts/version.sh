#!/usr/bin/env bash
# Prints the app's version as shell assignments, for `eval "$(iosApp/scripts/version.sh)"`.
#
# The version has one source in this repo: `app/build.gradle.kts`, where `DSH_VERSION_NAME` (the
# release tag without its leading `v`) wins and the literal after `?:` is the fallback a local build
# gets. This reads the same two things, and derives the build number the way that file derives
# `versionCode` (major * 10000 + minor * 100 + patch), so the Android and iOS apps of one release
# carry the same numbers.
#
#   DSH_VERSION_NAME       the full version, e.g. 0.19.2 or 0.20.0-rc1 (used in file names)
#   DSH_MARKETING_VERSION  the dotted-number part, e.g. 0.20.0 (CFBundleShortVersionString allows no suffix)
#   DSH_BUILD_NUMBER       e.g. 2000 (CFBundleVersion)
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
name="${DSH_VERSION_NAME:-}"
if [ -z "$name" ]; then
  name="$(sed -nE 's/.*getenv\("DSH_VERSION_NAME"\).*\?: "([^"]+)".*/\1/p' "$root/app/build.gradle.kts" | head -n 1)"
fi
if [ -z "$name" ]; then
  echo "version.sh: could not find the version in app/build.gradle.kts" >&2
  exit 1
fi

marketing="${name%%-*}"
IFS=. read -r major minor patch <<<"$marketing"
build=$(( ${major:-0} * 10000 + ${minor:-0} * 100 + ${patch:-0} ))
if [ "$build" -lt 1 ]; then build=1; fi

echo "export DSH_VERSION_NAME='$name'"
echo "export DSH_MARKETING_VERSION='$marketing'"
echo "export DSH_BUILD_NUMBER='$build'"
