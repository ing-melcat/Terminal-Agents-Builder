#!/data/data/com.termux/files/usr/bin/sh
# bootstrap-custom.sh — Idempotent post-bootstrap provisioner for Terminal Agents Builder
# Runs strictly AFTER TermuxInstaller atomic rename ($STAGING_PREFIX -> $PREFIX).

set -eu

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
STAMP_FILE="$HOME_DIR/.config/melcat/.bootstrap_provisioned_v7"

export PATH="$PREFIX/bin:$PATH"
export HOME="$HOME_DIR"

mkdir -p "$HOME_DIR/.config/melcat"
mkdir -p "$HOME_DIR/.config/antigravity"
mkdir -p "$HOME_DIR/.gemini/config/skills"
mkdir -p "$HOME_DIR/.gemini/antigravity-cli"
mkdir -p "$HOME_DIR/.claude"
mkdir -p "$HOME_DIR/.local/share/melcat/logs"
mkdir -p "$HOME_DIR/.local/share/melcat/snapshots"

chmod 0700 "$HOME_DIR/.config/antigravity" "$HOME_DIR/.gemini" "$HOME_DIR/.gemini/antigravity-cli" "$HOME_DIR/.local/share/melcat/snapshots" 2>/dev/null || true

# Clean up deprecated helper from earlier versions if present
rm -f "$PREFIX/etc/melcat/agy-oauth.js" 2>/dev/null || true

# 1. Unified skills symlinks:
#    ~/.claude/skills                  -> ~/.gemini/config/skills
#    ~/.gemini/antigravity-cli/skills  -> ~/.gemini/config/skills
for LINK_TARGET in "$HOME_DIR/.claude/skills" "$HOME_DIR/.gemini/antigravity-cli/skills"; do
    if [ ! -L "$LINK_TARGET" ]; then
        rm -rf "$LINK_TARGET"
        ln -s "$HOME_DIR/.gemini/config/skills" "$LINK_TARGET"
    fi
done

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
Provisioned for arm64-v8a native Antigravity CLI (agy) & Claude CLI execution.
EOF
    fi
done

# 3. Install Android browser launcher bridge (termux-open-url & xdg-open) so real `agy` opens Chrome natively
cat > "$PREFIX/bin/termux-open-url" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
URL="${1:-}"
if [ -z "$URL" ]; then
    echo "Usage: termux-open-url <url>" >&2
    exit 1
fi
am start --user 0 -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1 || \
am start -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1
EOF
chmod 0700 "$PREFIX/bin/termux-open-url"
ln -sf "$PREFIX/bin/termux-open-url" "$PREFIX/bin/xdg-open"

# 4. Install melcat-gc (Storage & Cache Optimizer + zstd snapshot tool, strictly excluding OAuth tokens)
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
        tar --exclude='*oauth-token*' --exclude='*oauth_creds.json*' \
            --exclude='.gemini/antigravity-cli/antigravity-oauth-token' \
            --exclude='.config/antigravity/antigravity-oauth-token' \
            --exclude='.gemini/antigravity-oauth-token' \
            --exclude='.gemini/oauth_creds.json' \
            --exclude='.gemini/google_accounts.json' \
            -cf - -C "$HOME_DIR" .gemini .claude .config/melcat 2>/dev/null | zstd -T0 -q -o "$OUT"
        chmod 0600 "$OUT" 2>/dev/null || true
        echo "[storage-gc] Portable zstd snapshot written (secrets excluded): $OUT"
    else
        OUT_GZ="$SNAP_DIR/agent-workspace-$TS.tar.gz"
        tar --exclude='*oauth-token*' --exclude='*oauth_creds.json*' \
            --exclude='.gemini/antigravity-cli/antigravity-oauth-token' \
            --exclude='.config/antigravity/antigravity-oauth-token' \
            --exclude='.gemini/antigravity-oauth-token' \
            --exclude='.gemini/oauth_creds.json' \
            --exclude='.gemini/google_accounts.json' \
            -czf "$OUT_GZ" -C "$HOME_DIR" .gemini .claude .config/melcat 2>/dev/null
        chmod 0600 "$OUT_GZ" 2>/dev/null || true
        echo "[storage-gc] Portable gzip snapshot written (secrets excluded): $OUT_GZ"
    fi
fi

df -h "$PREFIX" | tail -n 1 | awk '{print "[storage-gc] Free storage: " $4 " (" $5 " used)"}'
EOF
chmod 0700 "$PREFIX/bin/melcat-gc"

# 5. Install `workstation` controller CLI (keeps `$PREFIX/bin/agy` 100% dedicated to the real Google Antigravity CLI)
cat > "$PREFIX/bin/workstation" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
SKILLS_DIR="$HOME_DIR/.gemini/config/skills"
AGY_CLI_TOKEN="$HOME_DIR/.gemini/antigravity-cli/antigravity-oauth-token"
AGY_CFG_TOKEN="$HOME_DIR/.config/antigravity/antigravity-oauth-token"
AGY_INSTALLER_URL="https://raw.githubusercontent.com/wallentx/antigravity-cli-termux/dev/install.sh"

find_active_token() {
    for f in "$AGY_CLI_TOKEN" "$AGY_CFG_TOKEN" "$HOME_DIR/.gemini/antigravity-oauth-token"; do
        if [ -s "$f" ]; then
            printf '%s' "$f"
            return 0
        fi
    done
    return 1
}

open_ui() {
    OUT="$(am start --activity-reorder-to-front -n com.termux/com.termux.custom.ui.WorkstationActivity ${1:+--es melcat.open_panel "$1"} 2>&1 || true)"
    case "$OUT" in
        *Error*|*error*|*Exception*|*"not found"*)
            echo "[workstation] ERROR: could not open Workstation UI:" >&2
            echo "$OUT" >&2
            return 1
            ;;
    esac
    echo "[workstation] Workstation UI opened."
}

install_real_agy() {
    echo "[workstation] Preparing native Termux arm64 runtime for Google Antigravity CLI (agy)..."
    export DEBIAN_FRONTEND=noninteractive
    pkg update -y && pkg upgrade -y -o Dpkg::Options::=--force-confold
    pkg install -y -o Dpkg::Options::=--force-confold glibc-repo curl tar ca-certificates resolv-conf
    pkg update -y
    pkg install -y -o Dpkg::Options::=--force-confold glibc

    if ! grep -q "atomics" /proc/cpuinfo 2>/dev/null; then
        echo "[workstation] CPU lacks ARMv8.1 LSE atomics; installing qemu-user-aarch64..."
        pkg install -y -o Dpkg::Options::=--force-confold qemu-user-aarch64
    fi

    echo "[workstation] Downloading & installing native Antigravity CLI (VA39 Termux standalone)..."
    curl -fsSL "$AGY_INSTALLER_URL" | AGY_INSTALL_SKIP_LAUNCH=1 bash
    if [ -x "$PREFIX/bin/agy" ] && [ -x "$PREFIX/bin/agy.va39" ]; then
        echo "[workstation] Native Antigravity CLI installed: $("$PREFIX/bin/agy" --version 2>/dev/null || echo 'ready')"
        echo "[workstation] Run 'agy' now to sign in with Google and launch Antigravity CLI."
    else
        echo "[workstation] ERROR: Antigravity CLI binary was not installed cleanly." >&2
        return 1
    fi
}

print_help() {
    echo "============================================================"
    echo " Terminal Agents Builder — Workstation Controller"
    echo "============================================================"
    if [ -x "$PREFIX/bin/agy.va39" ] && [ -x "$PREFIX/bin/agy" ]; then
        echo " Antigravity: INSTALLED ($("$PREFIX/bin/agy" --version 2>/dev/null || echo 'native arm64'))"
    else
        echo " Antigravity: NOT INSTALLED (run 'workstation setup' or 'agy')"
    fi
    echo " Skills Dir : $SKILLS_DIR ($(ls -1 "$SKILLS_DIR" 2>/dev/null | tr '\n' ' '))"
    if TF="$(find_active_token 2>/dev/null)"; then
        echo " OAuth Vault: ACTIVE ($TF, mode 0$(stat -c '%a' "$TF" 2>/dev/null || echo '600'))"
    else
        echo " OAuth Vault: NOT SET (run 'agy' to sign in with Google)"
    fi
    echo "------------------------------------------------------------"
    echo " Commands:"
    echo "   agy                    -> Run official Google Antigravity CLI"
    echo "   workstation setup      -> Install native Antigravity CLI + toolchain"
    echo "   workstation install-agy-> Install/reinstall native Antigravity CLI (agy)"
    echo "   workstation upgrade    -> Upgrade Termux packages + 'agy update'"
    echo "   workstation doctor     -> Diagnose agy, glibc, TLS, DNS, skills & vault"
    echo "   workstation skills     -> List unified Antigravity & Claude skills"
    echo "   workstation token      -> Check Antigravity OAuth 0600 status"
    echo "   workstation ui         -> Bring 120Hz Workstation UI to front"
    echo "   melcat-gc              -> Clean caches & create zstd backups"
    echo "============================================================"
}

case "${1:-ui}" in
    ui|--ui)
        open_ui "${2:-}"
        ;;
    setup|--setup)
        install_real_agy
        echo "[workstation] Installing developer toolchain (git, zstd, zsh, tmux, ripgrep, jq)..."
        export DEBIAN_FRONTEND=noninteractive
        pkg install -y -o Dpkg::Options::=--force-confold git zstd zsh tmux ripgrep jq
        echo "[workstation] Ready! Run 'agy' to launch Google Antigravity CLI."
        ;;
    install-agy|reinstall-agy)
        install_real_agy
        ;;
    upgrade|--upgrade)
        echo "[workstation] Upgrading Termux packages..."
        export DEBIAN_FRONTEND=noninteractive
        pkg update -y && pkg upgrade -y -o Dpkg::Options::=--force-confold
        if [ -x "$PREFIX/bin/agy.va39" ] && [ -x "$PREFIX/bin/agy" ]; then
            echo "[workstation] Checking Antigravity CLI updates ('agy update')..."
            "$PREFIX/bin/agy" update || true
        fi
        echo "[workstation] Upgrade complete."
        ;;
    skills|--skills)
        echo "[workstation] Active global skills in $SKILLS_DIR:"
        ls -1 "$SKILLS_DIR" 2>/dev/null || true
        echo "  -> symlinked to ~/.gemini/antigravity-cli/skills and ~/.claude/skills"
        ;;
    token|--token)
        if TF="$(find_active_token 2>/dev/null)"; then
            MODE="$(stat -c '%a' "$TF" 2>/dev/null || echo '600')"
            echo "[workstation] Antigravity OAuth ACTIVE at $TF (mode: 0$MODE)"
        else
            echo "[workstation] Antigravity OAuth NOT set. Run 'agy' to sign in with Google."
        fi
        ;;
    doctor|--doctor)
        echo "[workstation] Doctor — $(uname -m) / Android $(getprop ro.build.version.release 2>/dev/null || echo '?')"
        if [ -x "$PREFIX/bin/agy.va39" ] && [ -x "$PREFIX/bin/agy" ]; then
            VER="$("$PREFIX/bin/agy" --version 2>/dev/null || echo 'error executing')"
            echo "  [ok]      agy ($VER)"
        else
            echo "  [missing] agy (Antigravity CLI) -> run 'workstation setup'"
        fi
        if [ -x "$PREFIX/glibc/lib/ld-linux-aarch64.so.1" ]; then
            echo "  [ok]      glibc loader (ld-linux-aarch64.so.1)"
        else
            echo "  [missing] glibc loader -> run 'workstation setup'"
        fi
        if [ -s "$PREFIX/etc/tls/cert.pem" ]; then
            echo "  [ok]      TLS CA bundle ($PREFIX/etc/tls/cert.pem)"
        else
            echo "  [missing] ca-certificates -> run 'workstation setup'"
        fi
        if [ -r "$PREFIX/etc/resolv.conf" ]; then
            echo "  [ok]      DNS resolver ($PREFIX/etc/resolv.conf)"
        else
            echo "  [missing] resolv-conf -> run 'workstation setup'"
        fi
        for bin in git zstd zsh tmux rg jq curl; do
            if command -v "$bin" >/dev/null 2>&1; then
                echo "  [ok]      $bin"
            else
                echo "  [missing] $bin -> run 'workstation setup'"
            fi
        done
        echo "  skills : $(ls -1 "$SKILLS_DIR" 2>/dev/null | wc -l | tr -d ' ') in $SKILLS_DIR"
        if TF="$(find_active_token 2>/dev/null)"; then
            echo "  [ok]      OAuth token ($TF, mode 0$(stat -c '%a' "$TF" 2>/dev/null || echo '600'))"
        else
            echo "  [warn]    Not signed in -> run 'agy'"
        fi
        echo "  storage: $(du -sh "$HOME_DIR" 2>/dev/null | cut -f1) in HOME"
        ;;
    -h|--help|help)
        print_help
        ;;
    *)
        echo "[workstation] Unknown command: '$1'" >&2
        print_help
        exit 2
        ;;
esac
EOF
chmod 0700 "$PREFIX/bin/workstation"

# 6. Install first-run `agy` bootstrapper ONLY if the real native Antigravity CLI (`agy.va39`) is not installed yet.
#    Once installed, `$PREFIX/bin/agy` is the real C bootstrapper + `$PREFIX/bin/agy.va39` and is NEVER overwritten!
if [ ! -x "$PREFIX/bin/agy.va39" ]; then
    cat > "$PREFIX/bin/agy" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
PREFIX="/data/data/com.termux/files/usr"
echo "[agy] First run: installing official Google Antigravity CLI (native Termux arm64)..."
"$PREFIX/bin/workstation" install-agy
if [ -x "$PREFIX/bin/agy.va39" ] && [ -x "$PREFIX/bin/agy" ]; then
    exec "$PREFIX/bin/agy" "$@"
fi
exit 1
EOF
    chmod 0700 "$PREFIX/bin/agy"
fi

# 7. Configure shell profiles (~/.bashrc and ~/.zshrc) cleanly for real Antigravity CLI + browser bridge
MARKER="# >>> TERMINAL-AGENTS-BUILDER >>>"
for PROFILE_RC in "$HOME_DIR/.bashrc" "$HOME_DIR/.zshrc"; do
    if [ ! -f "$PROFILE_RC" ] || ! grep -q "$MARKER" "$PROFILE_RC"; then
        cat >> "$PROFILE_RC" <<'EOF'

# >>> TERMINAL-AGENTS-BUILDER >>>
export TERMUX_WORKSTATION=1
export BROWSER="/data/data/com.termux/files/usr/bin/termux-open-url"
export PATH="/data/data/com.termux/files/usr/bin:$HOME/.local/bin:$PATH"
alias gc='melcat-gc'
alias gc-snap='melcat-gc --snapshot'
# <<< TERMINAL-AGENTS-BUILDER <<<
EOF
    fi
done

date -u +"%Y-%m-%dT%H:%M:%SZ" > "$STAMP_FILE"
chmod 0600 "$STAMP_FILE"
