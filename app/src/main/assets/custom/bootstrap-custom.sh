#!/data/data/com.termux/files/usr/bin/sh
# bootstrap-custom.sh — Idempotent post-bootstrap provisioner for Terminal Agents Builder (RUR Edition)
# Runs strictly AFTER TermuxInstaller atomic rename ($STAGING_PREFIX -> $PREFIX).

set -eu

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
STAMP_FILE="$HOME_DIR/.config/melcat/.bootstrap_provisioned_v13"

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

# 2. Seed core active skills ONLY if absent (never overwrites agent/user edits!)
mkdir -p "$HOME_DIR/.gemini/config/skills/senior-auditor" \
         "$HOME_DIR/.gemini/config/skills/motion-framer" \
         "$HOME_DIR/.gemini/config/skills/caveman" \
         "$HOME_DIR/.gemini/config/skills/rur-ros2-rassor"

if [ ! -f "$HOME_DIR/.gemini/config/skills/senior-auditor/SKILL.md" ]; then
    cat > "$HOME_DIR/.gemini/config/skills/senior-auditor/SKILL.md" <<'EOF'
---
name: senior-auditor
description: Ruthless security, concurrency, injection, and architecture code auditor (P0/P1/P2)
---
# Senior Auditor
Perform a strict production audit prioritized as P0 (Critical), P1 (High), and P2 (Medium) with exact file paths, line numbers, root causes, and concrete diffs.
Always verify: command/option injection, file permissions (0600), race conditions, resource leaks, and backward compatibility.
EOF
fi

if [ ! -f "$HOME_DIR/.gemini/config/skills/motion-framer/SKILL.md" ]; then
    cat > "$HOME_DIR/.gemini/config/skills/motion-framer/SKILL.md" <<'EOF'
---
name: motion-framer
description: High-craft 120Hz mobile/tablet UI & tactile motion designer
---
# Motion Framer
Design responsive interfaces with clean visual hierarchy, crisp spacing, WindowInsets/IME safety on 360dp phones and tablets, and smooth spring micro-interactions without layout shifts or clipped labels.
EOF
fi

if [ ! -f "$HOME_DIR/.gemini/config/skills/caveman/SKILL.md" ]; then
    cat > "$HOME_DIR/.gemini/config/skills/caveman/SKILL.md" <<'EOF'
---
name: caveman
description: Ultra-concise zero-fluff technical response mode for fast mobile terminal reading
---
# Caveman Mode
Keep explanations ultra-brief, direct, and high-signal for narrow smartphone screens. Skip filler prose; output exact commands, root causes, and diffs first.
EOF
fi

if [ ! -f "$HOME_DIR/.gemini/config/skills/rur-ros2-rassor/SKILL.md" ]; then
    cat > "$HOME_DIR/.gemini/config/skills/rur-ros2-rassor/SKILL.md" <<'EOF'
---
name: rur-ros2-rassor
description: RUR (UNIPOLI) Rover RASSOR ROS 2 Jazzy offboard_ws + LiveKit WebRTC ground-station assistant
---
# RUR Rover RASSOR — ROS 2 Jazzy (offboard_ws)
Context for diagnosing, building, and editing `rover_rassor_project-/offboard_ws` inside Ubuntu 24.04 (`proot-distro`) on ARM64:
- **ROS 2 Distro & Middleware:** ROS 2 Jazzy (`/opt/ros/jazzy/setup.bash`), `RMW_IMPLEMENTATION=rmw_cyclonedds_cpp`, default `ROS_DOMAIN_ID=42`.
- **LiveKit Prerequisite:** Rover sensor topics (`PointCloud2`, RGB, LZ4 depth, IMU, movement) arrive over **LiveKit Cloud WebRTC**, NOT DDS over Tailscale. One of the LiveKit receiver scripts (`livekit_receiver.launch.py`, `livekit_agent`, or `robot_receiver_prod.py`) MUST be running in another console tab before `ros2 topic list`, `slam_toolbox`, `nav2`, or `foxglove_bridge` will see rover topics.
- **Headless 3D Visualization:** Avoid heavy X11 RViz2 inside Android `proot`; use `foxglove_bridge` (`ws://127.0.0.1:8765`) for WebGL 3D inspection.
- **Packages (`offboard_ws/src`):** `livekit_agent`, `rover_rassor_bringup`, `rover_rassor_description`, `rover_rassor_mapping`, `rover_rassor_navigation`, `rover_rassor_planning` (C++ A* / Dijkstra).
- **Security:** Never print or commit `LIVEKIT_TOKEN`, `.env`, or `~/.config/melcat/vault/*`.
EOF
fi

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
cat > "$PREFIX/bin/termux-open-url.tmp.$$" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
URL="${1:-}"
if [ -z "$URL" ]; then
    echo "Usage: termux-open-url <url>" >&2
    exit 1
fi
am start --user 0 -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1 || \
am start -a android.intent.action.VIEW -d "$URL" >/dev/null 2>&1
EOF
chmod 0700 "$PREFIX/bin/termux-open-url.tmp.$$"
mv -f "$PREFIX/bin/termux-open-url.tmp.$$" "$PREFIX/bin/termux-open-url"
ln -sf "$PREFIX/bin/termux-open-url" "$PREFIX/bin/xdg-open"

# 4. Install melcat-gc (Storage & Cache Optimizer + zstd snapshot tool, strictly excluding OAuth, API vaults & git configs)
cat > "$PREFIX/bin/melcat-gc.tmp.$$" <<'EOF'
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
            --exclude='.config/melcat/rur-ops.json*' \
            --exclude='.cache/melcat-rur*' \
            --exclude='*id_ed25519*' --exclude='*id_rsa*' --exclude='*.pem' \
            --exclude='*/.git/config' \
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
            --exclude='.config/melcat/rur-ops.json*' \
            --exclude='.cache/melcat-rur*' \
            --exclude='*id_ed25519*' --exclude='*id_rsa*' --exclude='*.pem' \
            --exclude='*/.git/config' \
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
chmod 0700 "$PREFIX/bin/melcat-gc.tmp.$$"
mv -f "$PREFIX/bin/melcat-gc.tmp.$$" "$PREFIX/bin/melcat-gc"

# 5. Install `workstation` controller CLI (keeps `$PREFIX/bin/agy` and `$PREFIX/bin/opencode` dedicated to real binaries)
cat > "$PREFIX/bin/workstation.tmp.$$" <<'EOF'
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

    mkdir -p "$HOME_DIR/.config/opencode" "$SKILLS_DIR"
    if [ ! -L "$HOME_DIR/.config/opencode/skills" ]; then
        if [ -d "$HOME_DIR/.config/opencode/skills" ]; then
            cp -rn "$HOME_DIR/.config/opencode/skills/." "$SKILLS_DIR/" 2>/dev/null || \
                cp -r "$HOME_DIR/.config/opencode/skills/." "$SKILLS_DIR/" 2>/dev/null || true
            rm -rf "$HOME_DIR/.config/opencode/skills"
        elif [ -e "$HOME_DIR/.config/opencode/skills" ]; then
            mv -f "$HOME_DIR/.config/opencode/skills" "$HOME_DIR/.config/opencode/skills.bak.$(date +%s)" 2>/dev/null || \
                rm -f "$HOME_DIR/.config/opencode/skills"
        fi
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

validate_git_url() {
    RAW="$1"
    [ -n "$RAW" ] || return 1
    [ "${#RAW}" -le 240 ] || return 1
    [ "$(printf '%s\n' "$RAW" | wc -l | tr -d ' ')" -eq 1 ] || return 1
    case "$RAW" in
        -*|*[[:cntrl:]]*|*" "*|*"	"*|*".."*|*"$"*|*";"*|*"|"*|*"&"*|*"\`"*|ext::*|file:*)
            return 1
            ;;
        https://*/*|ssh://*/*|*@*:*)
            printf '%s\n' "$RAW" | grep -Eq '^(https|ssh)://([A-Za-z0-9._-]+@)?[A-Za-z0-9][A-Za-z0-9.-]{0,252}(:[0-9]{1,5})?/[A-Za-z0-9._~][A-Za-z0-9._~/-]{0,199}$|^[A-Za-z0-9._-]+@[A-Za-z0-9][A-Za-z0-9.-]{0,252}:[A-Za-z0-9._][A-Za-z0-9._/-]{1,199}$'
            return $?
            ;;
        *)
            return 1
            ;;
    esac
}

validate_skill_name() {
    RAW="$1"
    [ -n "$RAW" ] || return 1
    [ "$(printf '%s\n' "$RAW" | wc -l | tr -d ' ')" -eq 1 ] || return 1
    case "$RAW" in
        -*|*[[:cntrl:]]*|*/*|*\\*|*..*|*" "*|*"	"*)
            return 1
            ;;
        *)
            printf '%s\n' "$RAW" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$'
            return $?
            ;;
    esac
}

require_git() {
    if ! command -v git >/dev/null 2>&1; then
        echo "[skills] git is not installed. Run 'pkg install -y git' or 'workstation setup'." >&2
        return 1
    fi
}

ensure_git_identity() {
    REPO_DIR="$1"
    if [ -z "$(git -C "$REPO_DIR" config user.name 2>/dev/null || true)" ]; then
        git -C "$REPO_DIR" config user.name "RUR Workstation"
    fi
    if [ -z "$(git -C "$REPO_DIR" config user.email 2>/dev/null || true)" ]; then
        git -C "$REPO_DIR" config user.email "workstation@rur.local"
    fi
}

# Keep cloned sub-repos excluded from root ~/.gemini/config/skills/.git so they never create broken 160000 gitlinks
sync_root_subrepo_excludes() {
    [ -d "$SKILLS_DIR/.git" ] || return 0
    EXCLUDE_FILE="$SKILLS_DIR/.git/info/exclude"
    mkdir -p "$SKILLS_DIR/.git/info"
    TMP_EX="$EXCLUDE_FILE.tmp.$$"
    if [ -f "$EXCLUDE_FILE" ]; then
        awk '
            /^# >>> RUR-SUBREPOS >>>$/ { skip=1; next }
            /^# <<< RUR-SUBREPOS <<<$/ { skip=0; next }
            !skip { print }
        ' "$EXCLUDE_FILE" > "$TMP_EX"
    else
        : > "$TMP_EX"
    fi
    printf '# >>> RUR-SUBREPOS >>>\n' >> "$TMP_EX"
    for sub in "$SKILLS_DIR"/*; do
        if [ -d "$sub/.git" ]; then
            printf '/%s/\n' "$(basename -- "$sub")" >> "$TMP_EX"
        fi
    done
    printf '# <<< RUR-SUBREPOS <<<\n' >> "$TMP_EX"
    mv -f "$TMP_EX" "$EXCLUDE_FILE"
}

update_single_skill_repo() {
    REPO_DIR="$1"
    REPO_LABEL="$2"
    if ! git -C "$REPO_DIR" remote get-url origin >/dev/null 2>&1; then
        echo "  [skip]    $REPO_LABEL (git repo without 'origin' remote)"
        return 0
    fi
    BRANCH="$(git -C "$REPO_DIR" symbolic-ref --short -q HEAD 2>/dev/null || true)"
    if [ -z "$BRANCH" ]; then
        echo "  [skip]    $REPO_LABEL (detached HEAD — switch to a branch to sync)" >&2
        return 0
    fi
    echo "  [sync]    $REPO_LABEL ($BRANCH) ..."
    git -C "$REPO_DIR" fetch --prune origin 2>&1 || {
        echo "  [warn]    $REPO_LABEL: fetch failed (offline or auth required)" >&2
        return 0
    }

    # Unborn HEAD (fresh `workstation skills init <url>` on Device 2 syncing an existing remote repo)
    if ! git -C "$REPO_DIR" rev-parse --verify HEAD >/dev/null 2>&1; then
        TARGET_BRANCH="$BRANCH"
        if ! git -C "$REPO_DIR" rev-parse --verify "refs/remotes/origin/$TARGET_BRANCH" >/dev/null 2>&1; then
            REMOTE_SYM="$(git -C "$REPO_DIR" symbolic-ref --short -q refs/remotes/origin/HEAD 2>/dev/null || true)"
            if [ -n "$REMOTE_SYM" ] && git -C "$REPO_DIR" rev-parse --verify "refs/remotes/$REMOTE_SYM" >/dev/null 2>&1; then
                TARGET_BRANCH="${REMOTE_SYM#origin/}"
            elif git -C "$REPO_DIR" rev-parse --verify "refs/remotes/origin/main" >/dev/null 2>&1; then
                TARGET_BRANCH="main"
            elif git -C "$REPO_DIR" rev-parse --verify "refs/remotes/origin/master" >/dev/null 2>&1; then
                TARGET_BRANCH="master"
            else
                echo "  [skip]    $REPO_LABEL: remote branch origin/$BRANCH not found yet (run 'workstation skills push' first)"
                return 0
            fi
        fi

        # Move any colliding untracked local seed files into a safe backup dir before initial checkout
        BACKUP_DIR=""
        git -C "$REPO_DIR" ls-tree -r --name-only "refs/remotes/origin/$TARGET_BRANCH" | while IFS= read -r remote_rel; do
            [ -n "$remote_rel" ] || continue
            if [ -e "$REPO_DIR/$remote_rel" ]; then
                if [ -z "$BACKUP_DIR" ]; then
                    BACKUP_DIR="$HOME_DIR/.config/melcat/skills-pre-sync-$(date +%Y%m%d-%H%M%S)"
                    mkdir -p "$BACKUP_DIR"
                    echo "  [backup]  Backing up local seed collisions to $BACKUP_DIR"
                fi
                DEST_BAK="$BACKUP_DIR/$remote_rel"
                mkdir -p "$(dirname -- "$DEST_BAK")"
                mv -f -- "$REPO_DIR/$remote_rel" "$DEST_BAK"
            fi
        done

        if git -C "$REPO_DIR" checkout -B "$TARGET_BRANCH" "refs/remotes/origin/$TARGET_BRANCH" 2>&1; then
            echo "  [ok]      $REPO_LABEL initialized from origin/$TARGET_BRANCH"
        else
            echo "  [warn]    $REPO_LABEL: initial sync failed — local files preserved" >&2
        fi
        return 0
    fi

    if ! git -C "$REPO_DIR" rev-parse --verify "refs/remotes/origin/$BRANCH" >/dev/null 2>&1; then
        echo "  [skip]    $REPO_LABEL: remote branch origin/$BRANCH not found yet (run 'workstation skills push' first)"
        return 0
    fi

    ensure_git_identity "$REPO_DIR"
    PREV_HEAD="$(git -C "$REPO_DIR" rev-parse HEAD)"
    STASH_BEFORE="$(git -C "$REPO_DIR" rev-parse -q --verify refs/stash 2>/dev/null || true)"
    DID_STASH=0
    if [ -n "$(git -C "$REPO_DIR" status --porcelain 2>/dev/null || true)" ]; then
        if ! git -C "$REPO_DIR" stash push --include-untracked -m "rur-skills-autostash" >/dev/null 2>&1; then
            echo "  [warn]    $REPO_LABEL: could not autostash local edits — skipping update safely." >&2
            return 0
        fi
        STASH_AFTER="$(git -C "$REPO_DIR" rev-parse -q --verify refs/stash 2>/dev/null || true)"
        if [ -n "$STASH_AFTER" ] && [ "$STASH_AFTER" != "$STASH_BEFORE" ]; then
            DID_STASH=1
        fi
    fi

    if ! git -C "$REPO_DIR" rebase "refs/remotes/origin/$BRANCH" 2>&1; then
        git -C "$REPO_DIR" rebase --abort >/dev/null 2>&1 || true
        if [ "$DID_STASH" -eq 1 ]; then
            if ! git -C "$REPO_DIR" stash pop --quiet 2>/dev/null; then
                echo "  [warn]    $REPO_LABEL: rebase aborted; local edits remain safely in 'git stash list' (rur-skills-autostash)." >&2
                return 0
            fi
        fi
        echo "  [conflict] $REPO_LABEL: rebase aborted cleanly — your local agent edits are untouched. Run 'workstation skills diff'." >&2
        return 0
    fi

    if [ "$DID_STASH" -eq 1 ]; then
        if git -C "$REPO_DIR" stash apply --quiet 2>/dev/null && [ -z "$(git -C "$REPO_DIR" ls-files -u 2>/dev/null || true)" ]; then
            git -C "$REPO_DIR" stash drop --quiet >/dev/null 2>&1 || true
            echo "  [ok]      $REPO_LABEL updated (local agent edits preserved)"
        else
            git -C "$REPO_DIR" reset --hard "$PREV_HEAD" >/dev/null 2>&1 || true
            # Clean exact untracked paths unpacked by failed stash apply before restoring stash@{0}
            if git -C "$REPO_DIR" rev-parse -q --verify 'stash@{0}^3' >/dev/null 2>&1; then
                git -C "$REPO_DIR" ls-tree -r --name-only 'stash@{0}^3' | while IFS= read -r rel_path; do
                    [ -n "$rel_path" ] && rm -f -- "$REPO_DIR/$rel_path"
                done
            fi
            if git -C "$REPO_DIR" stash pop --quiet 2>/dev/null; then
                echo "  [conflict] $REPO_LABEL: local agent edits conflict with upstream — rolled back cleanly (zero conflict markers). Run 'workstation skills diff' or push first." >&2
            else
                echo "  [warn]    $REPO_LABEL: rolled back to $PREV_HEAD; your local edits are safely preserved in 'git stash list' (rur-skills-autostash)." >&2
            fi
        fi
    else
        echo "  [ok]      $REPO_LABEL updated"
    fi
}

push_single_skill_repo() {
    REPO_DIR="$1"
    REPO_LABEL="$2"
    COMMIT_MSG="$3"
    if ! git -C "$REPO_DIR" remote get-url origin >/dev/null 2>&1; then
        return 0
    fi
    BRANCH="$(git -C "$REPO_DIR" symbolic-ref --short -q HEAD 2>/dev/null || true)"
    if [ -z "$BRANCH" ]; then
        echo "  [skip]    $REPO_LABEL (detached HEAD — checkout a branch before pushing)" >&2
        return 0
    fi
    STATUS="$(git -C "$REPO_DIR" status --porcelain 2>/dev/null || true)"
    if [ -z "$STATUS" ]; then
        echo "  [clean]   $REPO_LABEL (nothing new to commit)"
    else
        ensure_git_identity "$REPO_DIR"
        git -C "$REPO_DIR" add -A
        if ! git -C "$REPO_DIR" diff --cached --quiet 2>/dev/null; then
            if ! git -C "$REPO_DIR" commit -m "$COMMIT_MSG"; then
                echo "  [warn]    $REPO_LABEL: commit failed — skipping push" >&2
                return 0
            fi
        fi
    fi
    if ! git -C "$REPO_DIR" rev-parse --verify HEAD >/dev/null 2>&1; then
        echo "  [skip]    $REPO_LABEL (no commits to push yet)"
        return 0
    fi
    if git -C "$REPO_DIR" push -u origin -- "refs/heads/$BRANCH:refs/heads/$BRANCH" 2>&1; then
        echo "  [pushed]  $REPO_LABEL -> origin/$BRANCH"
    else
        echo "  [warn]    $REPO_LABEL: push failed (verify git/SSH credentials or remote URL)" >&2
    fi
}

manage_skills() {
    SUB="${1:-list}"
    shift 2>/dev/null || true
    mkdir -p "$SKILLS_DIR"

    case "$SUB" in
        list|ls|status)
            sync_root_subrepo_excludes
            echo "[skills] Unified Skills Hub: $SKILLS_DIR"
            echo "         Symlinked to: agy (~/.gemini/antigravity-cli/skills), opencode (~/.config/opencode/skills), claude (~/.claude/skills)"
            if [ -d "$SKILLS_DIR/.git" ] && command -v git >/dev/null 2>&1; then
                RB="$(git -C "$SKILLS_DIR" symbolic-ref --short -q HEAD 2>/dev/null || echo 'detached')"
                RR="$(git -C "$SKILLS_DIR" remote get-url origin 2>/dev/null || echo 'no-remote')"
                RD="$(git -C "$SKILLS_DIR" --no-optional-locks status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
                echo "         Root Git Repo: branch=$RB · remote=$RR · pending=$RD"
            fi
            echo "------------------------------------------------------------"
            FOUND=0
            for d in "$SKILLS_DIR"/*; do
                [ -d "$d" ] || continue
                NAME="$(basename -- "$d")"
                FOUND=1
                if [ -d "$d/.git" ] && command -v git >/dev/null 2>&1; then
                    BR="$(git -C "$d" symbolic-ref --short -q HEAD 2>/dev/null || echo 'detached')"
                    DIRTY="$(git -C "$d" --no-optional-locks status --porcelain 2>/dev/null | wc -l | tr -d ' ')"
                    if [ "$DIRTY" != "0" ]; then
                        echo "  [git* ] $NAME (branch: $BR, $DIRTY local change(s))"
                    else
                        echo "  [git  ] $NAME (branch: $BR, clean)"
                    fi
                else
                    echo "  [local] $NAME"
                fi
            done
            if [ "$FOUND" -eq 0 ]; then
                echo "  (no skills installed yet)"
            fi
            ;;
        init)
            require_git
            REMOTE_URL="${1:-}"
            if [ -n "$REMOTE_URL" ] && ! validate_git_url "$REMOTE_URL"; then
                echo "[skills] ERROR: Invalid Git URL: $REMOTE_URL" >&2
                return 1
            fi
            if [ ! -d "$SKILLS_DIR/.git" ]; then
                git -C "$SKILLS_DIR" init -b main 2>/dev/null || git -C "$SKILLS_DIR" init
                if [ ! -f "$SKILLS_DIR/.gitignore" ]; then
                    printf '.DS_Store\n*.tmp\n*.bak.*\n' > "$SKILLS_DIR/.gitignore"
                fi
                echo "[skills] Initialized Git skills repository in $SKILLS_DIR"
            fi
            sync_root_subrepo_excludes
            if [ -n "$REMOTE_URL" ]; then
                if git -C "$SKILLS_DIR" remote get-url origin >/dev/null 2>&1; then
                    git -C "$SKILLS_DIR" remote set-url -- origin "$REMOTE_URL"
                else
                    git -C "$SKILLS_DIR" remote add -- origin "$REMOTE_URL"
                fi
                echo "[skills] Connected origin -> $REMOTE_URL"
                echo "[skills] Run 'workstation skills push' to back up your skills, or 'workstation skills update' to pull."
            else
                echo "[skills] Local Git initialized. Connect a remote anytime with: workstation skills init <git-url>"
            fi
            ;;
        add|clone)
            require_git
            URL="${1:-}"
            CUSTOM_NAME="${2:-}"
            if ! validate_git_url "$URL"; then
                echo "[skills] ERROR: Provide a valid remote Git URL (https://, ssh://, or git@host:org/repo.git)." >&2
                return 1
            fi
            if [ -n "$CUSTOM_NAME" ]; then
                NAME="$CUSTOM_NAME"
            else
                TRIMMED_URL="${URL%/}"
                REPO_TAIL="${TRIMMED_URL##*:}"
                NAME="$(basename -- "$REPO_TAIL" .git)"
            fi
            if ! validate_skill_name "$NAME"; then
                echo "[skills] ERROR: Invalid skill folder name: $NAME" >&2
                return 1
            fi
            DEST="$SKILLS_DIR/$NAME"
            if [ -e "$DEST" ]; then
                echo "[skills] ERROR: '$DEST' already exists. Run 'workstation skills update' or choose a different name." >&2
                return 1
            fi
            echo "[skills] Cloning skill repository into $DEST ..."
            git clone --depth=1 -- "$URL" "$DEST"
            sync_root_subrepo_excludes
            echo "[skills] Ready! '$NAME' is now active across agy, opencode, and claude."
            ;;
        update|pull|sync)
            require_git
            sync_root_subrepo_excludes
            echo "[skills] Updating Git skill repositories (rebase + autostash preserves local agent edits)..."
            ANY=0
            if [ -d "$SKILLS_DIR/.git" ]; then
                ANY=1
                update_single_skill_repo "$SKILLS_DIR" "root ($SKILLS_DIR)"
            fi
            for d in "$SKILLS_DIR"/*; do
                if [ -d "$d/.git" ]; then
                    ANY=1
                    update_single_skill_repo "$d" "$(basename -- "$d")"
                fi
            done
            if [ "$ANY" -eq 0 ]; then
                echo "[skills] No Git skill repos configured yet."
                echo "         Add one with: workstation skills add <git-url>"
                echo "         Or track all your local skills in GitHub: workstation skills init <your-repo-url>"
            fi
            ;;
        diff|changes)
            require_git
            sync_root_subrepo_excludes
            ANY=0
            if [ -d "$SKILLS_DIR/.git" ]; then
                ANY=1
                echo "=== root ($SKILLS_DIR) ==="
                git -C "$SKILLS_DIR" status -s
                git -C "$SKILLS_DIR" diff
            fi
            for d in "$SKILLS_DIR"/*; do
                if [ -d "$d/.git" ]; then
                    ANY=1
                    echo "=== $(basename -- "$d") ==="
                    git -C "$d" status -s
                    git -C "$d" diff
                fi
            done
            if [ "$ANY" -eq 0 ]; then
                echo "[skills] No Git skill repos yet. Run 'workstation skills init <git-url>' to start tracking AI skill edits."
            fi
            ;;
        push|save)
            require_git
            sync_root_subrepo_excludes
            MSG="${1:-chore(skills): sync agent skill improvements from RUR workstation}"
            ANY=0
            if [ -d "$SKILLS_DIR/.git" ] && git -C "$SKILLS_DIR" remote get-url origin >/dev/null 2>&1; then
                ANY=1
                push_single_skill_repo "$SKILLS_DIR" "root ($SKILLS_DIR)" "$MSG"
            fi
            for d in "$SKILLS_DIR"/*; do
                if [ -d "$d/.git" ] && git -C "$d" remote get-url origin >/dev/null 2>&1; then
                    ANY=1
                    push_single_skill_repo "$d" "$(basename -- "$d")" "$MSG"
                fi
            done
            if [ "$ANY" -eq 0 ]; then
                echo "[skills] No Git remote ('origin') configured yet."
                echo "         Connect your own skills repo first: workstation skills init <your-github-repo-url>"
            fi
            ;;
        *)
            echo "Usage: workstation skills [list | init [git-url] | add <git-url> [name] | update | diff | push [msg]]" >&2
            return 2
            ;;
    esac
}

find_ubuntu_rootfs() {
    if [ -x "$PREFIX/var/lib/proot-distro/containers/ubuntu/rootfs/bin/bash" ]; then
        printf '%s' "$PREFIX/var/lib/proot-distro/containers/ubuntu/rootfs"
    elif [ -x "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu/bin/bash" ]; then
        printf '%s' "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"
    else
        printf '%s' ""
    fi
}

install_ros2_ubuntu() {
    echo "[workstation] Instalando Ubuntu 24.04 LTS (noble) + ROS 2 Jazzy (offboard_ws)..."
    export DEBIAN_FRONTEND=noninteractive
    pkg update -y
    pkg install -y -o Dpkg::Options::=--force-confold proot-distro curl ca-certificates git

    UBUNTU_ROOT="$(find_ubuntu_rootfs)"
    if [ -n "$UBUNTU_ROOT" ]; then
        CUR_CODENAME="$(sed -n 's/^UBUNTU_CODENAME=//p' "$UBUNTU_ROOT/etc/os-release" 2>/dev/null | tr -d '"' | head -n 1)"
        if [ "$CUR_CODENAME" != "noble" ]; then
            echo "[workstation] Detectado Ubuntu '${CUR_CODENAME:-desconocido}' — ROS 2 Jazzy requiere Ubuntu 24.04 LTS ('noble')."
            echo "[workstation] Reemplazando contenedor 'ubuntu' por ubuntu:24.04..."
            proot-distro remove ubuntu || true
            UBUNTU_ROOT=""
        fi
    fi

    if [ -z "$UBUNTU_ROOT" ]; then
        echo "[workstation] Descargando Ubuntu 24.04 LTS (noble) ARM64..."
        if ! proot-distro install --override-alias ubuntu ubuntu:24.04; then
            echo "[workstation] Reintentando con alias legacy ubuntu-oldlts..."
            proot-distro install --override-alias ubuntu ubuntu-oldlts || proot-distro install ubuntu
        fi
        UBUNTU_ROOT="$(find_ubuntu_rootfs)"
    else
        echo "[workstation] Ubuntu 24.04 (noble) ya existe en proot-distro — verificando ROS 2 Jazzy..."
    fi

    if [ -z "$UBUNTU_ROOT" ]; then
        echo "[workstation] ERROR: No se encontró el rootfs de Ubuntu tras proot-distro install." >&2
        return 1
    fi

    echo "[workstation] Configurando repositorio oficial ROS 2 Jazzy (Ubuntu 24.04 noble, signed-by GPG) + paquetes offboard_ws..."
    proot-distro login ubuntu \
        --bind "$HOME_DIR:$HOME_DIR" \
        --bind "$PREFIX:$PREFIX" \
        -- /bin/bash -ec '
            set -euo pipefail
            export DEBIAN_FRONTEND=noninteractive
            apt-get update
            apt-get install -y --no-install-recommends \
                locales curl gnupg2 lsb-release ca-certificates software-properties-common \
                git build-essential python3-pip python3-venv python3-lz4
            locale-gen en_US en_US.UTF-8 || true
            update-locale LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 || true
            add-apt-repository -y universe || true

            mkdir -p /usr/share/keyrings
            curl -fsSL https://raw.githubusercontent.com/ros/rosdistro/master/ros.key \
                -o /usr/share/keyrings/ros-archive-keyring.gpg
            chmod 0644 /usr/share/keyrings/ros-archive-keyring.gpg

            ARCH="$(dpkg --print-architecture)"
            CODENAME="$(. /etc/os-release && printf "%s" "${UBUNTU_CODENAME:-noble}")"
            echo "[workstation] Ubuntu detectado: codename=$CODENAME arch=$ARCH"
            if [ "$CODENAME" != "noble" ]; then
                echo "[workstation] ERROR: Se esperaba Ubuntu 24.04 (noble) para ROS 2 Jazzy, pero se encontró: $CODENAME" >&2
                exit 1
            fi
            printf "deb [arch=%s signed-by=/usr/share/keyrings/ros-archive-keyring.gpg] http://packages.ros.org/ros2/ubuntu %s main\n" \
                "$ARCH" "$CODENAME" > /etc/apt/sources.list.d/ros2.list

            apt-get update
            apt-get install -y --no-install-recommends \
                ros-jazzy-ros-base \
                ros-jazzy-rmw-cyclonedds-cpp \
                python3-colcon-common-extensions \
                python3-rosdep

            echo "[workstation] Instalando stack offboard (SLAM, Nav2, Depth->LaserScan, Foxglove)..."
            apt-get install -y --no-install-recommends \
                ros-jazzy-depthimage-to-laserscan \
                ros-jazzy-slam-toolbox \
                ros-jazzy-navigation2 \
                ros-jazzy-nav2-bringup \
                ros-jazzy-foxglove-bridge || true

            echo "[workstation] Instalando SDK Python de LiveKit (best-effort)..."
            pip3 install --break-system-packages --upgrade livekit livekit-api python-dotenv lz4 2>/dev/null || true

            # Bridges to Termux native AI agents inside Ubuntu (without polluting LD_LIBRARY_PATH)
            printf "#!/bin/sh\nexport HOME=\"%s\"\nexport BROWSER=\"%s/bin/termux-open-url\"\nif [ -r \"\$HOME/.config/melcat/vault/providers.env\" ]; then . \"\$HOME/.config/melcat/vault/providers.env\"; fi\nexec \"%s/bin/agy\" \"\$@\"\n" \
                "$1" "$2" "$2" > /usr/local/bin/agy
            printf "#!/bin/sh\nexport HOME=\"%s\"\nexport BROWSER=\"%s/bin/termux-open-url\"\nif [ -r \"\$HOME/.config/melcat/vault/providers.env\" ]; then . \"\$HOME/.config/melcat/vault/providers.env\"; fi\nexec \"%s/bin/opencode\" \"\$@\"\n" \
                "$1" "$2" "$2" > /usr/local/bin/opencode
            chmod 0755 /usr/local/bin/agy /usr/local/bin/opencode

            printf "# RUR ROS 2 Jazzy defaults\nexport RMW_IMPLEMENTATION=rmw_cyclonedds_cpp\nexport ROS_DOMAIN_ID=\${ROS_DOMAIN_ID:-42}\n" \
                > /etc/profile.d/rur-ros2.sh
            chmod 0644 /etc/profile.d/rur-ros2.sh
            apt-get clean
        ' _ "$HOME_DIR" "$PREFIX"

    echo "[workstation] Ubuntu 24.04 + ROS 2 Jazzy + puentes agy/opencode LISTOS."
    echo "[workstation] Cambia al espacio 'ROS 2' en la barra superior o ejecuta 'workstation ros2'."
}

run_ros2_cli() {
    SUB="${1:-shell}"
    UBUNTU_ROOT="$(find_ubuntu_rootfs)"
    case "$SUB" in
        doctor|status)
            echo "[workstation] ROS 2 Jazzy Doctor:"
            if [ -x "$PREFIX/bin/proot-distro" ]; then
                echo "  [ok]      proot-distro"
            else
                echo "  [missing] proot-distro -> run 'workstation install-ros2'"
            fi
            if [ -n "$UBUNTU_ROOT" ] && [ -x "$UBUNTU_ROOT/bin/bash" ]; then
                echo "  [ok]      Ubuntu 24.04 rootfs ($UBUNTU_ROOT)"
            else
                echo "  [missing] Ubuntu 24.04 -> run 'workstation install-ros2'"
            fi
            if [ -n "$UBUNTU_ROOT" ] && [ -f "$UBUNTU_ROOT/opt/ros/jazzy/setup.bash" ]; then
                echo "  [ok]      ROS 2 Jazzy (/opt/ros/jazzy/setup.bash)"
            else
                echo "  [missing] ROS 2 Jazzy -> run 'workstation install-ros2'"
            fi
            if [ -n "$UBUNTU_ROOT" ] && [ -x "$UBUNTU_ROOT/usr/local/bin/agy" ] && [ -x "$UBUNTU_ROOT/usr/local/bin/opencode" ]; then
                echo "  [ok]      Ubuntu AI bridges (/usr/local/bin/agy, opencode)"
            else
                echo "  [opt]     Ubuntu AI bridges -> run 'workstation install-ros2'"
            fi
            ;;
        shell|"")
            if [ ! -x "$PREFIX/bin/proot-distro" ] || [ -z "$UBUNTU_ROOT" ] || [ ! -f "$UBUNTU_ROOT/opt/ros/jazzy/setup.bash" ]; then
                echo "[workstation] ROS 2 Jazzy no está instalado aún. Ejecuta 'workstation install-ros2'." >&2
                return 1
            fi
            exec "$PREFIX/bin/proot-distro" login ubuntu \
                --bind "$HOME_DIR:$HOME_DIR" \
                --bind "$PREFIX:$PREFIX" \
                -- /bin/bash -lc '
                    export HOME="$1"
                    export BROWSER="$2/bin/termux-open-url"
                    export ROS_DOMAIN_ID="${ROS_DOMAIN_ID:-42}"
                    export RMW_IMPLEMENTATION=rmw_cyclonedds_cpp
                    if [ -r "$HOME/.config/melcat/vault/providers.env" ]; then . "$HOME/.config/melcat/vault/providers.env"; fi
                    if [ -r /opt/ros/jazzy/setup.bash ]; then set +u 2>/dev/null || true; . /opt/ros/jazzy/setup.bash; fi
                    if [ -r "$HOME/RUR/rover_rassor_project-/offboard_ws/install/setup.bash" ]; then
                        . "$HOME/RUR/rover_rassor_project-/offboard_ws/install/setup.bash"
                    fi
                    if [ -d "$HOME/RUR/rover_rassor_project-/offboard_ws" ]; then
                        cd "$HOME/RUR/rover_rassor_project-/offboard_ws"
                    else
                        cd "$HOME"
                    fi
                    exec /bin/bash -i
                ' _ "$HOME_DIR" "$PREFIX"
            ;;
        *)
            echo "Usage: workstation ros2 [shell | doctor]" >&2
            return 2
            ;;
    esac
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
    UBUNTU_HELP_ROOT="$(find_ubuntu_rootfs)"
    if [ -n "$UBUNTU_HELP_ROOT" ] && [ -f "$UBUNTU_HELP_ROOT/opt/ros/jazzy/setup.bash" ]; then
        echo " ROS 2 Jazzy: INSTALLED (Ubuntu 24.04 proot-distro)"
    else
        echo " ROS 2 Jazzy: OPTIONAL (run 'workstation install-ros2')"
    fi
    echo " Skills Dir : $SKILLS_DIR ($(ls -1 "$SKILLS_DIR" 2>/dev/null | tr '\n' ' '))"
    if TF="$(find_active_token 2>/dev/null)"; then
        echo " OAuth Vault: ACTIVE ($TF, mode $(file_mode_str "$TF"))"
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
    echo "   workstation install-ros2    -> Install Ubuntu 24.04 + ROS 2 Jazzy + AI bridges"
    echo "   workstation ros2 [doctor]   -> Enter Ubuntu ROS 2 Jazzy shell or diagnose"
    echo "   workstation upgrade         -> Upgrade Termux packages (incl. opencode) + agy"
    echo "   workstation doctor          -> Diagnose agy, opencode, ssh, ros2, glibc & vaults"
    echo "   workstation skills [cmd]    -> Manage Git skill repos (list|init|add|update|diff|push)"
    echo "   workstation token           -> Check Antigravity OAuth & Coding Vault 0600"
    echo "   workstation ui [ros2|ssh]   -> Bring 120Hz Workstation UI (or space/panel) to front"
    echo "   melcat-gc                   -> Clean caches & create zstd backups"
    echo "============================================================"
}

file_mode_str() {
    M="$(stat -c '%a' "$1" 2>/dev/null || echo '')"
    case "$M" in
        [0-7][0-7][0-7]) printf '0%s' "$M" ;;
        [0-7][0-7][0-7][0-7]) printf '%s' "$M" ;;
        *) printf 'unknown' ;;
    esac
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
    install-ros2|reinstall-ros2)
        install_ros2_ubuntu
        ;;
    ros2|--ros2)
        shift
        run_ros2_cli "${1:-shell}"
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
        shift
        manage_skills "$@"
        ;;
    token|--token)
        if TF="$(find_active_token 2>/dev/null)"; then
            echo "[workstation] Antigravity OAuth ACTIVE at $TF (mode: $(file_mode_str "$TF"))"
        else
            echo "[workstation] Antigravity OAuth NOT set. Run 'agy' to sign in with Google."
        fi
        for prov in openrouter mistral deepseek anthropic openai; do
            KF="$VAULT_DIR/$prov.key"
            if [ -s "$KF" ]; then
                echo "[workstation] Coding Vault ($prov): ACTIVE (mode: $(file_mode_str "$KF"))"
            fi
        done
        if [ -s "$VAULT_DIR/ollama.url" ]; then
            echo "[workstation] Ollama LAN endpoint: $(cat "$VAULT_DIR/ollama.url")"
        fi
        if [ -s "$VAULT_DIR/rur-operator-session.json" ]; then
            echo "[workstation] RUR Operator Session: ACTIVE (mode: $(file_mode_str "$VAULT_DIR/rur-operator-session.json"))"
        fi
        if [ -s "$VAULT_DIR/rur-device.key" ]; then
            echo "[workstation] RUR Device Key: ACTIVE (mode: $(file_mode_str "$VAULT_DIR/rur-device.key"))"
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
        UBUNTU_DOC_ROOT="$(find_ubuntu_rootfs)"
        if [ -n "$UBUNTU_DOC_ROOT" ] && [ -f "$UBUNTU_DOC_ROOT/opt/ros/jazzy/setup.bash" ]; then
            echo "  [ok]      ROS 2 Jazzy (Ubuntu 24.04 proot-distro)"
        else
            echo "  [opt]     ROS 2 Jazzy -> run 'workstation install-ros2'"
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
            echo "  [ok]      Antigravity OAuth ($TF, mode $(file_mode_str "$TF"))"
        else
            echo "  [warn]    Antigravity not signed in -> run 'agy'"
        fi
        for prov in openrouter mistral deepseek anthropic openai; do
            KF="$VAULT_DIR/$prov.key"
            if [ -s "$KF" ]; then
                echo "  [ok]      Coding Vault $prov (mode $(file_mode_str "$KF"))"
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
chmod 0700 "$PREFIX/bin/workstation.tmp.$$"
mv -f "$PREFIX/bin/workstation.tmp.$$" "$PREFIX/bin/workstation"

# 6. Install first-run `agy` bootstrapper ONLY if the real native Antigravity CLI (`agy.va39`) is not installed yet.
#    Once installed, `$PREFIX/bin/agy` is the real C bootstrapper + `$PREFIX/bin/agy.va39` and is NEVER overwritten!
if [ ! -x "$PREFIX/bin/agy.va39" ]; then
    cat > "$PREFIX/bin/agy.tmp.$$" <<'EOF'
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
    chmod 0700 "$PREFIX/bin/agy.tmp.$$"
    mv -f "$PREFIX/bin/agy.tmp.$$" "$PREFIX/bin/agy"
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
