package com.termux.custom.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class AgentSessionRecord(
    val id: String,
    val title: String,
    val agentType: String,
    val workingDir: String,
    val compactedSummary: String,
    val contextTokens: Int,
    val lamportVersion: Long,
    val siteId: String,
    val updatedAt: Long,
    val isDeleted: Boolean,
)

data class CrdtDeltaEntry(
    val deltaId: String,
    val entityId: String,
    val siteId: String,
    val lamportClock: Long,
    val hlcTimestamp: Long,
    val operation: String,
    val payloadJson: String,
)

class LocalFirstCrdtDatabase(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val prefs = context.applicationContext.getSharedPreferences("melcat_crdt_meta", Context.MODE_PRIVATE)

    val siteId: String = prefs.getString(KEY_SITE_ID, null) ?: run {
        val generated = "node-" + UUID.randomUUID().toString().take(8)
        prefs.edit().putString(KEY_SITE_ID, generated).apply()
        generated
    }

    private val _sessionsFlow = MutableStateFlow<List<AgentSessionRecord>>(emptyList())
    val sessionsFlow: StateFlow<List<AgentSessionRecord>> = _sessionsFlow.asStateFlow()

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
        db.execSQL("PRAGMA synchronous = NORMAL;")
        db.execSQL("PRAGMA foreign_keys = ON;")
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS agent_sessions (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                agent_type TEXT NOT NULL,
                working_dir TEXT NOT NULL,
                compacted_summary TEXT NOT NULL,
                context_tokens INTEGER NOT NULL DEFAULT 0,
                lamport_version INTEGER NOT NULL DEFAULT 1,
                site_id TEXT NOT NULL,
                updated_at INTEGER NOT NULL,
                is_deleted INTEGER NOT NULL DEFAULT 0
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_active_updated ON agent_sessions(is_deleted, updated_at DESC);")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sessions_lamport ON agent_sessions(lamport_version);")

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS crdt_delta_log (
                delta_id TEXT PRIMARY KEY NOT NULL,
                entity_id TEXT NOT NULL,
                site_id TEXT NOT NULL,
                lamport_clock INTEGER NOT NULL,
                hlc_timestamp INTEGER NOT NULL,
                operation TEXT NOT NULL,
                payload_json TEXT NOT NULL
            );
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_crdt_delta_clock ON crdt_delta_log(lamport_clock ASC);")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    suspend fun refreshSessions() = withContext(ioDispatcher) {
        _sessionsFlow.value = queryActiveSessionsInternal()
    }

    suspend fun upsertSession(
        id: String = UUID.randomUUID().toString(),
        title: String,
        agentType: String,
        workingDir: String = "/data/data/com.termux/files/home",
        compactedSummary: String,
        contextTokens: Int = 0,
    ): AgentSessionRecord = withContext(ioDispatcher) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val nextLamport = nextLamportClock(db)

        val record = AgentSessionRecord(
            id = id,
            title = title.trim().ifEmpty { "agy-session" },
            agentType = agentType,
            workingDir = workingDir,
            compactedSummary = compactedSummary.trim(),
            contextTokens = contextTokens,
            lamportVersion = nextLamport,
            siteId = siteId,
            updatedAt = now,
            isDeleted = false,
        )

        db.beginTransactionNonExclusive()
        try {
            writeRecordAndDelta(db, record, "UPSERT")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        _sessionsFlow.value = queryActiveSessionsInternal()
        record
    }

    suspend fun softDeleteSession(id: String) = withContext(ioDispatcher) {
        val db = writableDatabase
        val existing = querySessionById(db, id) ?: return@withContext
        val now = System.currentTimeMillis()
        val nextLamport = nextLamportClock(db)
        val tombstone = existing.copy(
            lamportVersion = nextLamport,
            siteId = siteId,
            updatedAt = now,
            isDeleted = true,
        )

        db.beginTransactionNonExclusive()
        try {
            writeRecordAndDelta(db, tombstone, "TOMBSTONE")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        _sessionsFlow.value = queryActiveSessionsInternal()
    }

    suspend fun exportDeltaBundleJson(sinceLamport: Long = 0L): String = withContext(ioDispatcher) {
        val db = readableDatabase
        val arr = JSONArray()
        db.rawQuery(
            "SELECT delta_id, entity_id, site_id, lamport_clock, hlc_timestamp, operation, payload_json FROM crdt_delta_log WHERE lamport_clock > ? ORDER BY lamport_clock ASC",
            arrayOf(sinceLamport.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val obj = JSONObject()
                    .put("deltaId", cursor.getString(0))
                    .put("entityId", cursor.getString(1))
                    .put("siteId", cursor.getString(2))
                    .put("lamportClock", cursor.getLong(3))
                    .put("hlcTimestamp", cursor.getLong(4))
                    .put("operation", cursor.getString(5))
                    .put("payload", JSONObject(cursor.getString(6)))
                arr.put(obj)
            }
        }
        JSONObject()
            .put("siteId", siteId)
            .put("exportedAt", System.currentTimeMillis())
            .put("deltas", arr)
            .toString(2)
    }

    suspend fun mergeRemoteDeltaBundleJson(bundleJson: String): Int = withContext(ioDispatcher) {
        val root = JSONObject(bundleJson)
        val deltas = root.optJSONArray("deltas") ?: return@withContext 0
        val db = writableDatabase
        var applied = 0

        db.beginTransactionNonExclusive()
        try {
            for (i in 0 until deltas.length()) {
                val item = deltas.getJSONObject(i)
                val payload = item.getJSONObject("payload")
                val incoming = AgentSessionRecord(
                    id = payload.getString("id"),
                    title = payload.getString("title"),
                    agentType = payload.optString("agentType", "agy"),
                    workingDir = payload.optString("workingDir", "/data/data/com.termux/files/home"),
                    compactedSummary = payload.optString("compactedSummary", ""),
                    contextTokens = payload.optInt("contextTokens", 0),
                    lamportVersion = payload.getLong("lamportVersion"),
                    siteId = payload.getString("siteId"),
                    updatedAt = payload.getLong("updatedAt"),
                    isDeleted = payload.optBoolean("isDeleted", false),
                )

                val local = querySessionById(db, incoming.id)
                val shouldApply = if (local == null) {
                    true
                } else if (incoming.lamportVersion != local.lamportVersion) {
                    incoming.lamportVersion > local.lamportVersion
                } else if (incoming.updatedAt != local.updatedAt) {
                    incoming.updatedAt > local.updatedAt
                } else {
                    incoming.siteId > local.siteId
                }

                if (shouldApply) {
                    writeRecordAndDelta(db, incoming, "MERGE_LWW")
                    applied++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        _sessionsFlow.value = queryActiveSessionsInternal()
        applied
    }

    suspend fun exportDeltaBundleToFile(targetDir: File): File = withContext(ioDispatcher) {
        targetDir.mkdirs()
        val file = File(targetDir, "crdt-deltas-$siteId-${System.currentTimeMillis()}.json")
        file.writeText(exportDeltaBundleJson(0L))
        file
    }

    private fun writeRecordAndDelta(
        db: SQLiteDatabase,
        record: AgentSessionRecord,
        operation: String,
    ) {
        val values = ContentValues().apply {
            put("id", record.id)
            put("title", record.title)
            put("agent_type", record.agentType)
            put("working_dir", record.workingDir)
            put("compacted_summary", record.compactedSummary)
            put("context_tokens", record.contextTokens)
            put("lamport_version", record.lamportVersion)
            put("site_id", record.siteId)
            put("updated_at", record.updatedAt)
            put("is_deleted", if (record.isDeleted) 1 else 0)
        }
        db.insertWithOnConflict("agent_sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE)

        val payloadJson = JSONObject()
            .put("id", record.id)
            .put("title", record.title)
            .put("agentType", record.agentType)
            .put("workingDir", record.workingDir)
            .put("compactedSummary", record.compactedSummary)
            .put("contextTokens", record.contextTokens)
            .put("lamportVersion", record.lamportVersion)
            .put("siteId", record.siteId)
            .put("updatedAt", record.updatedAt)
            .put("isDeleted", record.isDeleted)
            .toString()

        val deltaValues = ContentValues().apply {
            put("delta_id", "${record.siteId}:${record.lamportVersion}:${record.id}")
            put("entity_id", record.id)
            put("site_id", record.siteId)
            put("lamport_clock", record.lamportVersion)
            put("hlc_timestamp", record.updatedAt)
            put("operation", operation)
            put("payload_json", payloadJson)
        }
        db.insertWithOnConflict("crdt_delta_log", null, deltaValues, SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun nextLamportClock(db: SQLiteDatabase): Long {
        db.rawQuery("SELECT COALESCE(MAX(lamport_clock), 0) + 1 FROM crdt_delta_log", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getLong(0) else 1L
        }
    }

    private fun querySessionById(db: SQLiteDatabase, id: String): AgentSessionRecord? {
        db.rawQuery(
            "SELECT id, title, agent_type, working_dir, compacted_summary, context_tokens, lamport_version, site_id, updated_at, is_deleted FROM agent_sessions WHERE id = ? LIMIT 1",
            arrayOf(id),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AgentSessionRecord(
                id = cursor.getString(0),
                title = cursor.getString(1),
                agentType = cursor.getString(2),
                workingDir = cursor.getString(3),
                compactedSummary = cursor.getString(4),
                contextTokens = cursor.getInt(5),
                lamportVersion = cursor.getLong(6),
                siteId = cursor.getString(7),
                updatedAt = cursor.getLong(8),
                isDeleted = cursor.getInt(9) != 0,
            )
        }
    }

    private fun queryActiveSessionsInternal(): List<AgentSessionRecord> {
        val db = readableDatabase
        val list = mutableListOf<AgentSessionRecord>()
        db.rawQuery(
            "SELECT id, title, agent_type, working_dir, compacted_summary, context_tokens, lamport_version, site_id, updated_at, is_deleted FROM agent_sessions WHERE is_deleted = 0 ORDER BY updated_at DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(
                    AgentSessionRecord(
                        id = cursor.getString(0),
                        title = cursor.getString(1),
                        agentType = cursor.getString(2),
                        workingDir = cursor.getString(3),
                        compactedSummary = cursor.getString(4),
                        contextTokens = cursor.getInt(5),
                        lamportVersion = cursor.getLong(6),
                        siteId = cursor.getString(7),
                        updatedAt = cursor.getLong(8),
                        isDeleted = cursor.getInt(9) != 0,
                    )
                )
            }
        }
        return list
    }

    companion object {
        private const val DB_NAME = "melcat_localfirst_crdt.db"
        private const val DB_VERSION = 1
        private const val KEY_SITE_ID = "crdt_site_id"
    }
}
