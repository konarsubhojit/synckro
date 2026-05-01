package com.konarsubhojit.synckro.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.konarsubhojit.synckro.data.local.dao.AccountDao
import com.konarsubhojit.synckro.data.local.dao.FileIndexDao
import com.konarsubhojit.synckro.data.local.dao.SyncEventDao
import com.konarsubhojit.synckro.data.local.dao.SyncPairDao
import com.konarsubhojit.synckro.data.local.db.SynckroDatabase
import com.konarsubhojit.synckro.data.scanner.LocalFolderScannerImpl
import com.konarsubhojit.synckro.data.worker.SyncScheduler
import com.konarsubhojit.synckro.domain.auth.AuthManager
import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.scan.LocalFolderScanner
import com.konarsubhojit.synckro.domain.sync.SyncEngine
import com.konarsubhojit.synckro.providers.gdrive.GoogleDriveAuthManager
import com.konarsubhojit.synckro.providers.onedrive.OneDriveAuthManager
import com.konarsubhojit.synckro.util.ContextStringProvider
import com.konarsubhojit.synckro.util.StringProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Provides the application's Room-backed SynckroDatabase instance.
     *
     * In debug builds the database builder is configured to fallback to destructive migrations;
     * release builds use explicit migrations and will not drop existing data.
     *
     * @param ctx Application context used to construct the database.
     * @return The constructed SynckroDatabase.
     */
    @Provides @Singleton
    fun provideDatabase(@ApplicationContext ctx: Context): SynckroDatabase {
        val builder = Room.databaseBuilder(ctx, SynckroDatabase::class.java, SynckroDatabase.NAME)
            .addMigrations(
                SynckroDatabase.MIGRATION_1_2,
                SynckroDatabase.MIGRATION_2_3,
                SynckroDatabase.MIGRATION_3_4,
                SynckroDatabase.MIGRATION_4_5,
            )
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
    @Provides
    fun provideSyncPairDao(db: SynckroDatabase): SyncPairDao = db.syncPairDao()

    /**
     * Provides the AccountDao associated with the given SynckroDatabase.
     *
     * @return The AccountDao instance retrieved from the database.
     */
    @Provides
    fun provideAccountDao(db: SynckroDatabase): AccountDao = db.accountDao()

    /**
     * Provides the DAO for accessing file index records from the database.
     *
     * @return The FileIndexDao instance from the provided SynckroDatabase.
     */
    @Provides
    fun provideFileIndexDao(db: SynckroDatabase): FileIndexDao = db.fileIndexDao()

    /**
     * Provides the DAO for reading and writing structured sync-event log entries.
     *
     * @return The [SyncEventDao] from the provided [SynckroDatabase].
     */
    @Provides
    fun provideSyncEventDao(db: SynckroDatabase): SyncEventDao = db.syncEventDao()

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

    @Provides @Singleton
    fun provideStringProvider(impl: ContextStringProvider): StringProvider = impl

    /**
     * Provides the shared [OkHttpClient] used by all network components (OneDrive Graph API, …).
     *
     * @return A singleton [OkHttpClient] with default settings.
     */
    @Provides @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient()

    /**
     * Provides the [LocalFolderScanner] that walks SAF document trees and reconciles
     * the result with the Room file index.
     *
     * @return A [LocalFolderScannerImpl] backed by DocumentsContract.
     */
    @Provides @Singleton
    fun provideLocalFolderScanner(impl: LocalFolderScannerImpl): LocalFolderScanner = impl
}
