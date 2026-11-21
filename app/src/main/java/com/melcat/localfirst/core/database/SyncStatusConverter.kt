package com.melcat.localfirst.core.database

import androidx.room.TypeConverter

class SyncStatusConverter {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus =
        SyncStatus.entries.firstOrNull { it.name == value } ?: SyncStatus.PENDING
}
