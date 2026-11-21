package com.termux.custom

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.termux.custom.data.LocalFirstCrdtDatabase
import com.termux.custom.security.AntigravityTokenWarden
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
    fun `CRDT database increments Lamport clock, soft-deletes tombstones, and merges remote P2P deltas`() = runTest(testDispatcher) {
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

        // Simulate incoming remote P2P delta from Redmi Note 13 Pro with higher Lamport clock (L#5)
        val remoteDeltaJson = """
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
                    "id": "session-pad6",
                    "title": "agy-pad6-120hz-merged",
                    "agentType": "agy",
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

        val applied = db.mergeRemoteDeltaBundleJson(remoteDeltaJson)
        assertEquals(1, applied)
        val merged = db.sessionsFlow.value.first()
        assertEquals("agy-pad6-120hz-merged", merged.title)
        assertEquals(5L, merged.lamportVersion)

        // Tombstone soft-delete hides from active sessions but records delta in crdt_delta_log
        db.softDeleteSession("session-pad6")
        assertTrue(db.sessionsFlow.value.isEmpty())
        val exportedAfterTombstone = db.exportDeltaBundleJson(0L)
        assertTrue(exportedAfterTombstone.contains("TOMBSTONE"))

        db.close()
    }

    @Test
    fun `AntigravityTokenWarden locks antigravity-oauth-token with 0600 permissions and revokes cleanly`() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val warden = AntigravityTokenWarden(context, testDispatcher)

        val result = warden.provisionOrSyncToken("ya29.a0AfB_byAntigravityOAuthTokenTest998877")
        assertTrue(result.isSuccess)
        val state = result.getOrThrow()
        assertTrue(state.isPresent)
        assertEquals("0600", state.posixModeOctal)
        assertTrue(state.maskedPreview.startsWith("ya29"))

        val revoked = warden.revokeLocalToken()
        assertFalse(revoked.isPresent)
    }
}
