#!/usr/bin/env bash
# Builds the unsigned device .ipa: build/ipa/DSH-Mobile-<version>-unsigned.ipa
#
# Needs macOS with Xcode, a JDK 17 and XcodeGen. It archives the Release build for a generic iOS device
# with code signing off, then packs Payload/DSH Mobile.app into a zip. Nothing in the result is signed,
# so it installs on no iPhone as it is: Sideloadly and AltStore re-sign it with the installer's own
# Apple ID (docs/IOS.md, "Installing on an iPhone"). It then checks what it built: arm64 only, no
# signature, and the repo's version in the Info.plist.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
eval "$(iosApp/scripts/version.sh)"
iosApp/generate.sh

mkdir -p build/ios-reports
xcodebuild archive -project iosApp/DSHMobile.xcodeproj -scheme DSHMobile -configuration Release \
  -destination 'generic/platform=iOS' -archivePath build/ios-archive/DSHMobile.xcarchive \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY="" \
  2>&1 | tee build/ios-reports/xcodebuild-archive.log

app="build/ios-archive/DSHMobile.xcarchive/Products/Applications/DSH Mobile.app"
test -d "$app" || { echo "::error::the archive has no DSH Mobile.app"; exit 1; }

rm -rf build/ipa
mkdir -p build/ipa/Payload
cp -R "$app" build/ipa/Payload/
ipa="DSH-Mobile-$DSH_VERSION_NAME-unsigned.ipa"
(cd build/ipa && zip -qry "$ipa" Payload)
ls -l "build/ipa/$ipa"

echo "--- what is in it"
unzip -l "build/ipa/$ipa" | tail -n 20
echo "--- architectures"
archs="$(lipo -archs "$app/DSH Mobile")"
echo "$archs"
echo "--- signature (expected: not signed)"
codesign -dv "$app" 2>&1 || true
echo "--- Info.plist"
plutil -p "$app/Info.plist" | grep -E 'CFBundle(Identifier|DisplayName|ShortVersionString|Version)|MinimumOSVersion|NSLocalNetwork|NSCamera|NSAllows|DTPlatformName|UIDeviceFamily|CADisable' || true

fail=0
[ "$archs" = "arm64" ] || { echo "::error::expected an arm64-only device build, got: $archs"; fail=1; }
[ ! -e "$app/_CodeSignature" ] || { echo "::error::the .ipa is signed; it was meant to be unsigned"; fail=1; }
short="$(plutil -extract CFBundleShortVersionString raw "$app/Info.plist")"
build="$(plutil -extract CFBundleVersion raw "$app/Info.plist")"
[ "$short" = "$DSH_MARKETING_VERSION" ] || { echo "::error::CFBundleShortVersionString is $short, expected $DSH_MARKETING_VERSION"; fail=1; }
[ "$build" = "$DSH_BUILD_NUMBER" ] || { echo "::error::CFBundleVersion is $build, expected $DSH_BUILD_NUMBER"; fail=1; }
[ "$(plutil -extract CFBundleIdentifier raw "$app/Info.plist")" = "com.labteto.dshmobile" ] || { echo "::error::wrong bundle identifier"; fail=1; }
[ "$(plutil -extract CADisableMinimumFrameDurationOnPhone raw "$app/Info.plist")" = "true" ] || { echo "::error::CADisableMinimumFrameDurationOnPhone is not set"; fail=1; }
[ -d "$app/compose-resources" ] || { echo "::error::the Compose resources are not in the app"; fail=1; }
# The Pair screen's QR scanner asks for the camera; without this string iOS ends the app the moment it does.
plutil -extract NSCameraUsageDescription raw "$app/Info.plist" > /dev/null 2>&1 || { echo "::error::NSCameraUsageDescription is not in the Info.plist"; fail=1; }
for lang in en fr ar; do
  # plutil reads a .strings file whether Xcode left it as text or converted it to a binary plist.
  plutil -p "$app/$lang.lproj/InfoPlist.strings" 2>/dev/null | grep -q NSCameraUsageDescription || { echo "::error::the $lang camera permission text is not in the app"; fail=1; }
done
exit "$fail"
