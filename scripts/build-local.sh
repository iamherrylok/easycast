#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -d .tools/jdk17/Contents/Home ]]; then export JAVA_HOME="$PWD/.tools/jdk17/Contents/Home"; fi
if [[ -d .tools/android-sdk ]]; then export ANDROID_HOME="$PWD/.tools/android-sdk"; fi
export ANDROID_USER_HOME="$PWD/.tools/android-user"
export GRADLE_USER_HOME="$PWD/.tools/gradle-user"
if [[ -x .tools/gradle-8.9/bin/gradle ]]; then
  gradle_cmd=.tools/gradle-8.9/bin/gradle
else
  gradle_cmd=./gradlew
fi
"$gradle_cmd" --no-daemon "$@" :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
mkdir -p dist
cp app/build/outputs/apk/release/app-release.apk dist/easycast-1.0.0.apk
(cd dist && shasum -a 256 easycast-1.0.0.apk > SHA256SUMS)
