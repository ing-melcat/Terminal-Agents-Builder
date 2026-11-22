#!/data/data/com.termux/files/usr/bin/sh
# bootstrap-custom.sh — Idempotent post-bootstrap provisioner for Terminal Agents Builder (RUR Edition)
# Runs strictly AFTER TermuxInstaller atomic rename ($STAGING_PREFIX -> $PREFIX).

set -eu

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
STAMP_FILE="$HOME_DIR/.config/melcat/.bootstrap_provisioned_v10"

export PATH="$PREFIX/bin:$PATH"
export HOME="$HOME_DIR"

mkdir -p "$HOME_DIR/.config/melcat"
mkdir -p "$HOME_DIR/.config/melcat/vault"
mkdir -p "$HOME_DIR/.config/antigravity"
mkdir -p "$HOME_DIR/.config/opencode"
mkdir -p "$HOME_DIR/.gemini/config/skills"
mkdir -p "$HOME_DIR/.gemini/antigravity-cli"
mkdir -p "$HOME_DIR/.claude"
mkdir -p "$HOME_DIR/.local/share/melcat/logs"
mkdir -p "$HOME_DIR/.local/share/melcat/snapshots"

chmod 0700 \
    "$HOME_DIR/.config/melcat/vault" \
    "$HOME_DIR/.config/antigravity" \
    "$HOME_DIR/.gemini" \
    "$HOME_DIR/.gemini/antigravity-cli" \
    "$HOME_DIR/.local/share/melcat/snapshots" 2>/dev/null || true

# Clean up deprecated helper from earlier versions if present
rm -f "$PREFIX/etc/melcat/agy-oauth.js" 2>/dev/null || true

# 1. Unified skills symlinks across Antigravity CLI, OpenCode v2, and Claude CLI:
#    ~/.gemini/antigravity-cli/skills  -> ~/.gemini/config/skills
#    ~/.config/opencode/skills         -> ~/.gemini/config/skills
#    ~/.claude/skills                  -> ~/.gemini/config/skills
for LINK_TARGET in \
    "$HOME_DIR/.claude/skills" \
    "$HOME_DIR/.gemini/antigravity-cli/skills" \
    "$HOME_DIR/.config/opencode/skills"; do
    if [ ! -L "$LINK_TARGET" ]; then
        if [ -d "$LINK_TARGET" ]; then
            # Non-destructive migration: preserve any user-created skills before symlinking
            cp -rn "$LINK_TARGET/." "$HOME_DIR/.gemini/config/skills/" 2>/dev/null || \
                cp -r "$LINK_TARGET/." "$HOME_DIR/.gemini/config/skills/" 2>/dev/null || true
            rm -rf "$LINK_TARGET"
        elif [ -e "$LINK_TARGET" ]; then
            mv -f "$LINK_TARGET" "$LINK_TARGET.bak.$(date +%s)" 2>/dev/null || rm -f "$LINK_TARGET"
        fi
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
Provisioned for arm64-v8a native Antigravity CLI (agy) & OpenCode v2 execution.
EOF
    fi
done

# 2b. Seed OpenCode v2 Termux touch-keyboard keybind fix (~/.config/opencode/cli.json) ONLY if absent.
#     Android soft keyboards cannot emit Shift+Tab; binding agent.cycle to Tab lets users switch Build/Plan.
if [ ! -s "$HOME_DIR/.config/opencode/cli.json" ]; then
    cat > "$HOME_DIR/.config/opencode/cli.json" <<'EOF'
{
  "$schema": "https://opencode.ai/v2/cli.json",
  "keybinds": {
    "agent.cycle": "tab",
    "agent.cycle.reverse": "shift+tab"
  }
}
EOF
fi

# 3. Install Android browser launcher bridge (termux-open-url & xdg-open) so `agy` & `opencode` open Chrome natively
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

# 4. Install melcat-gc (Storage & Cache Optimizer + zstd snapshot tool, strictly excluding OAuth & API vaults)
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
    SOURCES=""
    for d in .gemini .claude .config/melcat .config/opencode; do
        if [ -e "$HOME_DIR/$d" ]; then
            SOURCES="$SOURCES $d"
        fi
    done
    if [ -z "$SOURCES" ]; then
        echo "[storage-gc] Nothing to snapshot yet."
    elif command -v zstd >/dev/null 2>&1; then
        OUT="$SNAP_DIR/agent-workspace-$TS.tar.zst"
        TMP_TAR="$OUT.tmp"
        umask 077
        # shellcheck disable=SC2086
        if tar --exclude='*oauth-token*' --exclude='*oauth_creds.json*' \
            --exclude='*credentials*' --exclude='*auth.json*' \
            --exclude='.ssh*' \
            --exclude='.config/melcat/ssh-devices.json*' \
            --exclude='*id_ed25519*' --exclude='*id_rsa*' --exclude='*.pem' \
            --exclude='.gemini/antigravity-cli/antigravity-oauth-token' \
            --exclude='.config/antigravity/antigravity-oauth-token' \
            --exclude='.gemini/antigravity-oauth-token' \
            --exclude='.gemini/oauth_creds.json' \
            --exclude='.gemini/google_accounts.json' \
            --exclude='.config/melcat/vault*' \
            --exclude='*.key' \
            --exclude='*providers.env*' \
            --exclude='.local/share/opencode/opencode.db*' \
            --exclude='.local/share/opencode/auth.json*' \
            -cf "$TMP_TAR" -C "$HOME_DIR" $SOURCES 2>/dev/null && zstd -T0 -q --rm "$TMP_TAR" -o "$OUT"; then
            chmod 0600 "$OUT" 2>/dev/null || true
            echo "[storage-gc] Portable zstd snapshot written (secrets excluded): $OUT"
        else
            rm -f "$TMP_TAR" "$OUT"
            echo "[storage-gc] ERROR: zstd snapshot failed." >&2
        fi
    else
        OUT_GZ="$SNAP_DIR/agent-workspace-$TS.tar.gz"
        umask 077
        # shellcheck disable=SC2086
        if tar --exclude='*oauth-token*' --exclude='*oauth_creds.json*' \
            --exclude='*credentials*' --exclude='*auth.json*' \
            --exclude='.ssh*' \
            --exclude='.config/melcat/ssh-devices.json*' \
            --exclude='*id_ed25519*' --exclude='*id_rsa*' --exclude='*.pem' \
            --exclude='.gemini/antigravity-cli/antigravity-oauth-token' \
            --exclude='.config/antigravity/antigravity-oauth-token' \
            --exclude='.gemini/antigravity-oauth-token' \
            --exclude='.gemini/oauth_creds.json' \
            --exclude='.gemini/google_accounts.json' \
            --exclude='.config/melcat/vault*' \
            --exclude='*.key' \
            --exclude='*providers.env*' \
            --exclude='.local/share/opencode/opencode.db*' \
            --exclude='.local/share/opencode/auth.json*' \
            -czf "$OUT_GZ" -C "$HOME_DIR" $SOURCES 2>/dev/null; then
            chmod 0600 "$OUT_GZ" 2>/dev/null || true
            echo "[storage-gc] Portable gzip snapshot written (secrets excluded): $OUT_GZ"
        else
            rm -f "$OUT_GZ"
            echo "[storage-gc] ERROR: gzip snapshot failed." >&2
        fi
    fi
fi

df -h "$PREFIX" | tail -n 1 | awk '{print "[storage-gc] Free storage: " $4 " (" $5 " used)"}'
EOF
chmod 0700 "$PREFIX/bin/melcat-gc"

# 5. Install `workstation` controller CLI (keeps `$PREFIX/bin/agy` and `$PREFIX/bin/opencode` dedicated to real binaries)
cat > "$PREFIX/bin/workstation" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
SKILLS_DIR="$HOME_DIR/.gemini/config/skills"
VAULT_DIR="$HOME_DIR/.config/melcat/vault"
AGY_CLI_TOKEN="$HOME_DIR/.gemini/antigravity-cli/antigravity-oauth-token"
AGY_CFG_TOKEN="$HOME_DIR/.config/antigravity/antigravity-oauth-token"
AGY_INSTALLER_URL="https://raw.githubusercontent.com/wallentx/antigravity-cli-termux/dev/install.sh"
OPENCODE_APT_BASE="https://bd-loser.github.io/opencode-bionic"

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

install_native_opencode() {
    if [ "$(uname -m)" != "aarch64" ]; then
        echo "[workstation] ERROR: native opencode-bionic requires aarch64 (detected $(uname -m))." >&2
        return 1
    fi
    echo "[workstation] Installing native Bionic OpenCode v2 (aarch64, zero proot/glibc)..."
    export DEBIAN_FRONTEND=noninteractive
    pkg update -y
    pkg install -y -o Dpkg::Options::=--force-confold curl ca-certificates

    mkdir -p "$PREFIX/etc/apt/keyrings" "$PREFIX/etc/apt/sources.list.d" "$PREFIX/etc/apt/preferences.d"
    rm -f "$PREFIX/etc/apt/trusted.gpg.d/opencode-bionic.gpg"

    KEYRING="$PREFIX/etc/apt/keyrings/opencode-bionic.gpg"
    TMP_KEY="$KEYRING.tmp.$$"
    echo "[workstation] Adding scoped opencode-bionic APT repository (stable v2 channel)..."
    if ! curl -fsSL --connect-timeout 15 --max-time 60 \
        "$OPENCODE_APT_BASE/opencode-bionic.gpg" -o "$TMP_KEY" || [ ! -s "$TMP_KEY" ]; then
        rm -f "$TMP_KEY"
        echo "[workstation] ERROR: failed to download opencode-bionic GPG key." >&2
        return 1
    fi
    chmod 0644 "$TMP_KEY"
    mv -f "$TMP_KEY" "$KEYRING"

    cat > "$PREFIX/etc/apt/sources.list.d/opencode-bionic.list" <<APTEOF
deb [arch=aarch64 signed-by=$KEYRING] $OPENCODE_APT_BASE stable main
APTEOF

    cat > "$PREFIX/etc/apt/preferences.d/opencode-bionic" <<'PINEOF'
Package: *
Pin: origin bd-loser.github.io
Pin-Priority: -1

Package: opencode
Pin: origin bd-loser.github.io
Pin-Priority: 1001
PINEOF

    pkg update -y
    pkg install -y -o Dpkg::Options::=--force-confold --allow-downgrades opencode

    mkdir -p "$HOME_DIR/.config/opencode"
    if [ ! -L "$HOME_DIR/.config/opencode/skills" ]; then
        rm -rf "$HOME_DIR/.config/opencode/skills"
        ln -s "$SKILLS_DIR" "$HOME_DIR/.config/opencode/skills"
    fi
    if [ ! -s "$HOME_DIR/.config/opencode/cli.json" ]; then
        cat > "$HOME_DIR/.config/opencode/cli.json" <<'CLIEOF'
{
  "$schema": "https://opencode.ai/v2/cli.json",
  "keybinds": {
    "agent.cycle": "tab",
    "agent.cycle.reverse": "shift+tab"
  }
}
CLIEOF
    fi

    if [ -x "$PREFIX/bin/opencode" ]; then
        echo "[workstation] Native OpenCode installed: $("$PREFIX/bin/opencode" --version 2>/dev/null || echo 'ready')"
        echo "[workstation] Run 'opencode' (use /models for free models, or configure Coding Models in Agents -> Models)."
    else
        echo "[workstation] ERROR: OpenCode binary was not installed cleanly." >&2
        return 1
    fi
}

install_openssh() {
    echo "[workstation] Installing native Termux OpenSSH client (ssh, ssh-keygen, scp)..."
    export DEBIAN_FRONTEND=noninteractive
    pkg update -y
    pkg install -y -o Dpkg::Options::=--force-confold openssh
    mkdir -p "$HOME_DIR/.ssh"
    chmod 0700 "$HOME_DIR/.ssh" 2>/dev/null || true
    if [ -x "$PREFIX/bin/ssh" ]; then
        echo "[workstation] OpenSSH installed: $("$PREFIX/bin/ssh" -V 2>&1 || echo 'ready')"
        echo "[workstation] Open the SSH panel (workstation ui ssh) to connect via Direct LAN or Tailscale."
    else
        echo "[workstation] ERROR: OpenSSH binary was not installed cleanly." >&2
        return 1
    fi
}

print_help() {
    echo "============================================================"
    echo " Terminal Agents Builder (RUR) — Workstation Controller"
    echo "============================================================"
    if [ -x "$PREFIX/bin/agy.va39" ] && [ -x "$PREFIX/bin/agy" ]; then
        echo " Antigravity: INSTALLED ($("$PREFIX/bin/agy" --version 2>/dev/null || echo 'native arm64'))"
    else
        echo " Antigravity: NOT INSTALLED (run 'workstation setup' or 'agy')"
    fi
    if [ -x "$PREFIX/bin/opencode" ]; then
        echo " OpenCode v2: INSTALLED ($("$PREFIX/bin/opencode" --version 2>/dev/null || echo 'native bionic'))"
    else
        echo " OpenCode v2: NOT INSTALLED (run 'workstation install-opencode')"
    fi
    if [ -x "$PREFIX/bin/ssh" ]; then
        echo " OpenSSH    : INSTALLED ($("$PREFIX/bin/ssh" -V 2>&1 | head -n 1 || echo 'native'))"
    else
        echo " OpenSSH    : NOT INSTALLED (run 'workstation install-ssh')"
    fi
    echo " Skills Dir : $SKILLS_DIR ($(ls -1 "$SKILLS_DIR" 2>/dev/null | tr '\n' ' '))"
    if TF="$(find_active_token 2>/dev/null)"; then
        echo " OAuth Vault: ACTIVE ($TF, mode 0$(stat -c '%a' "$TF" 2>/dev/null || echo '600'))"
    else
        echo " OAuth Vault: NOT SET (run 'agy' to sign in with Google)"
    fi
    echo "------------------------------------------------------------"
    echo " Commands:"
    echo "   agy                         -> Run official Google Antigravity CLI"
    echo "   opencode                    -> Run native Bionic OpenCode v2 CLI"
    echo "   workstation setup           -> Install native agy + developer toolchain"
    echo "   workstation install-agy     -> Install/reinstall native Antigravity CLI"
    echo "   workstation install-opencode-> Install/upgrade native Bionic OpenCode v2"
    echo "   workstation install-ssh     -> Install native OpenSSH client (LAN & Tailscale)"
    echo "   workstation upgrade         -> Upgrade Termux packages (incl. opencode) + agy"
    echo "   workstation doctor          -> Diagnose agy, opencode, ssh, glibc, TLS & vaults"
    echo "   workstation skills          -> List unified agy/opencode/claude skills"
    echo "   workstation token           -> Check Antigravity OAuth & Coding Vault 0600"
    echo "   workstation ui [ssh|agents] -> Bring 120Hz Workstation UI (or panel) to front"
    echo "   melcat-gc                   -> Clean caches & create zstd backups"
    echo "============================================================"
}

case "${1:-ui}" in
    ui|--ui)
        open_ui "${2:-}"
        ;;
    setup|--setup)
        install_real_agy
        echo "[workstation] Installing developer toolchain (git, openssh, zstd, zsh, tmux, ripgrep, jq)..."
        export DEBIAN_FRONTEND=noninteractive
        pkg install -y -o Dpkg::Options::=--force-confold git openssh zstd zsh tmux ripgrep jq
        echo "[workstation] Ready! Run 'agy' for Antigravity CLI, or 'workstation install-opencode' for OpenCode v2."
        ;;
    install-agy|reinstall-agy)
        install_real_agy
        ;;
    install-opencode|reinstall-opencode)
        install_native_opencode
        ;;
    install-ssh|install-openssh)
        install_openssh
        ;;
    upgrade|--upgrade)
        echo "[workstation] Upgrading Termux packages (including OpenCode if installed)..."
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
        echo "  -> symlinked to ~/.gemini/antigravity-cli/skills, ~/.config/opencode/skills & ~/.claude/skills"
        ;;
    token|--token)
        if TF="$(find_active_token 2>/dev/null)"; then
            MODE="$(stat -c '%a' "$TF" 2>/dev/null || echo '600')"
            echo "[workstation] Antigravity OAuth ACTIVE at $TF (mode: 0$MODE)"
        else
            echo "[workstation] Antigravity OAuth NOT set. Run 'agy' to sign in with Google."
        fi
        for prov in openrouter mistral; do
            KF="$VAULT_DIR/$prov.key"
            if [ -s "$KF" ]; then
                echo "[workstation] Coding Vault ($prov): ACTIVE (mode: 0$(stat -c '%a' "$KF" 2>/dev/null || echo '600'))"
            fi
        done
        if [ -s "$VAULT_DIR/ollama.url" ]; then
            echo "[workstation] Ollama LAN endpoint: $(cat "$VAULT_DIR/ollama.url")"
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
        if [ -x "$PREFIX/bin/opencode" ]; then
            OCVER="$("$PREFIX/bin/opencode" --version 2>/dev/null || echo 'error executing')"
            echo "  [ok]      opencode ($OCVER, native bionic)"
        else
            echo "  [opt]     opencode (OpenCode v2) -> run 'workstation install-opencode'"
        fi
        if [ -x "$PREFIX/bin/ssh" ]; then
            SSHVER="$("$PREFIX/bin/ssh" -V 2>&1 | head -n 1 || echo 'ready')"
            echo "  [ok]      openssh ($SSHVER)"
        else
            echo "  [opt]     openssh (SSH & Tailscale) -> run 'workstation install-ssh'"
        fi
        if [ -s "$HOME_DIR/.config/opencode/cli.json" ]; then
            echo "  [ok]      opencode cli.json (Tab agent.cycle keybind)"
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
            echo "  [ok]      Antigravity OAuth ($TF, mode 0$(stat -c '%a' "$TF" 2>/dev/null || echo '600'))"
        else
            echo "  [warn]    Antigravity not signed in -> run 'agy'"
        fi
        for prov in openrouter mistral; do
            KF="$VAULT_DIR/$prov.key"
            if [ -s "$KF" ]; then
                echo "  [ok]      Coding Vault $prov (mode 0$(stat -c '%a' "$KF" 2>/dev/null || echo '600'))"
            fi
        done
        if [ -s "$VAULT_DIR/ollama.url" ]; then
            echo "  [ok]      Ollama LAN ($(cat "$VAULT_DIR/ollama.url"))"
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

# 7. Configure shell profiles (~/.bashrc and ~/.zshrc) cleanly for real Antigravity CLI, OpenCode & 0600 vault env
MARKER="# >>> TERMINAL-AGENTS-BUILDER >>>"
VAULT_MARKER="# >>> TERMINAL-AGENTS-VAULT-ENV >>>"
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
    if ! grep -q "$VAULT_MARKER" "$PROFILE_RC"; then
        cat >> "$PROFILE_RC" <<'EOF'

# >>> TERMINAL-AGENTS-VAULT-ENV >>>
if [ -r "$HOME/.config/melcat/vault/providers.env" ]; then
    . "$HOME/.config/melcat/vault/providers.env"
fi
# <<< TERMINAL-AGENTS-VAULT-ENV <<<
EOF
    fi
done

date -u +"%Y-%m-%dT%H:%M:%SZ" > "$STAMP_FILE"
chmod 0600 "$STAMP_FILE"
