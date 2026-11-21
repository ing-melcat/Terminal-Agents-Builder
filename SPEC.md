# SPEC.md — Terminal Agents Builder (`com.termux` + `com.termux.custom.*`)

## 1. Definición del Proyecto
Aplicación Android (APK) personalizada sobre el núcleo open-source de **`termux-app` (GPLv3)**, estructurada como una capa de personalización limpia (al estilo de **HyperOS sobre AOSP**) orientada a funcionar como **estación de trabajo nativa para agentes IA (`Antigravity CLI / agy` y `Claude CLI`)**.

* **Ejecución Nativa `arm64-v8a`:** Sin contenedores pesados como `proot-distro`. Ejecución directa sobre binarios `aarch64` en `$PREFIX` (`/data/data/com.termux/files/usr`).
* **Dispositivos Objetivo:**
  * **Primario (Modo Tablet / Desktop 120Hz):** Xiaomi Pad 6 con teclado físico bajo HyperOS.
  * **Secundario (Modo Compacto / Móvil):** Redmi Note 13 Pro y cualquier dispositivo Android `arm64-v8a` con el mismo binario APK.

---

## 2. Reglas Inmutables e Invariantes Técnicos

1. **`targetSdkVersion = 28` fijo:** Prohibido elevar a `29+` para evitar el bloqueo SELinux `W^X` de Android 10+ sobre ejecución de binarios desde el directorio de datos de la app (`execve` en `$PREFIX/bin`).
2. **`applicationId = "com.termux"` y `$PREFIX = /data/data/com.termux/files/usr`:** Se conservan intactos para compatibilidad binaria 100% con los repositorios `apt-android-7` (`aarch64`) de Termux.
3. **Motor PTY Intocable:** Prohibido modificar `forkpty`, descriptores de archivo (`FD`) o manejo de señales POSIX en `terminal-emulator/src/main/jni/termux.c`.
4. **Bootstrap Atómico:** Respetar íntegramente la extracción de `bootstrap-aarch64.zip`, `SYMLINKS.txt` y el `renameTo` atómico en `TermuxInstaller.java`. Cualquier auto-provisioning (`bootstrap-custom.sh`) se ejecuta **después** del `renameTo` atómico.
5. **Aislamiento de Código Nuevo:** Todo el desarrollo nuevo reside bajo `com.termux.custom.*`, manteniendo los paquetes upstream (`com.termux.app`, `com.termux.terminal`, `com.termux.view`, `com.termux.shared`) limpios para merges desde upstream.
6. **Licencia y Verificación:** Cumplimiento estricto de **GPLv3** de `termux-app` y verificación real de compilación NDK/JNI + Kotlin/Java.

---

## 3. Arquitectura en 4 Capas

```text
Terminal-Agents-Builder/
├── terminal-emulator/                  # [Capa 0 - Inmutable] Motor PTY C/JNI (termux.c)
├── terminal-view/                      # [Capa 0 - Inmutable] Renderizador base de terminal
├── termux-shared/                      # [Capa 0 - Inmutable] Utilidades, shell env y constantes
└── app/
    └── src/main/
        ├── cpp/                        # JNI loader de bootstrap-aarch64.zip
        ├── assets/custom/
        │   └── bootstrap-custom.sh     # Script idempotente post-bootstrap (agy/claude/skills/zstd)
        └── java/com/termux/
            ├── app/                    # Upstream TermuxActivity, TermuxService, TermuxInstaller
            └── custom/                 # === CAPAS 1, 2 Y 3 (AISLADAS) ===
                ├── data/               # [Capa 1] SQLite Local-First + CRDT Delta Log (conversaciones, contextos, compactaciones)
                ├── security/           # [Capa 1] AntigravityTokenWarden (antigravity-oauth-token chmod 0600)
                ├── guardian/           # [Capa 1] ProcessGuardianService (WakeLock, WifiLock, HyperOS Autostart, Phantom Killer)
                ├── storage/            # [Capa 1] StorageOptimizer (zstd -T0, tar --zstd snapshots, melcat-gc)
                ├── provisioning/       # [Capa 1] CustomBootstrapProvisioner (post-atomic-rename hook -> bootstrap-custom.sh)
                ├── bridge/             # [Capa 2] TerminalSurfaceBridge (120Hz frame pacing, NerdFont, hardware keyboard chords)
                └── ui/                 # [Capa 3] WorkstationActivity (Compose WindowSizeClass: Tablet 120Hz vs Compact Phone)
```

### Capa 0 — Núcleo Upstream de Termux (Inmutable)
* Motor PTY nativo en C/JNI (`terminal-emulator/src/main/jni/termux.c`).
* `TermuxService` y extracción atómica de `bootstrap-aarch64` en `TermuxInstaller.java`.

### Capa 1 — Runtime, Datos Local-First y Optimización (`com.termux.custom.*`)
* **Persistencia Local-First Descentralizada (`com.termux.custom.data`):** Base de datos SQLite en modo WAL con registro de deltas CRDT (Lamport clock + `site_id` + `hlc_timestamp` + soft-delete tombstones) para almacenar sesiones de agentes, contextos, compactaciones y exportar/importar deltas P2P sin servidor central.
* **Herencia Segura de Tokens (`com.termux.custom.security.AntigravityTokenWarden`):** Gestión y sincronización del token `antigravity-oauth-token` con permisos POSIX `0600` (`Os.chmod`) en almacenamiento privado y puente hacia `~/.config/antigravity/` / `~/.gemini/` para reutilizar la identidad y cuota del CLI sin sesiones paralelas conflictivas.
* **Supervivencia de Procesos (`com.termux.custom.guardian.ProcessGuardianService`):**
  * `ForegroundService` persistente con `PARTIAL_WAKE_LOCK` y `WIFI_MODE_FULL_HIGH_PERF`.
  * Intents directos a **HyperOS / MIUI Autostart** (`miui.intent.action.OP_AUTO_START`) y exclusión de ahorro de batería.
  * Diagnóstico del **Phantom Process Killer** de Android 12+ (`max_phantom_processes`) con generador de comando ADB/Wireless Debugging listo para copiar.
* **Optimización de Almacenamiento (`com.termux.custom.storage.StorageOptimizer`):**
  * Compresión `zstd` (`tar --zstd` / `zstd -T0`) para rotación de logs y snapshots portables entre Xiaomi Pad 6 y Redmi Note 13 Pro.
  * Rutina `melcat-gc` para limpieza segura de cachés (`apt`, `npm/pnpm`, `pip`, logs huérfanos).
* **Auto-Provisioning (`com.termux.custom.provisioning.CustomBootstrapProvisioner` + `bootstrap-custom.sh`):**
  * Ejecutado de forma idempotente tras el `renameTo` atómico de `$STAGING_PREFIX -> $PREFIX`.
  * Configura `~/.gemini/config/skills/`, crea el enlace simbólico hacia `~/.claude/skills/`, instala el binario helper `melcat-gc` en `$PREFIX/bin/melcat-gc` y prepara el perfil para `zsh` + `zellij`/`tmux`.

### Capa 2 — Abstracción de Terminal Moderna (`com.termux.custom.bridge.TerminalSurfaceBridge`)
* Interfaz que desacopla el `TerminalSession` / descriptor PTY de Termux del renderizador visual.
* **Fase 1 (Implementada):** Envoltura acelerada por hardware de `TerminalView` con configuración a **120Hz** (`FrameRate` / high-refresh display mode para Xiaomi Pad 6), soporte de fuente monoespaciada/Nerd Font, atajos completos de teclado físico (`Ctrl+Shift+P` Command Palette, `Ctrl+T` nueva sesión, `Ctrl+Tab` cambio de pestaña, `Alt+1..9`) y lanzamiento directo de agentes (`agy`, `claude`, `zellij`, `melcat-gc`).
* **Fase 2 (Preparada):** Punto de extensión `RendererBackend` para conectar un motor GPU nativo (`alacritty_terminal` / `libghostty-vt`) al mismo descriptor PTY.

### Capa 3 — Interfaz Adaptativa (`com.termux.custom.ui`)
* Construida en **Kotlin + Jetpack Compose** con detección reactiva de `WindowSizeClass` y teclado físico (`Configuration.keyboard == KEYBOARD_QWERTY`):
  * **Modo Tablet / Desktop (Xiaomi Pad 6 + Teclado Físico):** Barra superior de pestañas multi-sesión, panel lateral de agentes/contextos CRDT + diagnósticos HyperOS/Token/Storage, paleta de comandos rápida y barra de estado inferior en tiempo real.
  * **Modo Compacto (Redmi Note 13 Pro / Celular):** Navegación inferior compacta, barra táctil rápida de modificadores (`ESC`, `CTRL`, `ALT`, `TAB`, `/`, `-`, flechas y lanzadores `agy`/`claude`) y acceso rápido a snapshots/guardian.
