package com.melcat.localfirst.core.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.melcat.localfirst.core.database.AppDatabase
import com.melcat.localfirst.feature.agents.data.TerminalAgentDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME,
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()

    @Provides
    @Singleton
    fun provideTerminalAgentDao(
        database: AppDatabase,
    ): TerminalAgentDao = database.terminalAgentDao()
}
