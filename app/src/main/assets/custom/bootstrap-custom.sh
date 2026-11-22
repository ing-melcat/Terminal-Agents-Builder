#!/data/data/com.termux/files/usr/bin/sh
# bootstrap-custom.sh — Idempotent post-bootstrap provisioner for Terminal Agents Builder
# Runs strictly AFTER TermuxInstaller atomic rename ($STAGING_PREFIX -> $PREFIX).

set -eu

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
STAMP_FILE="$HOME_DIR/.config/melcat/.bootstrap_provisioned_v4"

export PATH="$PREFIX/bin:$PATH"
export HOME="$HOME_DIR"

mkdir -p "$HOME_DIR/.config/melcat"
mkdir -p "$HOME_DIR/.config/antigravity"
mkdir -p "$HOME_DIR/.gemini/config/skills"
mkdir -p "$HOME_DIR/.claude"
mkdir -p "$HOME_DIR/.local/share/melcat/logs"
mkdir -p "$HOME_DIR/.local/share/melcat/snapshots"

chmod 0700 "$HOME_DIR/.config/antigravity" "$HOME_DIR/.gemini" "$HOME_DIR/.local/share/melcat/snapshots" 2>/dev/null || true

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

# 3. Install melcat-gc (Storage & Cache Optimizer + zstd snapshot tool, strictly excluding OAuth tokens)
cat > "$PREFIX/bin/melcat-gc" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
LOG_DIR="$HOME_DIR/.local/share/melcat/logs"
SNAP_DIR="$HOME_DIR/.local/share/melcat/snapshots"

mkdir -p "$LOG_DIR" "$SNAP_DIR"
chmod 0700 "$SNAP_DIR" 2>/dev/null || true

echo "[storage-gc] Cleaning apt, pip, and npm caches..."
rm -rf "$PREFIX/var/cache/apt/archives/"*.deb 2>/dev/null || true
rm -rf "$HOME_DIR/.cache/pip" "$HOME_DIR/.npm/_cacache" 2>/dev/null || true

if command -v zstd >/dev/null 2>&1; then
    echo "[storage-gc] Compressing logs older than 1 day with zstd -T0..."
    find "$LOG_DIR" -type f -name "*.log" -mtime +0 -exec zstd -T0 --rm -q {} + 2>/dev/null || true
fi

if [ "${1:-}" = "--snapshot" ]; then
    TS="$(date +%Y%m%d-%H%M%S)"
    if command -v zstd >/dev/null 2>&1; then
        OUT="$SNAP_DIR/agent-workspace-$TS.tar.zst"
        tar --exclude='*oauth-token*' --exclude='.config/antigravity/antigravity-oauth-token' --exclude='.gemini/antigravity-oauth-token' \
            -cf - -C "$HOME_DIR" .gemini .claude .config/melcat 2>/dev/null | zstd -T0 -q -o "$OUT"
        chmod 0600 "$OUT" 2>/dev/null || true
        echo "[storage-gc] Portable zstd snapshot written (secrets excluded): $OUT"
    else
        OUT_GZ="$SNAP_DIR/agent-workspace-$TS.tar.gz"
        tar --exclude='*oauth-token*' --exclude='.config/antigravity/antigravity-oauth-token' --exclude='.gemini/antigravity-oauth-token' \
            -czf "$OUT_GZ" -C "$HOME_DIR" .gemini .claude .config/melcat 2>/dev/null
        chmod 0600 "$OUT_GZ" 2>/dev/null || true
        echo "[storage-gc] Portable gzip snapshot written (secrets excluded): $OUT_GZ"
    fi
fi

df -h "$PREFIX" | tail -n 1 | awk '{print "[storage-gc] Free storage: " $4 " (" $5 " used)"}'
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

print_help() {
    echo "============================================================"
    echo " Terminal Agents Builder — Native arm64-v8a CLI (agy)"
    echo "============================================================"
    echo " Skills Dir : $SKILLS_DIR ($(ls -1 "$SKILLS_DIR" 2>/dev/null | tr '\n' ' '))"
    if [ -s "$TOKEN_FILE" ]; then
        echo " OAuth Vault: ACTIVE (0600 inherited)"
    else
        echo " OAuth Vault: NOT SET (run 'agy login' or 'agy ui' -> Shield)"
    fi
    echo "------------------------------------------------------------"
    echo " Commands:"
    echo "   agy ui        -> Open Workstation UI"
    echo "   agy setup     -> Install git, nodejs-lts, zstd, zsh, tmux, rg, jq"
    echo "   agy upgrade   -> Upgrade all Termux packages (pkg upgrade)"
    echo "   agy skills    -> List unified ~/.gemini & ~/.claude skills"
    echo "   agy login     -> Paste OAuth token into 0600 vault (hidden input)"
    echo "   agy logout    -> Wipe & remove OAuth token"
    echo "   agy token     -> Check OAuth token 0600 status"
    echo "   agy doctor    -> Diagnose toolchain, skills, vault, storage"
    echo "   agy help      -> Show this help"
    echo "   melcat-gc     -> Clean caches & zstd-compress logs"
    echo "============================================================"
}

open_ui() {
    OUT="$(am start --activity-reorder-to-front -n com.termux/com.termux.custom.ui.WorkstationActivity ${1:+--es melcat.open_panel "$1"} 2>&1 || true)"
    case "$OUT" in
        *Error*|*error*|*Exception*|*"not found"*)
            echo "[agy] ERROR: could not open Workstation UI:" >&2
            echo "$OUT" >&2
            echo "[agy] Open the 'Terminal Agents' app icon from the launcher instead." >&2
            return 1
            ;;
    esac
    echo "[agy] Workstation UI opened."
}

case "${1:-}" in
    --ui|ui|workstation)
        echo "[agy] Opening 120Hz Terminal Agents Workstation..."
        open_ui ""
        exit $?
        ;;
    --setup|setup)
        echo "[agy] Installing native arm64-v8a toolchain (git, nodejs-lts, zstd, zsh, tmux, ripgrep, jq)..."
        export DEBIAN_FRONTEND=noninteractive
        pkg update -y && pkg install -y -o Dpkg::Options::=--force-confold git nodejs-lts zstd zsh tmux ripgrep jq
        echo "[agy] Native toolchain ready. Tip: run 'agy upgrade' to update all packages."
        exit 0
        ;;
    --upgrade|upgrade)
        echo "[agy] Upgrading all Termux packages..."
        export DEBIAN_FRONTEND=noninteractive
        pkg upgrade -y -o Dpkg::Options::=--force-confold
        echo "[agy] Packages upgraded."
        exit 0
        ;;
    --skills|skills)
        echo "[agy] Active skills in $SKILLS_DIR (symlinked to ~/.claude/skills):"
        ls -1 "$SKILLS_DIR" 2>/dev/null || true
        exit 0
        ;;
    --login|login)
        if [ ! -t 0 ]; then
            echo "[agy] 'agy login' needs an interactive terminal." >&2
            exit 1
        fi
        printf "[agy] Paste your Antigravity OAuth token (input hidden): "
        stty -echo 2>/dev/null || true
        IFS= read -r TOKEN_INPUT || TOKEN_INPUT=""
        stty echo 2>/dev/null || true
        echo
        TOKEN_INPUT="$(printf '%s' "$TOKEN_INPUT" | tr -d '\r')"
        case "$TOKEN_INPUT" in
            ""|*" "*|*"	"*)
                echo "[agy] Invalid token (empty or contains whitespace). Nothing saved." >&2
                exit 1
                ;;
        esac
        umask 077
        mkdir -p "$(dirname "$TOKEN_FILE")"
        TMP_FILE="$TOKEN_FILE.tmp.$$"
        printf '%s' "$TOKEN_INPUT" > "$TMP_FILE"
        chmod 600 "$TMP_FILE"
        mv -f "$TMP_FILE" "$TOKEN_FILE"
        mkdir -p "$HOME_DIR/.gemini"
        for COPY in "$HOME_DIR/.gemini/antigravity-oauth-token" "/data/data/com.termux/files/antigravity-oauth-token"; do
            cp -f "$TOKEN_FILE" "$COPY" && chmod 600 "$COPY"
        done
        unset TOKEN_INPUT
        echo "[agy] OAuth token saved to 0600 vault. New shells inherit ANTIGRAVITY_OAUTH_TOKEN."
        exit 0
        ;;
    --logout|logout)
        for f in "$TOKEN_FILE" "$HOME_DIR/.gemini/antigravity-oauth-token" "/data/data/com.termux/files/antigravity-oauth-token"; do
            if [ -f "$f" ]; then
                SIZE="$(wc -c < "$f" 2>/dev/null || echo 0)"
                head -c "$SIZE" /dev/zero > "$f" 2>/dev/null || true
                rm -f "$f"
            fi
        done
        echo "[agy] OAuth token wiped. Run 'unset ANTIGRAVITY_OAUTH_TOKEN' or open a new session."
        exit 0
        ;;
    --token|token)
        if [ -s "$TOKEN_FILE" ]; then
            MODE="$(stat -c '%a' "$TOKEN_FILE" 2>/dev/null || echo '600')"
            echo "[agy] OAuth token ACTIVE at $TOKEN_FILE (mode: 0$MODE)"
        else
            echo "[agy] OAuth token NOT provisioned. Run 'agy login' or open Shield tab -> OAuth Vault."
        fi
        exit 0
        ;;
    --doctor|doctor)
        echo "[agy] Doctor — $(uname -m) / Android $(getprop ro.build.version.release 2>/dev/null || echo '?')"
        for bin in git node npm zstd zsh tmux rg jq; do
            if command -v "$bin" >/dev/null 2>&1; then
                echo "  [ok]      $bin"
            else
                echo "  [missing] $bin  -> run 'agy setup'"
            fi
        done
        echo "  skills : $(ls -1 "$SKILLS_DIR" 2>/dev/null | wc -l | tr -d ' ') in $SKILLS_DIR"
        if [ -L "$HOME_DIR/.claude/skills" ]; then echo "  [ok]      ~/.claude/skills symlink"; else echo "  [warn]    ~/.claude/skills symlink missing"; fi
        if [ -s "$TOKEN_FILE" ]; then echo "  [ok]      OAuth vault (0$(stat -c '%a' "$TOKEN_FILE" 2>/dev/null))"; else echo "  [warn]    OAuth vault empty -> 'agy login'"; fi
        echo "  storage: $(du -sh "$HOME_DIR" 2>/dev/null | cut -f1) in HOME"
        UPG="$(apt list --upgradable 2>/dev/null | grep -c upgradable || true)"
        [ "${UPG:-0}" -gt 0 ] 2>/dev/null && echo "  [info]    $UPG packages upgradable -> 'agy upgrade'"
        exit 0
        ;;
    --skill|--resume)
        if [ -x "$HOME_DIR/.local/bin/agy" ]; then
            exec "$HOME_DIR/.local/bin/agy" "$@"
        fi
        echo "[agy] '$1' requires the full Antigravity CLI at ~/.local/bin/agy (not installed yet)." >&2
        echo "[agy] Skills available now: $(ls -1 "$SKILLS_DIR" 2>/dev/null | tr '\n' ' ')" >&2
        exit 3
        ;;
    -h|--help|help)
        print_help
        exit 0
        ;;
    "")
        ;;
    *)
        if [ -x "$HOME_DIR/.local/bin/agy" ]; then
            exec "$HOME_DIR/.local/bin/agy" "$@"
        fi
        echo "[agy] Unknown command: '$1'" >&2
        echo "[agy] Run 'agy help' to see available commands." >&2
        exit 2
        ;;
esac

if [ -x "$HOME_DIR/.local/bin/agy" ]; then
    exec "$HOME_DIR/.local/bin/agy" "$@"
fi

print_help
EOF
chmod 0700 "$PREFIX/bin/agy"

# 5. Install `workstation` shortcut command
cat > "$PREFIX/bin/workstation" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec agy ui
EOF
chmod 0700 "$PREFIX/bin/workstation"

# 6. Configure shell profiles (~/.bashrc and ~/.zshrc) for Antigravity & Claude CLI token inheritance
MARKER="# >>> TERMINAL-AGENTS-BUILDER >>>"
for PROFILE_RC in "$HOME_DIR/.bashrc" "$HOME_DIR/.zshrc"; do
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
done

date -u +"%Y-%m-%dT%H:%M:%SZ" > "$STAMP_FILE"
chmod 0600 "$STAMP_FILE"
