#!/usr/bin/env bash
# Uses the inherited cloud proxy without persisting proxy credentials.
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
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
mkdir -p "$ANDROID_USER_HOME" "$GRADLE_USER_HOME"
if [[ -x "$toolkit_base/jdk21/bin/javac" ]]; then
  export JAVA_HOME=$toolkit_base/jdk21
  export PATH=$JAVA_HOME/bin:$PATH
fi
proxy_args=()
if [[ -n ${HTTPS_PROXY:-${https_proxy:-}} ]]; then
  mapfile -t proxy_parts < <(python3 - <<'PY'
import os, urllib.parse
p = urllib.parse.urlsplit(os.environ.get('HTTPS_PROXY') or os.environ.get('https_proxy'))
if p.hostname:
    print(p.hostname)
    print(p.port or 80)
PY
  )
  proxy_args=("-Dhttps.proxyHost=${proxy_parts[0]}" "-Dhttps.proxyPort=${proxy_parts[1]}" "-Dhttp.proxyHost=${proxy_parts[0]}" "-Dhttp.proxyPort=${proxy_parts[1]}" '-Dhttp.nonProxyHosts=localhost|127.*|[::1]')
  if [[ -r /etc/ssl/certs/java/cacerts ]]; then
    proxy_args+=('-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts')
  fi
fi
cd "$repo_dir"
exec ./gradlew --no-daemon "${proxy_args[@]}" "$@"
