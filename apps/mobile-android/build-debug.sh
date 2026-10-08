#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repository_dir="$(cd "$project_dir/../.." && pwd)"

if [[ -d "$repository_dir/.tools/jdk21" ]]; then
  export JAVA_HOME="$repository_dir/.tools/jdk21"
fi
if [[ -d "$repository_dir/.tools/android-sdk" ]]; then
  export ANDROID_SDK_ROOT="$repository_dir/.tools/android-sdk"
fi
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$repository_dir/.tools/gradle-home}"

cd "$project_dir"
./gradlew --no-daemon :app:assembleDebug
mkdir -p releases
version_name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -n 1)"
if [[ -z "$version_name" ]]; then
  echo "No se pudo determinar versionName desde app/build.gradle.kts." >&2
  exit 1
fi
apk="releases/qs-taller-xr-${version_name}-debug.apk"
cp app/build/outputs/apk/debug/app-debug.apk "$apk"
sha256sum "$apk" | tee "$apk.sha256"
