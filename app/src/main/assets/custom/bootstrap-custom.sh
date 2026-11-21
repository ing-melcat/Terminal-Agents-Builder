#!/data/data/com.termux/files/usr/bin/sh
# bootstrap-custom.sh — Idempotent post-bootstrap provisioner for Terminal Agents Builder
# Runs AFTER TermuxInstaller atomic rename ($STAGING_PREFIX -> $PREFIX).

set -eu

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
STAMP_FILE="$HOME_DIR/.config/melcat/.bootstrap_provisioned_v2"

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

# 4. Install `agy` native CLI launcher & workstation controller
cat > "$PREFIX/bin/agy" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
TOKEN_FILE="$HOME_DIR/.config/antigravity/antigravity-oauth-token"
SKILLS_DIR="$HOME_DIR/.gemini/config/skills"

if [ -f "$TOKEN_FILE" ]; then
    chmod 600 "$TOKEN_FILE" 2>/dev/null || true
    export ANTIGRAVITY_OAUTH_TOKEN="$(cat "$TOKEN_FILE")"
fi

case "${1:-}" in
    --ui|ui|workstation)
        echo "[agy] Opening 120Hz Agents Workstation UI..."
        am start -n com.termux/com.termux.custom.ui.WorkstationActivity >/dev/null 2>&1 || \
        am start -n com.termux/.custom.ui.WorkstationActivity
        exit 0
        ;;
    --setup|setup)
        echo "[agy] Installing native arm64-v8a workstation toolchain (git, nodejs-lts, zstd, zsh, tmux, ripgrep, jq)..."
        pkg update -y && pkg install -y git nodejs-lts zstd zsh tmux ripgrep jq
        echo "[agy] Toolchain ready."
        exit 0
        ;;
    --skills|skills)
        echo "[agy] Active skills in $SKILLS_DIR (symlinked to ~/.claude/skills):"
        ls -1 "$SKILLS_DIR" 2>/dev/null || true
        exit 0
        ;;
    --token|token)
        if [ -f "$TOKEN_FILE" ]; then
            MODE="$(stat -c '%a' "$TOKEN_FILE" 2>/dev/null || echo '600')"
            echo "[agy] OAuth token PRESENT at $TOKEN_FILE (mode: 0$MODE)"
        else
            echo "[agy] OAuth token NOT provisioned yet. Run 'agy ui' -> Guardian & Token -> Provision."
        fi
        exit 0
        ;;
esac

if [ -x "$HOME_DIR/.local/bin/agy" ]; then
    exec "$HOME_DIR/.local/bin/agy" "$@"
fi

echo "============================================================"
echo " Antigravity CLI (agy) — Native arm64-v8a Workstation"
echo "============================================================"
echo " Prefix     : $PREFIX (targetSdk=28 W^X exec enabled)"
echo " Skills Dir : $SKILLS_DIR ($(ls -1 "$SKILLS_DIR" 2>/dev/null | tr '\n' ' '))"
if [ -f "$TOKEN_FILE" ]; then
    echo " OAuth Token: ACTIVE (0600 inherited)"
else
    echo " OAuth Token: NOT SET (provision via 'agy ui')"
fi
echo "------------------------------------------------------------"
echo " Commands:"
echo "   agy ui        -> Open 120Hz Compose Workstation UI"
echo "   agy setup     -> Install native git, nodejs-lts, zstd, zsh, tmux, rg"
echo "   agy skills    -> List unified ~/.gemini & ~/.claude skills"
echo "   agy token     -> Verify OAuth token 0600 status"
echo "   melcat-gc     -> Clean caches & zstd-compress logs"
echo "============================================================"
EOF
chmod 0700 "$PREFIX/bin/agy"

# 5. Install `workstation` shortcut command
cat > "$PREFIX/bin/workstation" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec agy ui
EOF
chmod 0700 "$PREFIX/bin/workstation"

# 6. Configure shell profile for Antigravity (agy) & Claude CLI OAuth token inheritance
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
