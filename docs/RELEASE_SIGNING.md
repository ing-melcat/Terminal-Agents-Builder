# Firma Privada de Releases (RUR · UNIPOLI) y CI/CD con GitHub Actions

Esta guía documenta cómo se firman los APKs oficiales de **Terminal Agents Builder (RUR Edition)** con tu llave privada exclusiva (en lugar de `app/testkey_untrusted.jks`), garantizando actualizaciones in-place seguras para todos los clientes.

---

## 1. Secretos requeridos en GitHub Actions

Configura estos 4 secretos en **Settings → Secrets and variables → Actions → New repository secret**:

| Secreto | Descripción |
| :--- | :--- |
| `RUR_RELEASE_KEYSTORE_BASE64` | Archivo `.jks` privado codificado en Base64 en una sola línea (`base64 -w 0 rur-release.jks`) |
| `RUR_KEYSTORE_PASSWORD` | Contraseña del keystore (mínimo 20 caracteres aleatorios) |
| `RUR_KEY_ALIAS` | Alias de la llave (ej. `rur-release`) |
| `RUR_KEY_PASSWORD` | Contraseña de la llave |

> **Regla de oro:** El archivo `.jks` y `keystore.properties` están bloqueados en `.gitignore` y **NUNCA** deben subirse a git. Guarda siempre una copia de respaldo cifrada fuera de GitHub (los secretos de GitHub son de solo escritura y no se pueden descargar después).

---

## 2. Cómo generar el keystore manualmente (opcional, desde tu PC)

```bash
keytool -genkeypair -v \
  -keystore rur-release.jks \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 \
  -validity 10000 \
  -alias rur-release \
  -dname "CN=Luis Antonio Simental Ochoa, OU=Research Unit of Robotics, O=UNIPOLI Durango, L=Durango, ST=Durango, C=MX"

# Codificar en Base64 para RUR_RELEASE_KEYSTORE_BASE64:
base64 -w 0 rur-release.jks > rur-release.jks.b64
```

Para compilar un release firmado localmente, crea un archivo untracked `keystore.properties` en la raíz del repo:
```properties
storeFile=/ruta/segura/rur-release.jks
storePassword=TU_PASSWORD
keyAlias=rur-release
keyPassword=TU_PASSWORD
```
Y ejecuta:
```bash
RUR_REQUIRE_RELEASE_SIGNING=1 TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS=1 ./gradlew assembleRelease --no-daemon
```

---

## 3. Cómo publicar una nueva versión oficial firmada

El workflow [`.github/workflows/release-apk.yml`](../.github/workflows/release-apk.yml) ejecuta automáticamente:
1. Validación POSIX (`sh -n`) + invariantes Capa 0 (`targetSdkVersion=28`, `com.termux`, `termux.c`).
2. Suite completa de pruebas unitarias (`./gradlew testDebugUnitTest`).
3. Compilación `assembleRelease` (`arm64-v8a` + `universal`) con `versionCode` estrictamente creciente (`major*10000 + minor*100 + patch`).
4. Verificación `apksigner` asegurando que **NO** se haya firmado con `testkey_untrusted.jks`.
5. Publicación automática del GitHub Release con los APKs firmados y sus `.sha256` adjuntos como **Release Assets** (compatibles 100% con el OTA integrado en `Shield -> Auto-Updates`).

### Opción A — Por etiqueta Git
```bash
git tag v1.1
git push origin v1.1
```

### Opción B — Manual desde GitHub
En la pestaña **Actions → Release Signed APK (RUR Private Keystore) → Run workflow**, ingresa el tag (ej. `v1.1`) y pulsa **Run workflow**.

---

## 4. Nota única de migración desde builds `debug` (`testkey_untrusted.jks`)
- La **primera vez** que cambies de un APK `debug` anterior a tu APK `release` firmado con tu llave privada RUR, Android exigirá desinstalar la versión `debug` una sola vez (distinta firma criptográfica).
- **A partir de ese momento**, todas las futuras versiones (`v1.1` → `v1.2` → `v2.0`...) se actualizarán **encima** sin perder `$HOME`, `$PREFIX`, agentes, llaves SSH ni bóvedas `0600`.
