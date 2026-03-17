#!/usr/bin/env bash
# Claude Code Web — SessionStart hook for JOTP
#
# Installs Java 26 on first run (container state is cached after completion),
# sets JAVA_HOME + PATH for the session, and warms the Maven dependency cache.
# Only runs in Claude Code on the web (CLAUDE_CODE_REMOTE=true).

set -euo pipefail

# ── Skip in local CLI sessions ───────────────────────────────────────────────
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
    exit 0
fi

# ── Constants ────────────────────────────────────────────────────────────────
JDK26_DIR="/opt/jdk-26"
JDK26_URL="https://download.java.net/java/GA/jdk26/c3cc523845074aa0af4f5e1e1ed4151d/35/GPL/openjdk-26_linux-x64_bin.tar.gz"
PROJECT_DIR="${CLAUDE_PROJECT_DIR:-.}"

# ── 1. Install JDK 26 if not present ─────────────────────────────────────────
if [ ! -x "$JDK26_DIR/bin/java" ]; then
    echo "Installing Java 26 (first run — container state cached after this)..."
    TMP_TAR="$(mktemp /tmp/jdk26.XXXXXX.tar.gz)"
    curl -fsSL --retry 3 --retry-delay 2 "$JDK26_URL" -o "$TMP_TAR"
    mkdir -p "$JDK26_DIR"
    tar -xzf "$TMP_TAR" -C "$JDK26_DIR" --strip-components=1
    rm -f "$TMP_TAR"

    # Import proxy CA certs so Maven can reach Maven Central through
    # Anthropic's TLS-intercepting egress proxy.
    for CERT in /usr/local/share/ca-certificates/*.crt; do
        [ -f "$CERT" ] || continue
        ALIAS="$(basename "$CERT" .crt)"
        "$JDK26_DIR/bin/keytool" -importcert \
            -keystore "$JDK26_DIR/lib/security/cacerts" \
            -storepass changeit \
            -alias "$ALIAS" \
            -file "$CERT" \
            -noprompt \
            2>/dev/null || true
    done

    echo "Java 26 installed to $JDK26_DIR (proxy CA certs imported)"
fi

# ── 2. Persist JAVA_HOME + PATH for all session commands ─────────────────────
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
    echo "export JAVA_HOME=\"$JDK26_DIR\""       >> "$CLAUDE_ENV_FILE"
    echo "export PATH=\"$JDK26_DIR/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
    echo "export MAVEN_OPTS=\"-Xmx2g -Xms512m\"" >> "$CLAUDE_ENV_FILE"
fi

# Also set for commands run within this hook script
export JAVA_HOME="$JDK26_DIR"
export PATH="$JDK26_DIR/bin:$PATH"
export MAVEN_OPTS="-Xmx2g -Xms512m"

# ── 3. Verify Java 26 ─────────────────────────────────────────────────────────
JAVA_VER="$("$JDK26_DIR/bin/java" -version 2>&1 | grep 'openjdk version')"
echo "Java: ${JAVA_VER:-26 installed}"

# ── 4. Resolve build command ──────────────────────────────────────────────────
cd "$PROJECT_DIR"
if command -v mvnd &>/dev/null; then
    BUILD_CMD="mvnd"
    echo "Build: mvnd (Maven Daemon)"
elif [ -x "./mvnw" ]; then
    BUILD_CMD="./mvnw"
    echo "Build: ./mvnw (Maven Wrapper)"
else
    BUILD_CMD="mvn"
    echo "Build: mvn (system Maven)"
fi

# ── 5. Warm Maven dependency cache ───────────────────────────────────────────
# Compiles sources to pull down all dependencies. The || true ensures the hook
# never fails the session even if the cache warm is interrupted.
if [ ! -d "$PROJECT_DIR/target/classes" ]; then
    echo "Warming Maven cache — compiling sources (first session only)..."
    "$BUILD_CMD" compile -q --no-transfer-progress -T1C \
        -Dmaven.wagon.http.ssl.insecure=true \
        -Dmaven.wagon.http.ssl.allowall=true \
        -Dmaven.test.skip=true \
        2>/dev/null || true
    echo "Maven cache ready"
fi

# ── 6. Git context ────────────────────────────────────────────────────────────
BRANCH="$(git -C "$PROJECT_DIR" branch --show-current 2>/dev/null || echo "detached")"
DIRTY="$(git -C "$PROJECT_DIR" status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
LAST="$(git -C "$PROJECT_DIR" log -1 --format="%h %s" 2>/dev/null || echo "no commits")"

echo ""
echo "=== JOTP Web Session Ready ==="
echo "Branch : $BRANCH"
echo "Status : $([ "$DIRTY" -gt 0 ] && echo "$DIRTY file(s) modified" || echo "clean")"
echo "Head   : $LAST"
echo ""
echo "Java 26 + --enable-preview active (configured in pom.xml)"
echo "Quick commands:"
echo "  make compile     — compile sources"
echo "  make test        — unit tests  (T=ClassName for single)"
echo "  make verify      — tests + quality + guard checks"
echo "  make format      — Spotless (Google Java Format)"
echo "  make guard-check — scan H_TODO / H_MOCK / H_STUB violations"
