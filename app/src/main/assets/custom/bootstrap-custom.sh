#!/data/data/com.termux/files/usr/bin/sh
# bootstrap-custom.sh — Idempotent post-bootstrap provisioner for Terminal Agents Builder
# Runs AFTER TermuxInstaller atomic rename ($STAGING_PREFIX -> $PREFIX).

set -eu

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
STAMP_FILE="$HOME_DIR/.config/melcat/.bootstrap_provisioned_v1"

export PATH="$PREFIX/bin:$PATH"
export HOME="$HOME_DIR"

mkdir -p "$HOME_DIR/.config/melcat"
mkdir -p "$HOME_DIR/.config/antigravity"
mkdir -p "$HOME_DIR/.gemini/config/skills"
mkdir -p "$HOME_DIR/.claude"
mkdir -p "$HOME_DIR/.local/share/melcat/logs"
mkdir -p "$HOME_DIR/.local/share/melcat/snapshots"

# 1. Unified skills symlink: ~/.claude/skills -> ~/.gemini/config/skills
if [ ! -L "$HOME_DIR/.claude/skills" ]; then
    rm -rf "$HOME_DIR/.claude/skills"
    ln -s "$HOME_DIR/.gemini/config/skills" "$HOME_DIR/.claude/skills"
fi

# 2. Seed core active skills (senior-auditor, motion-framer, caveman) if not present
for skill in senior-auditor motion-framer caveman; do
    SKILL_DIR="$HOME_DIR/.gemini/config/skills/$skill"
    mkdir -p "$SKILL_DIR"
    if [ ! -f "$SKILL_DIR/SKILL.md" ]; then
        cat > "$SKILL_DIR/SKILL.md" <<EOF
---
name: $skill
description: Active workstation skill ($skill) provisioned by Terminal Agents Builder
---
# $skill
Provisioned for arm64-v8a native agent execution (agy & claude).
EOF
    fi
done

# 3. Install melcat-gc (Storage & Cache Optimizer + zstd snapshot tool)
cat > "$PREFIX/bin/melcat-gc" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
LOG_DIR="$HOME_DIR/.local/share/melcat/logs"
SNAP_DIR="$HOME_DIR/.local/share/melcat/snapshots"

mkdir -p "$LOG_DIR" "$SNAP_DIR"

echo "[melcat-gc] Cleaning apt, pip, and npm caches..."
rm -rf "$PREFIX/var/cache/apt/archives/"*.deb 2>/dev/null || true
rm -rf "$HOME_DIR/.cache/pip" "$HOME_DIR/.npm/_cacache" 2>/dev/null || true

if command -v zstd >/dev/null 2>&1; then
    echo "[melcat-gc] Compressing logs older than 1 day with zstd -T0..."
    find "$LOG_DIR" -type f -name "*.log" -mtime +0 -exec zstd -T0 --rm -q {} + 2>/dev/null || true
fi

if [ "${1:-}" = "--snapshot" ]; then
    TS="$(date +%Y%m%d-%H%M%S)"
    OUT="$SNAP_DIR/agent-workspace-$TS.tar.zst"
    if command -v zstd >/dev/null 2>&1; then
        tar --zstd -cf "$OUT" -C "$HOME_DIR" .gemini .claude .config/melcat 2>/dev/null || true
        echo "[melcat-gc] Snapshot written: $OUT"
    else
        OUT_GZ="$SNAP_DIR/agent-workspace-$TS.tar.gz"
        tar -czf "$OUT_GZ" -C "$HOME_DIR" .gemini .claude .config/melcat 2>/dev/null || true
        echo "[melcat-gc] Snapshot written (gzip fallback): $OUT_GZ"
    fi
fi

df -h "$PREFIX" | tail -n 1 | awk '{print "[melcat-gc] Free storage: " $4 " (" $5 " used)"}'
EOF
chmod 0700 "$PREFIX/bin/melcat-gc"

# 4. Configure shell profile for Antigravity (agy) & Claude CLI OAuth token inheritance
PROFILE_RC="$HOME_DIR/.bashrc"
MARKER="# >>> TERMINAL-AGENTS-BUILDER >>>"
if [ ! -f "$PROFILE_RC" ] || ! grep -q "$MARKER" "$PROFILE_RC"; then
    cat >> "$PROFILE_RC" <<'EOF'

# >>> TERMINAL-AGENTS-BUILDER >>>
export TERMUX_WORKSTATION=1
export PATH="$HOME/.local/bin:/data/data/com.termux/files/usr/bin:$PATH"
TOKEN_FILE="/data/data/com.termux/files/home/.config/antigravity/antigravity-oauth-token"
if [ -f "$TOKEN_FILE" ]; then
    chmod 600 "$TOKEN_FILE" 2>/dev/null || true
    export ANTIGRAVITY_OAUTH_TOKEN="$(cat "$TOKEN_FILE")"
fi
alias agy-audit='agy --skill senior-auditor'
alias gc='melcat-gc'
alias gc-snap='melcat-gc --snapshot'
# <<< TERMINAL-AGENTS-BUILDER <<<
EOF
fi

date -u +"%Y-%m-%dT%H:%M:%SZ" > "$STAMP_FILE"
chmod 0600 "$STAMP_FILE"
