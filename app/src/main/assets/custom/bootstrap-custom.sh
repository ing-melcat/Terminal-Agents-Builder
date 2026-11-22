#!/data/data/com.termux/files/usr/bin/sh
# bootstrap-custom.sh — Idempotent post-bootstrap provisioner for Terminal Agents Builder
# Runs strictly AFTER TermuxInstaller atomic rename ($STAGING_PREFIX -> $PREFIX).

set -eu

PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
STAMP_FILE="$HOME_DIR/.config/melcat/.bootstrap_provisioned_v5"

export PATH="$PREFIX/bin:$PATH"
export HOME="$HOME_DIR"

mkdir -p "$HOME_DIR/.config/melcat"
mkdir -p "$HOME_DIR/.config/antigravity"
mkdir -p "$HOME_DIR/.gemini/config/skills"
mkdir -p "$HOME_DIR/.claude"
mkdir -p "$HOME_DIR/.local/share/melcat/logs"
mkdir -p "$HOME_DIR/.local/share/melcat/snapshots"
mkdir -p "$PREFIX/etc/melcat"

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

# 3. Install Android browser launcher bridge (termux-open-url & xdg-open) so Node/CLI OAuth opens Chrome natively
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

# 4. Install pure Node.js official Google OAuth 2.0 Web Loopback + PKCE authenticator for `agy login`
cat > "$PREFIX/etc/melcat/agy-oauth.js" <<'EOF'
#!/usr/bin/env node
"use strict";

const http = require("node:http");
const https = require("node:https");
const crypto = require("node:crypto");
const fs = require("node:fs");
const path = require("node:path");
const readline = require("node:readline");
const { spawnSync } = require("node:child_process");

const HOME_DIR = process.env.HOME || "/data/data/com.termux/files/home";
const GEMINI_DIR = path.join(HOME_DIR, ".gemini");
const AGY_DIR = path.join(HOME_DIR, ".config", "antigravity");
const OAUTH_CREDS_PATH = path.join(GEMINI_DIR, "oauth_creds.json");
const ACCOUNTS_PATH = path.join(GEMINI_DIR, "google_accounts.json");
const SETTINGS_PATH = path.join(GEMINI_DIR, "settings.json");
const TOKEN_PATHS = [
  path.join(AGY_DIR, "antigravity-oauth-token"),
  path.join(GEMINI_DIR, "antigravity-oauth-token"),
  "/data/data/com.termux/files/antigravity-oauth-token",
];

// Official Google OAuth 2.0 installed-app CLI credentials used by @google/gemini-cli
const CLIENT_ID = "681255809395-oo8ft2oprdrnp9e3aqf6av3hmdib135j.apps.googleusercontent.com";
const CLIENT_SECRET = "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";
const SCOPES = [
  "https://www.googleapis.com/auth/cloud-platform",
  "https://www.googleapis.com/auth/userinfo.email",
  "https://www.googleapis.com/auth/userinfo.profile",
].join(" ");

function base64Url(buf) {
  return buf.toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function writeMode0600(filePath, content) {
  const dir = path.dirname(filePath);
  fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
  try { fs.chmodSync(dir, 0o700); } catch (_) {}
  const tmp = `${filePath}.tmp.${process.pid}.${Date.now()}`;
  fs.writeFileSync(tmp, content, { mode: 0o600 });
  try { fs.chmodSync(tmp, 0o600); } catch (_) {}
  fs.renameSync(tmp, filePath);
  try { fs.chmodSync(filePath, 0o600); } catch (_) {}
}

function httpsRequest(urlStr, options, bodyStr) {
  return new Promise((resolve, reject) => {
    const req = https.request(urlStr, options, (res) => {
      let data = "";
      res.on("data", (chunk) => (data += chunk));
      res.on("end", () => resolve({ status: res.statusCode || 0, body: data }));
    });
    req.on("error", reject);
    if (bodyStr) req.write(bodyStr);
    req.end();
  });
}

async function exchangeCode(code, redirectUri, codeVerifier) {
  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    client_secret: CLIENT_SECRET,
    code,
    code_verifier: codeVerifier,
    grant_type: "authorization_code",
    redirect_uri: redirectUri,
  }).toString();

  const res = await httpsRequest(
    "https://oauth2.googleapis.com/token",
    {
      method: "POST",
      headers: {
        "Content-Type": "application/x-www-form-urlencoded",
        "Content-Length": Buffer.byteLength(params),
      },
    },
    params
  );
  if (res.status < 200 || res.status >= 300) {
    throw new Error(`Token exchange failed (HTTP ${res.status}): ${res.body}`);
  }
  return JSON.parse(res.body);
}

async function fetchEmail(accessToken) {
  if (!accessToken) return null;
  try {
    const res = await httpsRequest("https://www.googleapis.com/oauth2/v2/userinfo", {
      method: "GET",
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    if (res.status >= 200 && res.status < 300) {
      const info = JSON.parse(res.body);
      return info.email || null;
    }
  } catch (_) {}
  return null;
}

function saveCredentials(tokens, email) {
  const creds = {
    access_token: tokens.access_token,
    refresh_token: tokens.refresh_token,
    scope: tokens.scope || SCOPES,
    token_type: tokens.token_type || "Bearer",
    id_token: tokens.id_token,
    expiry_date: Date.now() + (Number(tokens.expires_in || 3600) * 1000),
  };
  writeMode0600(OAUTH_CREDS_PATH, JSON.stringify(creds, null, 2) + "\n");

  if (email) {
    writeMode0600(ACCOUNTS_PATH, JSON.stringify({ active: email, old: [] }, null, 2) + "\n");
  }

  let settings = {};
  try {
    if (fs.existsSync(SETTINGS_PATH)) {
      settings = JSON.parse(fs.readFileSync(SETTINGS_PATH, "utf8"));
    }
  } catch (_) {}
  settings.selectedAuthType = "oauth-personal";
  settings.security = settings.security || {};
  settings.security.auth = settings.security.auth || {};
  settings.security.auth.selectedType = "oauth-personal";
  writeMode0600(SETTINGS_PATH, JSON.stringify(settings, null, 2) + "\n");

  const primaryToken = (tokens.refresh_token || tokens.access_token || "").trim();
  if (primaryToken) {
    for (const target of TOKEN_PATHS) {
      try { writeMode0600(target, primaryToken + "\n"); } catch (_) {}
    }
  }
}

function openAndroidBrowser(url) {
  try {
    const res = spawnSync("am", ["start", "--user", "0", "-a", "android.intent.action.VIEW", "-d", url], {
      stdio: "ignore",
    });
    if (res.status === 0) return true;
  } catch (_) {}
  try {
    const res = spawnSync("am", ["start", "-a", "android.intent.action.VIEW", "-d", url], {
      stdio: "ignore",
    });
    return res.status === 0;
  } catch (_) {
    return false;
  }
}

async function runCodeFlow(codeVerifier, codeChallenge, state) {
  const redirectUri = "https://codeassist.google.com/authcode";
  const authUrl =
    "https://accounts.google.com/o/oauth2/v2/auth?" +
    new URLSearchParams({
      client_id: CLIENT_ID,
      redirect_uri: redirectUri,
      response_type: "code",
      access_type: "offline",
      prompt: "consent",
      scope: SCOPES,
      state,
      code_challenge_method: "S256",
      code_challenge: codeChallenge,
    }).toString();

  console.log("\n[agy] Opening Google Sign-In in your browser...");
  openAndroidBrowser(authUrl);
  console.log("\nIf the browser did not open automatically, open this URL:\n\n" + authUrl + "\n");

  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  const code = await new Promise((resolve) => rl.question("[agy] Paste the authorization code from Google: ", (ans) => {
    rl.close();
    resolve((ans || "").trim());
  }));
  if (!code) throw new Error("Authorization code is empty.");

  const tokens = await exchangeCode(code, redirectUri, codeVerifier);
  const email = await fetchEmail(tokens.access_token);
  saveCredentials(tokens, email);
  console.log(`\n[agy] Authenticated with Google OAuth 2.0${email ? " as " + email : ""} (0600 vault synced).`);
}

async function runWebLoopbackFlow() {
  const codeVerifier = base64Url(crypto.randomBytes(32));
  const codeChallenge = base64Url(crypto.createHash("sha256").update(codeVerifier).digest());
  const state = crypto.randomBytes(24).toString("hex");

  if (process.argv.includes("--code") || process.env.NO_BROWSER === "true") {
    return runCodeFlow(codeVerifier, codeChallenge, state);
  }

  const server = http.createServer();
  const port = await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => resolve(server.address().port));
  });

  const redirectUri = `http://127.0.0.1:${port}/oauth2callback`;
  const authUrl =
    "https://accounts.google.com/o/oauth2/v2/auth?" +
    new URLSearchParams({
      client_id: CLIENT_ID,
      redirect_uri: redirectUri,
      response_type: "code",
      access_type: "offline",
      prompt: "consent",
      scope: SCOPES,
      state,
      code_challenge_method: "S256",
      code_challenge: codeChallenge,
    }).toString();

  console.log("[agy] Launching Google OAuth 2.0 Sign-In in your browser...");
  openAndroidBrowser(authUrl);
  console.log("[agy] Waiting for Google callback on " + redirectUri + " ...");
  console.log("      (If browser doesn't open, visit: " + authUrl + " )\n");

  const code = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      server.close();
      reject(new Error("OAuth timed out after 3 minutes."));
    }, 180000);

    server.on("request", (req, res) => {
      try {
        const u = new URL(req.url || "/", `http://127.0.0.1:${port}`);
        if (u.pathname !== "/oauth2callback") {
          res.writeHead(404);
          res.end("Not Found");
          return;
        }
        const err = u.searchParams.get("error");
        const retState = u.searchParams.get("state");
        const retCode = u.searchParams.get("code");
        if (err) {
          res.writeHead(400, { "Content-Type": "text/html; charset=utf-8" });
          res.end(`<h3>Google OAuth Error: ${err}</h3>`);
          clearTimeout(timer);
          server.close();
          reject(new Error(`Google OAuth error: ${err}`));
          return;
        }
        if (retState !== state || !retCode) {
          res.writeHead(400, { "Content-Type": "text/html; charset=utf-8" });
          res.end("<h3>Invalid OAuth state or missing code.</h3>");
          clearTimeout(timer);
          server.close();
          reject(new Error("Invalid OAuth state or missing code"));
          return;
        }
        res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
        res.end(
          "<!doctype html><html><body style='background:#05070B;color:#F1F5F9;font-family:sans-serif;text-align:center;padding:40px'>" +
          "<h2 style='color:#38BDF8'>Terminal Agents Builder</h2><p>Google OAuth 2.0 completado.Ya puedes volver a la terminal.</p></body></html>"
        );
        clearTimeout(timer);
        server.close();
        resolve(retCode);
      } catch (e) {
        clearTimeout(timer);
        server.close();
        reject(e);
      }
    });
  });

  const tokens = await exchangeCode(code, redirectUri, codeVerifier);
  const email = await fetchEmail(tokens.access_token);
  saveCredentials(tokens, email);
  try {
    spawnSync("am", ["start", "--activity-reorder-to-front", "-n", "com.termux/com.termux.custom.ui.WorkstationActivity"], {
      stdio: "ignore",
    });
  } catch (_) {}
  console.log(`[agy] Authenticated with Google OAuth 2.0${email ? " as " + email : ""} (0600 vault synced).`);
}

runWebLoopbackFlow().catch((err) => {
  console.error("[agy] OAuth login failed:", err.message || err);
  console.error("[agy] Tip: try 'agy login --code' for manual authorization code mode.");
  process.exit(1);
});
EOF
chmod 0700 "$PREFIX/etc/melcat/agy-oauth.js"

# 5. Install melcat-gc (Storage & Cache Optimizer + zstd snapshot tool, strictly excluding OAuth credentials)
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
        tar --exclude='*oauth-token*' --exclude='*oauth_creds.json*' --exclude='.gemini/oauth_creds.json' --exclude='.gemini/google_accounts.json' --exclude='.config/antigravity/antigravity-oauth-token' --exclude='.gemini/antigravity-oauth-token' \
            -cf - -C "$HOME_DIR" .gemini .claude .config/melcat 2>/dev/null | zstd -T0 -q -o "$OUT"
        chmod 0600 "$OUT" 2>/dev/null || true
        echo "[storage-gc] Portable zstd snapshot written (secrets excluded): $OUT"
    else
        OUT_GZ="$SNAP_DIR/agent-workspace-$TS.tar.gz"
        tar --exclude='*oauth-token*' --exclude='*oauth_creds.json*' --exclude='.gemini/oauth_creds.json' --exclude='.gemini/google_accounts.json' --exclude='.config/antigravity/antigravity-oauth-token' --exclude='.gemini/antigravity-oauth-token' \
            -czf "$OUT_GZ" -C "$HOME_DIR" .gemini .claude .config/melcat 2>/dev/null
        chmod 0600 "$OUT_GZ" 2>/dev/null || true
        echo "[storage-gc] Portable gzip snapshot written (secrets excluded): $OUT_GZ"
    fi
fi

df -h "$PREFIX" | tail -n 1 | awk '{print "[storage-gc] Free storage: " $4 " (" $5 " used)"}'
EOF
chmod 0700 "$PREFIX/bin/melcat-gc"

# 6. Install `agy` native CLI launcher & workstation controller
cat > "$PREFIX/bin/agy" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
set -eu
PREFIX="/data/data/com.termux/files/usr"
HOME_DIR="/data/data/com.termux/files/home"
TOKEN_FILE="$HOME_DIR/.config/antigravity/antigravity-oauth-token"
OAUTH_CREDS_FILE="$HOME_DIR/.gemini/oauth_creds.json"
ACCOUNTS_FILE="$HOME_DIR/.gemini/google_accounts.json"
SKILLS_DIR="$HOME_DIR/.gemini/config/skills"

# Harvest token from ~/.gemini/oauth_creds.json if present so CLI & 0600 Vault stay in sync
if [ -s "$OAUTH_CREDS_FILE" ] && [ ! -s "$TOKEN_FILE" ] && command -v jq >/dev/null 2>&1; then
    EXTRACTED="$(jq -r '.refresh_token // .access_token // empty' "$OAUTH_CREDS_FILE" 2>/dev/null || true)"
    if [ -n "$EXTRACTED" ]; then
        umask 077
        mkdir -p "$(dirname "$TOKEN_FILE")"
        printf '%s\n' "$EXTRACTED" > "$TOKEN_FILE"
        chmod 600 "$TOKEN_FILE" 2>/dev/null || true
    fi
fi

if [ -f "$TOKEN_FILE" ]; then
    chmod 600 "$TOKEN_FILE" 2>/dev/null || true
    export ANTIGRAVITY_OAUTH_TOKEN="$(cat "$TOKEN_FILE")"
fi

print_help() {
    echo "============================================================"
    echo " Terminal Agents Builder — Native arm64-v8a CLI (agy)"
    echo "============================================================"
    echo " Skills Dir : $SKILLS_DIR ($(ls -1 "$SKILLS_DIR" 2>/dev/null | tr '\n' ' '))"
    if [ -s "$OAUTH_CREDS_FILE" ] || [ -s "$TOKEN_FILE" ]; then
        ACCT=""
        if [ -s "$ACCOUNTS_FILE" ] && command -v jq >/dev/null 2>&1; then
            ACCT="$(jq -r '.active // empty' "$ACCOUNTS_FILE" 2>/dev/null || true)"
        fi
        echo " OAuth Vault: ACTIVE (${ACCT:-0600 inherited})"
    else
        echo " OAuth Vault: NOT SET (run 'agy login' to sign in with Google)"
    fi
    echo "------------------------------------------------------------"
    echo " Commands:"
    echo "   agy               -> Launch interactive AI Agent CLI"
    echo "   agy \"<prompt>\"    -> Run AI Agent on a prompt"
    echo "   agy login         -> Sign in with Google OAuth 2.0 (opens browser)"
    echo "   agy login --code  -> Sign in with Google authorization code"
    echo "   agy logout        -> Wipe OAuth credentials & 0600 vault"
    echo "   agy setup         -> Install toolchain + @google/gemini-cli"
    echo "   agy upgrade       -> Upgrade all Termux packages & agent CLI"
    echo "   agy skills        -> List unified ~/.gemini & ~/.claude skills"
    echo "   agy token         -> Check Google OAuth / 0600 vault status"
    echo "   agy doctor        -> Diagnose toolchain, agent CLI, skills & vault"
    echo "   agy ui            -> Open 120Hz Workstation UI"
    echo "   agy help          -> Show this help"
    echo "   melcat-gc         -> Clean caches & zstd-compress logs"
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

ensure_agent_cli() {
    if [ -x "$HOME_DIR/.local/bin/agy" ] || command -v gemini >/dev/null 2>&1; then
        return 0
    fi
    if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
        echo "[agy] Node.js runtime not installed yet. Running 'agy setup'..."
        "$PREFIX/bin/agy" setup
    else
        echo "[agy] Installing official agent CLI engine (@google/gemini-cli)..."
        npm install -g --omit=optional @google/gemini-cli
    fi
}

case "${1:-}" in
    --ui|ui|workstation)
        echo "[agy] Opening 120Hz Terminal Agents Workstation..."
        open_ui "${2:-}"
        exit $?
        ;;
    --setup|setup)
        echo "[agy] Installing native arm64-v8a toolchain (git, nodejs-lts, zstd, zsh, tmux, ripgrep, jq)..."
        export DEBIAN_FRONTEND=noninteractive
        pkg update -y && pkg install -y -o Dpkg::Options::=--force-confold git nodejs-lts zstd zsh tmux ripgrep jq
        echo "[agy] Installing official AI Agent CLI engine (@google/gemini-cli)..."
        npm install -g --omit=optional @google/gemini-cli
        echo "[agy] Native toolchain & AI Agent CLI ready. Run 'agy login' to sign in with Google, or 'agy' to start."
        exit 0
        ;;
    --upgrade|upgrade)
        echo "[agy] Upgrading all Termux packages..."
        export DEBIAN_FRONTEND=noninteractive
        pkg upgrade -y -o Dpkg::Options::=--force-confold
        if command -v npm >/dev/null 2>&1; then
            echo "[agy] Upgrading @google/gemini-cli..."
            npm install -g --omit=optional @google/gemini-cli
        fi
        echo "[agy] Packages upgraded."
        exit 0
        ;;
    --skills|skills)
        echo "[agy] Active skills in $SKILLS_DIR (symlinked to ~/.claude/skills):"
        ls -1 "$SKILLS_DIR" 2>/dev/null || true
        exit 0
        ;;
    --login|login)
        shift || true
        if [ "${1:-}" = "--token" ]; then
            if [ ! -t 0 ]; then
                echo "[agy] 'agy login --token' needs an interactive terminal." >&2
                exit 1
            fi
            printf "[agy] Paste your OAuth token / API key (input hidden): "
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
            mkdir -p "$(dirname "$TOKEN_FILE")" "$HOME_DIR/.gemini"
            TMP_FILE="$TOKEN_FILE.tmp.$$"
            printf '%s\n' "$TOKEN_INPUT" > "$TMP_FILE"
            chmod 600 "$TMP_FILE"
            mv -f "$TMP_FILE" "$TOKEN_FILE"
            for COPY in "$HOME_DIR/.gemini/antigravity-oauth-token" "/data/data/com.termux/files/antigravity-oauth-token"; do
                cp -f "$TOKEN_FILE" "$COPY" 2>/dev/null && chmod 600 "$COPY" 2>/dev/null || true
            done
            unset TOKEN_INPUT
            echo "[agy] Token saved to 0600 vault."
            exit 0
        fi
        if command -v node >/dev/null 2>&1 && [ -f "$PREFIX/etc/melcat/agy-oauth.js" ]; then
            exec node "$PREFIX/etc/melcat/agy-oauth.js" "$@"
        fi
        echo "[agy] Launching native Google OAuth 2.0 Sign-In via Workstation..."
        open_ui "oauth_login"
        exit $?
        ;;
    --logout|logout)
        for f in "$TOKEN_FILE" "$HOME_DIR/.gemini/antigravity-oauth-token" "/data/data/com.termux/files/antigravity-oauth-token" "$OAUTH_CREDS_FILE" "$ACCOUNTS_FILE"; do
            if [ -f "$f" ]; then
                SIZE="$(wc -c < "$f" 2>/dev/null || echo 0)"
                head -c "$SIZE" /dev/zero > "$f" 2>/dev/null || true
                rm -f "$f"
            fi
        done
        echo "[agy] Google OAuth credentials & 0600 vault wiped."
        exit 0
        ;;
    --token|token)
        if [ -s "$OAUTH_CREDS_FILE" ] || [ -s "$TOKEN_FILE" ]; then
            TARGET_F="$TOKEN_FILE"
            [ -s "$TARGET_F" ] || TARGET_F="$OAUTH_CREDS_FILE"
            MODE="$(stat -c '%a' "$TARGET_F" 2>/dev/null || echo '600')"
            ACCT=""
            if [ -s "$ACCOUNTS_FILE" ] && command -v jq >/dev/null 2>&1; then
                ACCT="$(jq -r '.active // empty' "$ACCOUNTS_FILE" 2>/dev/null || true)"
            fi
            echo "[agy] OAuth ACTIVE${ACCT:+ ($ACCT)} at $TARGET_F (mode: 0$MODE)"
        else
            echo "[agy] OAuth NOT configured. Run 'agy login' to sign in with Google."
        fi
        exit 0
        ;;
    --doctor|doctor)
        echo "[agy] Doctor — $(uname -m) / Android $(getprop ro.build.version.release 2>/dev/null || echo '?')"
        for bin in git node npm gemini zstd zsh tmux rg jq; do
            if command -v "$bin" >/dev/null 2>&1; then
                echo "  [ok]      $bin"
            else
                echo "  [missing] $bin  -> run 'agy setup'"
            fi
        done
        echo "  skills : $(ls -1 "$SKILLS_DIR" 2>/dev/null | wc -l | tr -d ' ') in $SKILLS_DIR"
        if [ -L "$HOME_DIR/.claude/skills" ]; then echo "  [ok]      ~/.claude/skills symlink"; else echo "  [warn]    ~/.claude/skills symlink missing"; fi
        if [ -s "$OAUTH_CREDS_FILE" ] || [ -s "$TOKEN_FILE" ]; then
            echo "  [ok]      Google OAuth vault (0600)"
        else
            echo "  [warn]    OAuth vault empty -> run 'agy login'"
        fi
        echo "  storage: $(du -sh "$HOME_DIR" 2>/dev/null | cut -f1) in HOME"
        UPG="$(apt list --upgradable 2>/dev/null | grep -c upgradable || true)"
        [ "${UPG:-0}" -gt 0 ] 2>/dev/null && echo "  [info]    $UPG packages upgradable -> 'agy upgrade'"
        exit 0
        ;;
    -h|--help|help)
        print_help
        exit 0
        ;;
esac

if [ -x "$HOME_DIR/.local/bin/agy" ]; then
    exec "$HOME_DIR/.local/bin/agy" "$@"
fi

ensure_agent_cli
if command -v gemini >/dev/null 2>&1; then
    exec gemini "$@"
fi

print_help
EOF
chmod 0700 "$PREFIX/bin/agy"

# 7. Install `workstation` shortcut command
cat > "$PREFIX/bin/workstation" <<'EOF'
#!/data/data/com.termux/files/usr/bin/sh
exec agy ui
EOF
chmod 0700 "$PREFIX/bin/workstation"

# 8. Configure shell profiles (~/.bashrc and ~/.zshrc) for Antigravity & Claude CLI token inheritance
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
alias agy-audit='agy "Run a senior-auditor review on this repository"'
alias gc='melcat-gc'
alias gc-snap='melcat-gc --snapshot'
# <<< TERMINAL-AGENTS-BUILDER <<<
EOF
    fi
done

date -u +"%Y-%m-%dT%H:%M:%SZ" > "$STAMP_FILE"
chmod 0600 "$STAMP_FILE"
