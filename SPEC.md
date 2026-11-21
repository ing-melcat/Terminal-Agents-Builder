# SPEC.md — Arquitectura y Plan de Ejecución: App Android Local-First

## 1. Visión del Producto y Paradigma
Aplicación móvil nativa para **Android** construida bajo el paradigma **Local-First (Offline-First)**.
* **Principio fundamental:** La base de datos local en el dispositivo es la única fuente de verdad (*Single Source of Truth*) para la interfaz de usuario. Todas las operaciones de lectura y escritura ocurren de forma instantánea en local con latencia cero, sin depender de conexión a internet.
* **Resiliencia:** Cero pantallas de carga bloqueantes por red, persistencia atómica garantizada ante cierres inesperados y preparación estructural para sincronización diferencial opcional en segundo plano (CRDTs / Timestamps + Soft Deletes).

---

## 2. Stack Tecnológico Obligatorio
* **Lenguaje:** Kotlin 2.0+ (Coroutines + `StateFlow` / `SharedFlow`).
* **UI Framework:** Jetpack Compose (Material 3) con soporte nativo para Edge-to-Edge y gestos fluidos.
* **Base de Datos Local:** Room (SQLite) con modo WAL (*Write-Ahead Logging*) activado y migraciones versionadas explícitas.
* **Inyección de Dependencias:** Hilt (Dagger) o Koin (preferencia: Hilt para validación en tiempo de compilación).
* **Arquitectura:** Clean Architecture + MVI / MVVM unidireccional (`UI -> Intent/Event -> ViewModel -> UseCase -> Repository -> Room DAO -> Flow<UiState>`).
* **Procesamiento en Segundo Plano:** WorkManager para tareas diferidas y sincronización resiliente.
* **Target SDK:** Min SDK 26 (Android 8.0) / Target SDK 35 (Android 15).

---

## 3. Reglas de Ejecución para el Agente (`agy`)

### Uso de Skills Activas (`~/.gemini/config/skills`)
1. **`senior-auditor`:** Antes de dar por cerrado cualquier módulo, esquema de Room o repositorio, audita condiciones de carrera en hilos (`Dispatchers.IO`), fugas de memoria, manejo de excepciones y consultas SQL sin índices. Cero código placeholder (`// TODO`).
2. **`motion-framer`:** Toda transición de pantalla, apertura de tarjetas, estado vacío (*empty state*) y micro-interacción en Jetpack Compose debe implementar físicas de resorte (`spring()`), curvas de aceleración naturales y transiciones compartidas fluidas (`AnimatedContent`, `animateItemPlacement`), evitando saltos bruscos de layout.
3. **`caveman`:** Mantén las respuestas de terminal, logs de avance y explicaciones técnicas ultra-concisas, directas y sin relleno verbal. Prioriza código funcional y comandos ejecutables.

---

## 4. Estructura de Capas del Proyecto

```text
app/src/main/java/com/melcat/localfirst/
├── core/
│   ├── database/          # AppDatabase, TypeConverters, WAL config, Migrations
│   ├── di/                # Módulos de Hilt (DatabaseModule, RepositoryModule)
│   ├── designsystem/      # Tema Material 3, Tipografía, Tokens de Motion (motion-framer)
│   └── common/            # Result wrapper, DispatchersProvider, Extensiones
├── feature/
│   └── [nombre_modulo]/
│       ├── data/          # Entities, DAOs, Mappers, RepositoryImpl
│       ├── domain/        # Models puros, Repository Interface, UseCases
│       └── presentation/  # Screen (Compose), ViewModel, UiState, UiEvent, Components
└── MainActivity.kt        # Configuración Edge-to-Edge y NavHost
```

---

## 5. Modelo de Datos Base (Local-First Ready)

Toda entidad persistente en Room debe incluir metadatos base para integridad y futura sincronización sin reescribir el esquema:

```kotlin
// Campos estándar obligatorios en cada @Entity:
// @PrimaryKey val id: String = UUID.randomUUID().toString()
// val createdAt: Long = System.currentTimeMillis()
// val updatedAt: Long = System.currentTimeMillis()
// val isDeleted: Boolean = false                  // Soft-delete para integridad local/sync
// val syncStatus: SyncStatus = SyncStatus.PENDING // LOCAL_ONLY, PENDING, SYNCED
```

---

## 6. Fases de Construcción (Secuencia Estricta)

### Fase 1: Scaffolding y Configuración Gradle (Version Catalog `libs.versions.toml`)
* Inicializar proyecto Android con Kotlin DSL (`build.gradle.kts`).
* Configurar KSP (Kotlin Symbol Processing) para Room y Hilt.
* Verificar compilación limpia `./gradlew assembleDebug` antes de escribir lógica de negocio.

### Fase 2: Capa de Datos Local (Room + Repository)
* Implementar `AppDatabase` con `setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)`.
* Crear entidades, DAOs reactivos retornando `Flow<List<Entity>>` y pruebas unitarias instrumentadas/Robolectric para operaciones CRUD y soft-deletes.

### Fase 3: Capa de Dominio y Estado Unidireccional
* Definir modelos inmutables (`@Immutable` / `@Stable` para optimizar recomposiciones en Compose).
* Implementar `ViewModel` exponiendo `StateFlow<UiState>` mediante `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InitialState)`.

### Fase 4: Interfaz Jetpack Compose + Físicas de Movimiento
* Construir pantallas principales con componentes modulares y soporte completo para modo oscuro/claro dinámico.
* Aplicar `motion-framer` en listas, gestos de deslizar para eliminar/archivar y retroalimentación háptica (`HapticFeedbackType`).

### Fase 5: Auditoría Final y Empaquetado APK
* Ejecutar auditoría completa con `senior-auditor` (revisión de recomposiciones innecesarias, índices SQLite y manejo de permisos).
* Compilar APK de prueba: `./gradlew assembleDebug` y validar instalación vía `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

---

## 7. Prompt de Arranque para `agy`

> "Lee `SPEC.md` completo. Activa las skills `senior-auditor`, `motion-framer` y `caveman`. Inicia la Fase 1 generando la estructura base del proyecto, `libs.versions.toml` y `build.gradle.kts` listos para compilar."
