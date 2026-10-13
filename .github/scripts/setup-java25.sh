#!/usr/bin/env bash
set -euo pipefail
# Pin the exact official JDK used for the verified binary. setup-java's SemVer
# parser cannot express this four-component vendor release identifier.
archive="$RUNNER_TEMP/vaultbackup-jdk.tar.gz"
jdk="$RUNNER_TEMP/vaultbackup-jdk"
curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 \
  'https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz' \
  --output "$archive"
echo "dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e  $archive" | sha256sum --check
mkdir -p "$jdk"
tar -xzf "$archive" -C "$jdk" --strip-components=1
printf 'JAVA_HOME=%s\n' "$jdk" >> "$GITHUB_ENV"
printf '%s/bin\n' "$jdk" >> "$GITHUB_PATH"
"$jdk/bin/java" -version
