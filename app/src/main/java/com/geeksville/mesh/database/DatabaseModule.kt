package com.geeksville.mesh.database

import android.app.Application
import com.geeksville.mesh.database.dao.MeshLogDao
import com.geeksville.mesh.database.dao.QuickChatActionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
class DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(app: Application): MeshtasticDatabase =
        MeshtasticDatabase.getDatabase(app)

    @Provides
    fun provideMeshLogDao(database: MeshtasticDatabase): MeshLogDao {
        return database.meshLogDao()
    }

    @Provides
    fun provideQuickChatActionDao(database: MeshtasticDatabase): QuickChatActionDao {
        return database.quickChatActionDao()
    }
}