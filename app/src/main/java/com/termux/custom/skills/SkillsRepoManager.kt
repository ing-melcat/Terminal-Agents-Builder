package com.termux.custom.skills

import android.system.Os
import android.system.OsConstants
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SkillRepoInfo(
    val name: String,
    val hasSkillMd: Boolean,
    val isGitRepo: Boolean,
    val branch: String? = null,
    val remoteUrl: String? = null,
    val hasLocalEdits: Boolean = false,
)

data class SkillsSummaryState(
    val gitInstalled: Boolean = false,
    val isRootGitRepo: Boolean = false,
    val rootBranch: String? = null,
    val rootRemoteUrl: String? = null,
    val rootHasLocalEdits: Boolean = false,
    val skills: List<SkillRepoInfo> = emptyList(),
) {
    val totalCount: Int get() = skills.size
    val gitReposCount: Int
        get() = skills.count { it.isGitRepo } + if (isRootGitRepo) 1 else 0
    val modifiedCount: Int
        get() = skills.count { it.hasLocalEdits } + if (rootHasLocalEdits) 1 else 0
}

/**
 * Inspects unified global skills in ~/.gemini/config/skills/ (symlinked to agy, opencode, and claude),
 * reads/edits SKILL.md safely with atomic writes and symlink-escape protection,
 * validates Git skill repository URLs and directory names strictly against option/command injection,
 * and builds zero-interpolation OS execve argv[] invocations for `workstation skills ...` in an
 * isolated local PTY tab so active SSH / TUI agent sessions are never disturbed.
 */
class SkillsRepoManager(
    private val prefixDir: File = File(TermuxConstants.TERMUX_PREFIX_DIR_PATH),
    private val homeDir: File = File(TermuxConstants.TERMUX_HOME_DIR_PATH),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val writeMutex = Mutex()
    private val skillsDir: File get() = File(homeDir, ".gemini/config/skills")

    suspend fun inspectSkills(): SkillsSummaryState = withContext(ioDispatcher) {
        currentCoroutineContext().ensureActive()
        val gitBin = File(prefixDir, "bin/git")
        val gitAvailable = gitBin.canExecute()

        if (!skillsDir.isDirectory) {
            return@withContext SkillsSummaryState(gitInstalled = gitAvailable)
        }

        val isRootGit = File(skillsDir, ".git").exists()
        val rootBranch = if (isRootGit) readGitBranch(skillsDir) else null
        val rootRemote = if (isRootGit) readGitOriginUrl(skillsDir) else null
        val rootDirty = if (isRootGit && gitAvailable) hasGitChanges(gitBin, skillsDir) else false

        val entries = skillsDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?.map { dir ->
                currentCoroutineContext().ensureActive()
                val isSubGit = File(dir, ".git").exists()
                val branch = if (isSubGit) readGitBranch(dir) else null
                val remote = if (isSubGit) readGitOriginUrl(dir) else null
                val dirty = if (isSubGit && gitAvailable) hasGitChanges(gitBin, dir) else false
                SkillRepoInfo(
                    name = dir.name,
                    hasSkillMd = File(dir, "SKILL.md").isFile,
                    isGitRepo = isSubGit,
                    branch = branch,
                    remoteUrl = remote,
                    hasLocalEdits = dirty,
                )
            }
            .orEmpty()

        SkillsSummaryState(
            gitInstalled = gitAvailable,
            isRootGitRepo = isRootGit,
            rootBranch = rootBranch,
            rootRemoteUrl = rootRemote,
            rootHasLocalEdits = rootDirty,
            skills = entries,
        )
    }

    /**
     * Reads ~/.gemini/config/skills/<skillName>/SKILL.md safely (rejecting path traversal,
     * live or dangling symlink escapes outside skillsDir, and oversized files > 128 KB with
     * a bounded O_NOFOLLOW read). Returns a clean YAML frontmatter template when SKILL.md
     * does not exist yet inside that skill folder.
     */
    suspend fun readSkillMarkdown(skillName: String): Result<String> = withContext(ioDispatcher) {
        runCatching {
            val cleanName = validateSkillName(skillName).getOrThrow()
            val skillMd = resolveSafeSkillMdFile(cleanName, createParentIfMissing = false)
            if (!skillMd.exists()) {
                return@runCatching defaultSkillTemplate(cleanName)
            }
            require(skillMd.isFile) { "SKILL.md no es un archivo regular" }
            val rawBytes = readBoundedNoFollow(skillMd, MAX_SKILL_MD_BYTES.toInt())
            rawBytes.toString(Charsets.UTF_8)
        }.onFailure { err ->
            if (err is CancellationException) throw err
        }
    }

    /**
     * Atomically writes ~/.gemini/config/skills/<skillName>/SKILL.md using O_EXCL + O_NOFOLLOW
     * temp creation, fsync, and Os.rename after verifying fail-closed symlink containment so
     * edits immediately reflect in agy/opencode/claude and Git `diff`/`push`.
     */
    suspend fun saveSkillMarkdown(skillName: String, rawContent: String): Result<Unit> =
        withContext(ioDispatcher) {
            writeMutex.withLock {
                runCatching {
                    val cleanName = validateSkillName(skillName).getOrThrow()
                    val normalized = rawContent.replace("\r\n", "\n")
                    val bytes = normalized.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_SKILL_MD_BYTES) {
                        "El contenido excede el límite máximo de 128 KB"
                    }
                    val skillMd = resolveSafeSkillMdFile(cleanName, createParentIfMissing = true)
                    val parent = skillMd.parentFile ?: error("Directorio de skill inválido")
                    val tmp = File(parent, ".SKILL.md.tmp.${System.nanoTime()}")
                    try {
                        val wroteWithOs = runCatching {
                            val fd = Os.open(
                                tmp.absolutePath,
                                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW,
                                420, // 0644
                            )
                            check(fd != null && fd.valid()) { "Os.open unavailable" }
                            try {
                                FileOutputStream(fd).use { out ->
                                    out.write(bytes)
                                    out.fd.sync()
                                }
                            } finally {
                                runCatching { Os.close(fd) }
                            }
                            check(tmp.isFile && tmp.length() == bytes.size.toLong()) { "Os.open write incomplete" }
                            Os.rename(tmp.absolutePath, skillMd.absolutePath)
                            !tmp.exists() && skillMd.isFile && skillMd.length() == bytes.size.toLong()
                        }.getOrDefault(false)

                        if (!wroteWithOs) {
                            FileOutputStream(tmp).use { out ->
                                out.write(bytes)
                                out.fd.sync()
                            }
                            if (!tmp.renameTo(skillMd)) {
                                // Unconditionally unlink target (even if dangling symlink) before fallback copy
                                runCatching { Os.remove(skillMd.absolutePath) }
                                skillMd.delete()
                                tmp.copyTo(skillMd, overwrite = true)
                                tmp.delete()
                            }
                        }
                    } finally {
                        if (tmp.exists()) runCatching { tmp.delete() }
                    }
                }.onFailure { err ->
                    if (err is CancellationException) throw err
                }
            }
        }

    private fun readBoundedNoFollow(file: File, maxBytes: Int): ByteArray {
        val osRead = runCatching {
            val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
            check(fd != null && fd.valid()) { "Os.open unavailable" }
            try {
                java.io.FileInputStream(fd).use { input ->
                    readStreamBounded(input, maxBytes)
                }
            } finally {
                runCatching { Os.close(fd) }
            }
        }
        if (osRead.isSuccess) return osRead.getOrThrow()
        val cause = osRead.exceptionOrNull()
        if (cause is IllegalArgumentException) throw cause
        if (cause is android.system.ErrnoException && cause.errno == OsConstants.ELOOP) {
            throw IllegalArgumentException("No se permite leer un SKILL.md enlazado simbólicamente")
        }
        // Fallback for JVM/Robolectric where Os.open may be stubbed
        require(!isSymlinkFailClosed(file)) { "No se permite leer un SKILL.md enlazado simbólicamente" }
        return java.io.FileInputStream(file).use { input -> readStreamBounded(input, maxBytes) }
    }

    private fun readStreamBounded(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            require(total <= maxBytes) {
                "SKILL.md excede el límite de 128 KB para edición en la app"
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun isSymlinkFailClosed(file: File): Boolean = try {
        val st = Os.lstat(file.absolutePath)
        if (st != null && st.st_mode != 0) {
            OsConstants.S_ISLNK(st.st_mode)
        } else {
            runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        }
    } catch (e: android.system.ErrnoException) {
        e.errno != OsConstants.ENOENT
    } catch (_: Throwable) {
        runCatching { java.nio.file.Files.isSymbolicLink(file.toPath()) }.getOrDefault(true)
    }

    private fun resolveSafeSkillMdFile(cleanName: String, createParentIfMissing: Boolean): File {
        if (!skillsDir.exists() && createParentIfMissing) {
            skillsDir.mkdirs()
        }
        val canonicalRoot = skillsDir.canonicalFile
        val targetDir = File(skillsDir, cleanName)
        // Check symlink unconditionally BEFORE exists() so dangling symlinks are always rejected
        require(!isSymlinkFailClosed(targetDir)) {
            "No se permiten enlaces simbólicos en carpetas de skills"
        }
        if (targetDir.exists()) {
            require(targetDir.isDirectory) { "La ruta de la skill no es un directorio" }
        } else if (createParentIfMissing) {
            targetDir.mkdirs()
        }
        val canonicalDir = targetDir.canonicalFile
        require(canonicalDir.parentFile == canonicalRoot) {
            "Ruta fuera de ~/.gemini/config/skills"
        }
        val skillMd = File(canonicalDir, "SKILL.md")
        // Unconditional check catches both live and dangling symlinks at SKILL.md
        require(!isSymlinkFailClosed(skillMd)) {
            "No se permite editar un SKILL.md enlazado simbólicamente"
        }
        if (skillMd.exists()) {
            require(skillMd.canonicalFile.parentFile == canonicalDir) {
                "SKILL.md fuera de la carpeta de la skill"
            }
        }
        return skillMd
    }

    private fun defaultSkillTemplate(skillName: String): String =
        """
        ---
        name: $skillName
        description: Custom workstation skill shared by agy, opencode, and claude
        ---
        # $skillName
        """.trimIndent() + "\n"

    /**
     * Builds a 100% static zero-interpolation `$PREFIX/bin/sh` execve invocation that runs
     * `$PREFIX/bin/workstation skills <subcommand> [args...]` inside a dedicated local PTY tab
     * and drops cleanly into Termux's login shell so output/diffs remain readable.
     */
    fun buildSkillsPtyExec(
        subcommand: String,
        extraArgs: List<String> = emptyList(),
    ): Pair<String, Array<String>> {
        require(subcommand in ALLOWED_SUBCOMMANDS) {
            "Subcomando de skills inválido: $subcommand"
        }
        val validatedArgs = when (subcommand) {
            "add" -> {
                require(extraArgs.isNotEmpty()) { "Ingresa la URL Git del repositorio de skills" }
                val cleanUrl = validateGitUrl(extraArgs[0]).getOrThrow()
                val cleanName = extraArgs.getOrNull(1)?.trim().orEmpty()
                if (cleanName.isNotEmpty()) {
                    listOf(cleanUrl, validateSkillName(cleanName).getOrThrow())
                } else {
                    listOf(cleanUrl)
                }
            }
            "init" -> {
                val rawUrl = extraArgs.firstOrNull()?.trim().orEmpty()
                if (rawUrl.isNotEmpty()) {
                    listOf(validateGitUrl(rawUrl).getOrThrow())
                } else {
                    emptyList()
                }
            }
            "push" -> {
                val rawMsg = extraArgs.firstOrNull()?.trim().orEmpty()
                    .filterNot { it.isISOControl() }
                    .take(120)
                if (rawMsg.isNotEmpty()) listOf(rawMsg) else emptyList()
            }
            else -> emptyList()
        }

        val shBin = File(prefixDir, "bin/sh").absolutePath
        val wsBin = File(prefixDir, "bin/workstation").absolutePath
        val loginBin = File(prefixDir, "bin/login").absolutePath
        val bashBin = File(prefixDir, "bin/bash").absolutePath

        val argv = ArrayList<String>(9 + validatedArgs.size).apply {
            add("-c")
            add(SKILLS_PTY_WRAPPER_SCRIPT)
            add("rur-skills")
            add(wsBin)
            add(loginBin)
            add(bashBin)
            add(shBin)
            add("skills")
            add(subcommand)
            addAll(validatedArgs)
        }
        return shBin to argv.toTypedArray()
    }

    private fun readGitBranch(repoDir: File): String? {
        val headFile = File(repoDir, ".git/HEAD")
        if (!headFile.isFile || headFile.length() > 1024L) return null
        val text = runCatching { headFile.readText().trim() }.getOrNull() ?: return null
        return when {
            text.startsWith("ref: refs/heads/") -> text.removePrefix("ref: refs/heads/").take(40)
            text.length >= 7 -> text.take(7)
            else -> null
        }
    }

    private fun readGitOriginUrl(repoDir: File): String? {
        val configFile = File(repoDir, ".git/config")
        if (!configFile.isFile || configFile.length() > 65_536L) return null
        val lines = runCatching { configFile.readLines() }.getOrNull() ?: return null
        var inOrigin = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inOrigin = line.equals("[remote \"origin\"]", ignoreCase = true)
            } else if (inOrigin && line.substringBefore('=').trim().equals("url", ignoreCase = true)) {
                val value = line.substringAfter('=', "").trim()
                if (value.isNotEmpty()) return value.take(200)
            }
        }
        return null
    }

    private fun hasGitChanges(gitBin: File, repoDir: File): Boolean {
        var proc: Process? = null
        var readerThread: Thread? = null
        return try {
            val pb = ProcessBuilder(
                gitBin.absolutePath,
                "--no-optional-locks",
                "-C", repoDir.absolutePath,
                "status", "--porcelain", "--untracked-files=normal",
            ).redirectErrorStream(true)
            pb.environment()["HOME"] = homeDir.absolutePath
            pb.environment()["PATH"] = "${prefixDir.absolutePath}/bin:/system/bin"
            pb.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
            val started = pb.start()
            proc = started
            runCatching { started.outputStream.close() }
            val hasOutput = AtomicBoolean(false)
            val drainer = Thread {
                runCatching {
                    started.inputStream.bufferedReader().use { reader ->
                        val buf = CharArray(256)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            if (!hasOutput.get()) {
                                for (i in 0 until n) {
                                    if (!buf[i].isWhitespace()) {
                                        hasOutput.set(true)
                                        break
                                    }
                                }
                            }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
            readerThread = drainer
            if (!started.waitFor(4, TimeUnit.SECONDS)) {
                return false
            }
            drainer.join(500)
            started.exitValue() == 0 && hasOutput.get()
        } catch (ce: CancellationException) {
            throw ce
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Throwable) {
            false
        } finally {
            proc?.let { p ->
                if (p.isAlive) p.destroyForcibly()
                runCatching { p.inputStream.close() }
            }
            readerThread?.let { t ->
                runCatching { t.join(250) }
            }
        }
    }

    companion object {
        private const val MAX_SKILL_MD_BYTES = 131_072L
        private val ALLOWED_SUBCOMMANDS = setOf("list", "init", "add", "update", "diff", "push")

        // Strict encrypted-remote allowlist aligned 1-to-1 with bootstrap-custom.sh:
        // - Requires https:// or ssh:// (blocks plaintext http:// MITM and embedded :password@)
        // - Non-dash hostname and non-dash repo path (blocks option injection into git/ssh/basename)
        private val HTTPS_OR_SSH_URL_REGEX =
            Regex("^(https|ssh)://([A-Za-z0-9._-]+@)?[A-Za-z0-9][A-Za-z0-9.-]{0,252}(:[0-9]{1,5})?/[A-Za-z0-9._~][A-Za-z0-9._~/-]{0,199}$")
        private val SCP_GIT_URL_REGEX =
            Regex("^[A-Za-z0-9._-]+@[A-Za-z0-9][A-Za-z0-9.-]{0,252}:[A-Za-z0-9._][A-Za-z0-9._/-]{1,199}$")
        private val SKILL_NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$")

        private val SKILLS_PTY_WRAPPER_SCRIPT = """
            ws_bin="${'$'}1"
            login_bin="${'$'}2"
            bash_bin="${'$'}3"
            sh_bin="${'$'}4"
            shift 4
            if [ -x "${'$'}ws_bin" ]; then
                "${'$'}ws_bin" "${'$'}@" || true
            else
                printf '\033[1;31m[RUR Skills]\033[0m workstation CLI not found. Run bootstrap first.\n'
            fi
            printf '\n'
            if [ -x "${'$'}login_bin" ]; then
                exec "${'$'}login_bin"
            elif [ -x "${'$'}{SHELL:-}" ]; then
                exec "${'$'}SHELL" -l
            elif [ -x "${'$'}bash_bin" ]; then
                exec "${'$'}bash_bin" -l
            else
                exec "${'$'}sh_bin"
            fi
        """.trimIndent()

        fun validateGitUrl(rawUrl: String): Result<String> = runCatching {
            val clean = rawUrl.trim()
            require(clean.isNotEmpty()) { "Ingresa la URL del repositorio Git" }
            require(clean.length <= 240) { "URL demasiado larga (máx. 240 caracteres)" }
            require(!clean.startsWith('-')) { "La URL no puede iniciar con '-'" }
            require(clean.none { it.isISOControl() || it.isWhitespace() }) {
                "La URL no puede contener espacios ni caracteres de control"
            }
            require(!clean.contains("..")) { "URL inválida" }
            require(!clean.startsWith("ext::", ignoreCase = true) && !clean.startsWith("file:", ignoreCase = true)) {
                "Solo se permiten repositorios remotos https://, ssh:// o git@host:org/repo.git"
            }
            require(HTTPS_OR_SSH_URL_REGEX.matches(clean) || SCP_GIT_URL_REGEX.matches(clean)) {
                "URL Git inválida (usa https://github.com/usuario/repo.git o git@github.com:usuario/repo.git)"
            }
            clean
        }

        fun validateSkillName(rawName: String): Result<String> = runCatching {
            val clean = rawName.trim()
            require(clean.isNotEmpty()) { "Nombre de skill vacío" }
            require(!clean.startsWith('-') && !clean.contains("..") && !clean.contains('/') && !clean.contains('\\')) {
                "Nombre de carpeta inválido"
            }
            require(SKILL_NAME_REGEX.matches(clean)) {
                "Usa solo letras, números, '.', '_' o '-' (máx. 48 caracteres)"
            }
            clean
        }
    }
}
