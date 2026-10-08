#!/usr/bin/env bash
# Baut das signierte Google-ARCore-Bundle (.aab) für Google Play und eine Release-APK.
# Voraussetzungen: legal.properties ausgefüllt, keystore.properties vorhanden (siehe *.example).
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd "$project_dir/../.." && pwd)"
if [[ -d "$repository_dir/.tools/jdk21" ]]; then export JAVA_HOME="$repository_dir/.tools/jdk21"; fi
if [[ -d "$repository_dir/.tools/android-sdk" ]]; then export ANDROID_SDK_ROOT="$repository_dir/.tools/android-sdk"; fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repository_dir/.tools/gradle-home}"

cd "$project_dir"
python3 tools/build-privacy-page.py
./gradlew --no-daemon :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintRelease :app:bundleRelease :app:assembleRelease
python3 tools/verify-demo-packaging.py app/build/outputs/bundle/release/app-release.aab app/build/outputs/apk/release/app-release.apk
mkdir -p releases
version_name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -n 1)"
bundle="releases/qs-taller-xr-${version_name}-release.aab"
apk="releases/qs-taller-xr-${version_name}-release.apk"
cp app/build/outputs/bundle/release/app-release.aab "$bundle"
cp app/build/outputs/apk/release/app-release.apk "$apk"
cp app/build/outputs/mapping/release/mapping.txt "releases/qs-taller-xr-${version_name}-mapping.txt"
sha256sum "$bundle" | tee "$bundle.sha256"
sha256sum "$apk" | tee "$apk.sha256"
echo "Fertig: $bundle  → in der Play Console hochladen."
