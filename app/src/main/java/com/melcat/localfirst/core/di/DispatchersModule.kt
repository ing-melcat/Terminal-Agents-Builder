package com.melcat.localfirst.core.di

import com.melcat.localfirst.core.common.DefaultDispatchersProvider
import com.melcat.localfirst.core.common.DispatchersProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DispatchersModule {
    @Binds
    @Singleton
    abstract fun bindDispatchersProvider(
        impl: DefaultDispatchersProvider,
    ): DispatchersProvider
}
