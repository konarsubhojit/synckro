package com.konarsubhojit.synckro.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.konarsubhojit.synckro.data.local.dao.FileIndexDao
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.data.local.db.SynckroDatabase
import com.konarsubhojit.synckro.data.worker.SyncScheduler
import com.konarsubhojit.synckro.domain.sync.SyncEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): SynckroDatabase {
        val builder = Room.databaseBuilder(ctx, SynckroDatabase::class.java, SynckroDatabase.NAME)
        // Destructive fallback is only acceptable while the schema is still
        // pre-1.0. In release builds we refuse to drop user sync state and
        // require explicit migrations.
        if (com.konarsubhojit.synckro.BuildConfig.DEBUG) {
            @Suppress("DEPRECATION")
            builder.fallbackToDestructiveMigration()
        }
        return builder.build()
    }

    @Provides fun provideSyncPairDao(db: SynckroDatabase): SyncPairDao = db.syncPairDao()

    @Provides fun provideFileIndexDao(db: SynckroDatabase): FileIndexDao = db.fileIndexDao()

    @Provides @Singleton
    fun provideWorkManager(@ApplicationContext ctx: Context): WorkManager =
        WorkManager.getInstance(ctx)

    @Provides @Singleton
    fun provideSyncScheduler(workManager: WorkManager): SyncScheduler =
        SyncScheduler(workManager)

    @Provides @Singleton
    fun provideSyncEngine(): SyncEngine = SyncEngine()
}
