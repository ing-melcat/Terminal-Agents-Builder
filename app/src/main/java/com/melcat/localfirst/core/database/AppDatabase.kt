package com.melcat.localfirst.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import com.melcat.localfirst.feature.agents.data.TerminalAgentDao
import com.melcat.localfirst.feature.agents.data.TerminalAgentEntity

@Database(
    entities = [TerminalAgentEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(SyncStatusConverter::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun terminalAgentDao(): TerminalAgentDao

    companion object {
        const val DATABASE_NAME: String = "terminal_agents_builder.db"

        val ALL_MIGRATIONS: Array<Migration> = emptyArray()
    }
}
