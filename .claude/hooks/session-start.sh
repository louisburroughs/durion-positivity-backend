#!/bin/bash
# SessionStart hook for Claude Code on the web: provision JDK 25.
#
# The build requires Java 25 (enforced by maven-enforcer), but the remote
# container ships JDK 21 and the network policy blocks SDKMAN/Adoptium.
# download.oracle.com is reachable, so we install Oracle JDK 25 (NFTC
# license) and export JAVA_HOME/PATH for the session. The extracted JDK is
# cached with the container, so subsequent sessions skip the download.
set -euo pipefail

# Web sessions only — local developers use SDKMAN per .sdkmanrc.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

JDK_BASE="$HOME/.jdk"
JDK_URL="https://download.oracle.com/java/25/latest/jdk-25_linux-x64_bin.tar.gz"

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

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  {
    echo "export JAVA_HOME=\"$JDK_HOME\""
    echo "export PATH=\"$JDK_HOME/bin:\$PATH\""
  } >> "$CLAUDE_ENV_FILE"
fi

echo "JDK 25 ready at $JDK_HOME"
