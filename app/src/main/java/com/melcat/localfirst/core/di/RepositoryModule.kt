package com.melcat.localfirst.core.di

import com.melcat.localfirst.feature.agents.data.TerminalAgentRepositoryImpl
import com.melcat.localfirst.feature.agents.domain.TerminalAgentRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTerminalAgentRepository(
        impl: TerminalAgentRepositoryImpl,
    ): TerminalAgentRepository
}
