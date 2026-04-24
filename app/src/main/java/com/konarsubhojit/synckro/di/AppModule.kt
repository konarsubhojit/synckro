package com.konarsubhojit.synckro.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.konarsubhojit.synckro.data.local.dao.AccountDao
import com.konarsubhojit.synckro.data.local.dao.FileIndexDao
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.data.local.db.SynckroDatabase
import com.konarsubhojit.synckro.data.worker.SyncScheduler
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.sync.SyncEngine
import com.konarsubhojit.synckro.providers.gdrive.GoogleDriveAuthManager
import com.konarsubhojit.synckro.providers.onedrive.OneDriveAuthManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the application's Room-backed SynckroDatabase instance.
     *
     * In debug builds the database builder is configured to fallback to destructive migrations; release builds require explicit migrations and will not drop existing data.
     *
     * @param ctx Application context used to construct the database.
     * @return The constructed SynckroDatabase.
     */
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

    /**
 * Provides the SyncPairDao associated with the given SynckroDatabase.
 *
 * @return The SyncPairDao instance retrieved from the database.
 */
@Provides fun provideSyncPairDao(db: SynckroDatabase): SyncPairDao = db.syncPairDao()

    /**
 * Provides the AccountDao associated with the given SynckroDatabase.
 *
 * @return The AccountDao instance retrieved from the database.
 */
@Provides fun provideAccountDao(db: SynckroDatabase): AccountDao = db.accountDao()

    /**
 * Provides the DAO for accessing file index records from the database.
 *
 * @return The FileIndexDao instance from the provided SynckroDatabase.
 */
@Provides fun provideFileIndexDao(db: SynckroDatabase): FileIndexDao = db.fileIndexDao()

    /**
         * Provides the application WorkManager instance.
         *
         * @param ctx The application Context used to obtain the WorkManager.
         * @return The WorkManager instance for the provided application context.
         */
        @Provides @Singleton
    fun provideWorkManager(@ApplicationContext ctx: Context): WorkManager =
        WorkManager.getInstance(ctx)

    /**
         * Provides a SyncScheduler that orchestrates synchronization tasks using the supplied WorkManager.
         *
         * @return The created SyncScheduler instance.
         */
        @Provides @Singleton
    fun provideSyncScheduler(workManager: WorkManager): SyncScheduler =
        SyncScheduler(workManager)

    /**
     * Provides the application's synchronization engine used to coordinate sync tasks.
     *
     * @return A `SyncEngine` instance used to perform and manage synchronization operations.
     */
    @Provides @Singleton
    fun provideSyncEngine(): SyncEngine = SyncEngine()

    @Provides @IntoMap @CloudProviderKey(CloudProviderType.ONEDRIVE) @Singleton
    fun provideOneDriveAuthManager(impl: OneDriveAuthManager): AuthManager = impl

    @Provides @IntoMap @CloudProviderKey(CloudProviderType.GOOGLE_DRIVE) @Singleton
    fun provideGoogleDriveAuthManager(impl: GoogleDriveAuthManager): AuthManager = impl
}
