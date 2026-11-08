#!/usr/bin/env bash
# Generates `iosApp/DSHMobile.xcodeproj` from `project.yml`.
#
#   brew install xcodegen      # once
#   iosApp/generate.sh
#   open iosApp/DSHMobile.xcodeproj
#
# The project is a build product and is not checked in. Run this again after pulling a change to
# `project.yml`; a change to the Swift sources or the Kotlin does not need it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
eval "$("$here/scripts/version.sh")"
cd "$here"
xcodegen generate --spec project.yml
echo "Generated $here/DSHMobile.xcodeproj for version $DSH_VERSION_NAME (build $DSH_BUILD_NUMBER)"
