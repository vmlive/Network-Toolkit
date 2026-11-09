#!/usr/bin/env bash
# Reproduce the Android command line build environment. Requires Java 17+,
# curl, Python 3 and unzip. Downloads retain TLS and archive verification.
set -euo pipefail
if [[ -d /workspace ]]; then
  toolkit_base=${NETWORK_TOOLKIT_TOOLS:-/workspace/.toolchains}
  toolkit_android_user=/workspace/.android
  toolkit_gradle_user=/workspace/.gradle
else
  toolkit_base=${NETWORK_TOOLKIT_TOOLS:-${XDG_CACHE_HOME:-$HOME/.cache}/network-toolkit}
  toolkit_android_user=$toolkit_base/android-user
  toolkit_gradle_user=$toolkit_base/gradle-user
fi
export ANDROID_HOME=${ANDROID_HOME:-$toolkit_base/android-sdk}
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-$toolkit_android_user}
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$toolkit_gradle_user}
mkdir -p "$toolkit_base/downloads" "$ANDROID_HOME/cmdline-tools" "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
if ! command -v javac >/dev/null || ! command -v jlink >/dev/null; then
  if [[ ! -x "$toolkit_base/jdk21/bin/javac" ]]; then
    jdk_url='https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz'
    curl --fail --location --retry 2 "$jdk_url.sha256.txt" -o "$toolkit_base/downloads/jdk-21.sha256"
    curl --fail --location --retry 2 "$jdk_url" -o "$toolkit_base/downloads/jdk-21.tar.gz"
    python3 - "$toolkit_base" <<'PY'
import hashlib, pathlib, sys, tarfile
base = pathlib.Path(sys.argv[1])
archive = base / 'downloads/jdk-21.tar.gz'
expected = (base / 'downloads/jdk-21.sha256').read_text().split()[0]
if hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit('Temurin JDK checksum mismatch')
with tarfile.open(archive) as t:
    root = t.getnames()[0].split('/')[0]
    t.extractall(base, filter='data')
(base / 'jdk21').symlink_to(root)
PY
  fi
  export JAVA_HOME=$toolkit_base/jdk21
  export PATH=$JAVA_HOME/bin:$PATH
fi
sdk_tools="$ANDROID_HOME/cmdline-tools/12.0"
if [[ ! -x "$sdk_tools/bin/sdkmanager" ]]; then
  curl --fail --location --retry 2 https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o "$toolkit_base/downloads/commandline-tools.zip"
  python3 - "$toolkit_base/downloads/commandline-tools.zip" "$ANDROID_HOME/cmdline-tools" <<'PY'
import hashlib, pathlib, sys, zipfile
archive = pathlib.Path(sys.argv[1])
# Google repository2-1.xml: cmdline-tools;12.0 / Linux.
expected = 'd313adb7aedccf6cf0cfca51ec180f0059f5f8f8'
if hashlib.sha1(archive.read_bytes()).hexdigest() != expected:
    raise SystemExit('Android command line tools checksum mismatch')
target = pathlib.Path(sys.argv[2])
with zipfile.ZipFile(archive) as z:
    z.extractall(target)
(target / 'cmdline-tools').rename(target / '12.0')
PY
  chmod +x "$sdk_tools/bin/"*
fi
proxy_args=()
if [[ -n ${HTTPS_PROXY:-${https_proxy:-}} ]]; then
  if [[ -r /etc/ssl/certs/java/cacerts ]]; then
    export SDKMANAGER_OPTS="${SDKMANAGER_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
  fi
  mapfile -t proxy_parts < <(python3 - <<'PY'
import os, urllib.parse
p = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy'))
if p.hostname:
    print(p.hostname)
    print(p.port or 80)
PY
  )
  proxy_args=(--proxy=http "--proxy_host=${proxy_parts[0]}" "--proxy_port=${proxy_parts[1]}")
fi
# SDK tools prompt for licenses even in automation. Check the sdkmanager
# status separately because yes may exit with SIGPIPE after the prompt closes.
set +e
yes | "$sdk_tools/bin/sdkmanager" "--sdk_root=$ANDROID_HOME" "${proxy_args[@]}" --licenses
sdk_status=${PIPESTATUS[1]}
set -e
[[ $sdk_status -eq 0 ]] || exit "$sdk_status"
"$sdk_tools/bin/sdkmanager" "--sdk_root=$ANDROID_HOME" "${proxy_args[@]}" 'platforms;android-35' 'build-tools;34.0.0' 'build-tools;35.0.0' 'platform-tools'
printf 'Android SDK ready: %s\nUse scripts/cloud-gradle.sh assembleDebug testDebugUnitTest lintDebug\n' "$ANDROID_HOME"
