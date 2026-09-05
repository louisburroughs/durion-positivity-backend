#!/bin/bash
# Provision JDK 25 for Claude Code cloud sessions.
#
# The reactor requires Java 25 (see <java.version> in pom.xml), but the cloud
# container image ships OpenJDK 21 and the network policy blocks SDKMAN and
# Adoptium. download.oracle.com is reachable, so this installs Oracle JDK 25
# (NFTC license).
#
# Use it in two places:
#
#   1. As the cloud environment's *setup script* (claude.ai/code -> environment
#      settings -> Setup script). That runs once, before Claude Code launches,
#      and the resulting filesystem is snapshotted and reused by later sessions.
#      Because it drops a /etc/profile.d entry, every future session gets
#      JAVA_HOME/PATH pointing at JDK 25 with nothing else to do.
#   2. As the SessionStart hook (.claude/hooks/session-start.sh), which covers
#      sessions started before the snapshot was rebuilt.
#
# Idempotent and non-interactive: safe to run repeatedly.
set -euo pipefail

JDK_BASE="${JDK_BASE:-$HOME/.jdk}"
JDK_URL="https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz"
PROFILE_D_FILE="/etc/profile.d/zz-jdk25.sh"

find_jdk() {
  ls -d "$JDK_BASE"/jdk-25* 2>/dev/null | sort -V | tail -1
}

JDK_HOME="$(find_jdk || true)"

if [ -z "$JDK_HOME" ] || [ ! -x "$JDK_HOME/bin/javac" ]; then
  echo "Installing JDK 25 from download.oracle.com..."
  mkdir -p "$JDK_BASE"
  curl -sSL -o "$JDK_BASE/jdk25.tar.gz" "$JDK_URL"
  curl -sSL -o "$JDK_BASE/jdk25.sha256" "$JDK_URL.sha256"
  (cd "$JDK_BASE" && echo "$(cat jdk25.sha256) jdk25.tar.gz" | sha256sum -c -)
  tar -xzf "$JDK_BASE/jdk25.tar.gz" -C "$JDK_BASE"
  rm -f "$JDK_BASE/jdk25.tar.gz" "$JDK_BASE/jdk25.sha256"
  JDK_HOME="$(find_jdk)"
fi

"$JDK_HOME/bin/javac" -version

# Every login shell in this container (and in every session restored from the
# cached snapshot) picks this up. The name sorts after the image's own
# /etc/profile.d/java.sh, which pins JDK 21, so this wins.
if [ -w /etc/profile.d ] || [ "$(id -u)" = "0" ]; then
  cat > "$PROFILE_D_FILE" <<EOF
export JAVA_HOME="$JDK_HOME"
export PATH="\$JAVA_HOME/bin:\$PATH"
EOF
  chmod 0644 "$PROFILE_D_FILE"
  echo "Wrote $PROFILE_D_FILE"
else
  echo "WARNING: cannot write $PROFILE_D_FILE; JDK 25 will not be on PATH by default" >&2
fi

# SessionStart-hook path: export into the session Claude Code is starting.
if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=\"$JDK_HOME\""
    echo "export PATH=\"$JDK_HOME/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

echo "JDK 25 ready at $JDK_HOME"
