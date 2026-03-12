#!/usr/bin/env bash
# .claude/setup.sh — Auto-install OpenJDK 26 + mvnd 2.0.0-rc-3 + Maven proxy
# Called by SessionStart hook so every Claude Code session has the right tools.
set -euo pipefail

JDK26_HOME="/usr/lib/jvm/openjdk-26"
JDK26_URL="https://download.java.net/java/GA/jdk26/c3cc523845074aa0af4f5e1e1ed4151d/35/GPL/openjdk-26_linux-x64_bin.tar.gz"

MVND_VERSION="2.0.0-rc-3"
MVND_HOME="/root/.mvnd/mvnd-${MVND_VERSION}"
MVND_URL="https://github.com/apache/maven-mvnd/releases/download/${MVND_VERSION}/maven-mvnd-${MVND_VERSION}-linux-amd64.tar.gz"
MVND_BIN="${MVND_HOME}/bin/mvnd"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# ── OpenJDK 26 ─────────────────────────────────────────────────────────────
if [ ! -x "${JDK26_HOME}/bin/java" ]; then
  echo "Installing OpenJDK 26..."
  TMP=$(mktemp -d)
  curl -fsSL "${JDK26_URL}" -o "${TMP}/jdk26.tar.gz"
  tar -xzf "${TMP}/jdk26.tar.gz" -C "${TMP}"
  EXTRACTED=$(ls -d "${TMP}"/jdk-26* 2>/dev/null | head -1)
  if [ -z "$EXTRACTED" ]; then
    echo "ERROR: Could not find extracted JDK 26 directory in ${TMP}" >&2
    ls "${TMP}" >&2
    rm -rf "${TMP}"
    exit 1
  fi
  mkdir -p /usr/lib/jvm
  mv "${EXTRACTED}" "${JDK26_HOME}"
  rm -rf "${TMP}"
  echo "OpenJDK 26 installed at ${JDK26_HOME}"
else
  echo "OpenJDK 26 already at ${JDK26_HOME}"
fi

export JAVA_HOME="${JDK26_HOME}"
export PATH="${JDK26_HOME}/bin:${PATH}"

# ── mvnd 2.0.0-rc-3 ───────────────────────────────────────────────────────
if [ ! -x "${MVND_BIN}" ]; then
  echo "Installing mvnd ${MVND_VERSION}..."
  TMP=$(mktemp -d)
  curl -fsSL "${MVND_URL}" -o "${TMP}/mvnd.tar.gz"
  tar -xzf "${TMP}/mvnd.tar.gz" -C "${TMP}"
  EXTRACTED=$(ls -d "${TMP}"/maven-mvnd-* 2>/dev/null | head -1)
  if [ -z "$EXTRACTED" ]; then
    echo "ERROR: Could not find extracted mvnd directory in ${TMP}" >&2
    rm -rf "${TMP}"
    exit 1
  fi
  mkdir -p "$(dirname "${MVND_HOME}")"
  mv "${EXTRACTED}" "${MVND_HOME}"
  rm -rf "${TMP}"
  echo "mvnd ${MVND_VERSION} installed at ${MVND_HOME}"
else
  echo "mvnd ${MVND_VERSION} already at ${MVND_BIN}"
fi

# ── Symlink mvnd to /usr/local/bin ────────────────────────────────────────
if [ ! -L /usr/local/bin/mvnd ] || [ "$(readlink /usr/local/bin/mvnd)" != "${MVND_BIN}" ]; then
  ln -sf "${MVND_BIN}" /usr/local/bin/mvnd
  echo "/usr/local/bin/mvnd -> ${MVND_BIN}"
fi

# ── /opt/mvnd2/bin symlink (YAWL toolchain expects mvnd here) ────────────
if [ ! -L /opt/mvnd2/bin/mvnd ] || [ "$(readlink /opt/mvnd2/bin/mvnd)" != "${MVND_BIN}" ]; then
  mkdir -p /opt/mvnd2/bin
  ln -sf "${MVND_BIN}" /opt/mvnd2/bin/mvnd
  echo "/opt/mvnd2/bin/mvnd -> ${MVND_BIN}"
fi

# ── Pre-build dx-guard (PostToolUse hook needs it; cargo not in hook PATH at edit time) ──
GUARD_BIN="${REPO_ROOT}/guard-system/target/release/dx-guard"
if [ ! -x "${GUARD_BIN}" ]; then
  echo "Building guard system (dx-guard)..."
  export PATH="${HOME}/.cargo/bin:${PATH}"
  (cd "${REPO_ROOT}/guard-system" && cargo build --release -q) && \
    echo "dx-guard built: ${GUARD_BIN}" || \
    echo "[WARN] guard-system build failed — guards will grep-fallback"
fi

# ── /opt/jdk symlink (mvnd native binary has /opt/jdk hardcoded as fallback)
if [ ! -L /opt/jdk ] || [ "$(readlink /opt/jdk)" != "${JDK26_HOME}" ]; then
  mkdir -p /opt
  ln -sf "${JDK26_HOME}" /opt/jdk
  echo "/opt/jdk -> ${JDK26_HOME}"
fi

# ── ~/.m2/mvnd.properties — point mvnd daemon at JDK 26 ───────────────────
MVND_PROPS="${HOME}/.m2/mvnd.properties"
if [ ! -f "${MVND_PROPS}" ] || ! grep -q "^java.home=" "${MVND_PROPS}" 2>/dev/null; then
  mkdir -p "${HOME}/.m2"
  echo "java.home=${JDK26_HOME}" > "${MVND_PROPS}"
  echo "Wrote ${MVND_PROPS}: java.home=${JDK26_HOME}"
fi

# ── Maven Proxy Setup ─────────────────────────────────────────────────────
# Auto-configure ~/.m2/settings.xml from JAVA_TOOL_OPTIONS proxy settings
# or from https_proxy/http_proxy environment variables.
setup_maven_proxy() {
  local SETTINGS_FILE="${HOME}/.m2/settings.xml"

  # If settings.xml already has proxy config, skip
  if [ -f "${SETTINGS_FILE}" ] && grep -q '<proxy>' "${SETTINGS_FILE}" 2>/dev/null; then
    echo "Maven proxy config already in ${SETTINGS_FILE}"
    return 0
  fi

  # Extract proxy details from JAVA_TOOL_OPTIONS if available
  local PROXY_HOST="" PROXY_PORT="" PROXY_USER="" PROXY_PASS=""
  local JTO="${JAVA_TOOL_OPTIONS:-}"

  if [ -n "$JTO" ]; then
    PROXY_HOST=$(echo "$JTO" | grep -oP '(?<=-Dhttps\.proxyHost=)\S+' | head -1 || true)
    PROXY_PORT=$(echo "$JTO" | grep -oP '(?<=-Dhttps\.proxyPort=)\S+' | head -1 || true)
    PROXY_USER=$(echo "$JTO" | grep -oP '(?<=-Dhttps\.proxyUser=)\S+' | head -1 || true)
    PROXY_PASS=$(echo "$JTO" | grep -oP '(?<=-Dhttps\.proxyPassword=)\S+' | head -1 || true)
  fi

  # Fallback: parse from https_proxy/http_proxy URL
  if [ -z "$PROXY_HOST" ]; then
    local UPSTREAM="${https_proxy:-${HTTPS_PROXY:-${http_proxy:-${HTTP_PROXY:-}}}}"
    if [ -n "$UPSTREAM" ]; then
      PROXY_HOST=$(python3 -c "from urllib.parse import urlparse; p=urlparse('${UPSTREAM}'); print(p.hostname or '')" 2>/dev/null || true)
      PROXY_PORT=$(python3 -c "from urllib.parse import urlparse; p=urlparse('${UPSTREAM}'); print(p.port or 3128)" 2>/dev/null || true)
      PROXY_USER=$(python3 -c "from urllib.parse import urlparse; p=urlparse('${UPSTREAM}'); print(p.username or '')" 2>/dev/null || true)
      PROXY_PASS=$(python3 -c "from urllib.parse import urlparse; p=urlparse('${UPSTREAM}'); print(p.password or '')" 2>/dev/null || true)
    fi
  fi

  if [ -z "$PROXY_HOST" ]; then
    echo "No proxy detected — skipping Maven proxy config"
    return 0
  fi

  PROXY_PORT="${PROXY_PORT:-3128}"

  echo "Configuring Maven proxy -> ${PROXY_HOST}:${PROXY_PORT}"
  mkdir -p "${HOME}/.m2"

  # Build password elements only if credentials exist
  local USER_ELEM="" PASS_ELEM=""
  if [ -n "$PROXY_USER" ]; then
    USER_ELEM="<username>${PROXY_USER}</username>"
  fi
  if [ -n "$PROXY_PASS" ]; then
    PASS_ELEM="<password>${PROXY_PASS}</password>"
  fi

  cat > "${SETTINGS_FILE}" <<XMLEOF
<settings>
  <proxies>
    <proxy><id>egress-https</id><active>true</active><protocol>https</protocol>
      <host>${PROXY_HOST}</host><port>${PROXY_PORT}</port>
      ${USER_ELEM}
      ${PASS_ELEM}
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy>
    <proxy><id>egress-http</id><active>true</active><protocol>http</protocol>
      <host>${PROXY_HOST}</host><port>${PROXY_PORT}</port>
      ${USER_ELEM}
      ${PASS_ELEM}
      <nonProxyHosts>localhost|127.0.0.1</nonProxyHosts></proxy>
  </proxies>
</settings>
XMLEOF
  echo "Maven settings.xml written with proxy config"
}

setup_maven_proxy

# ── Start maven-proxy-v2.py bridge (optional, for https_proxy env) ─────────
if [ -n "${https_proxy:-}${HTTPS_PROXY:-}${http_proxy:-}${HTTP_PROXY:-}" ]; then
  PROXY_SCRIPT="${REPO_ROOT}/maven-proxy-v2.py"
  if [ -f "${PROXY_SCRIPT}" ] && ! pgrep -f "python3.*maven-proxy" >/dev/null 2>&1; then
    echo "Starting Maven proxy bridge (127.0.0.1:3128)..."
    nohup python3 "${PROXY_SCRIPT}" >/dev/null 2>&1 &
    sleep 1
    if pgrep -f "python3.*maven-proxy" >/dev/null 2>&1; then
      echo "Maven proxy bridge started"
    else
      echo "Maven proxy bridge failed to start (continuing anyway)"
    fi
  fi
fi

echo "Setup complete: JDK $(java -version 2>&1 | head -1 | grep -oP '\"[^\"]+\"') | mvnd at ${MVND_BIN}"
