package com.synckro.di

import android.content.ContentResolver
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.work.WorkManager
import com.synckro.data.local.dao.AccountDao
import com.synckro.data.local.dao.ConflictRecordDao
import com.synckro.data.local.dao.FileIndexDao
import com.synckro.data.local.dao.LocalIndexDao
import com.synckro.data.local.dao.SyncEventDao
import com.synckro.data.local.dao.SyncPairDao
import com.synckro.data.local.db.SynckroDatabase
import com.synckro.data.local.fs.LocalFsEnumerator
import com.synckro.data.local.fs.SafLocalFileAccess
import com.synckro.data.repository.ConflictRepository
import com.synckro.data.repository.SyncEventRepository
import com.synckro.data.scanner.LocalFolderScannerImpl
import com.synckro.data.worker.SyncScheduler
import com.synckro.domain.auth.AuthManager
import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.domain.scan.LocalFolderScanner
import com.synckro.domain.sync.RemoteEnumerator
import com.synckro.domain.sync.SyncEngine
import com.synckro.providers.fake.FakeCloudProvider
import com.synckro.providers.gdrive.GoogleDriveAuthManager
import com.synckro.providers.onedrive.OneDriveCacheCompatibilityChecker
import com.synckro.providers.onedrive.OneDriveAuthManager
import com.synckro.util.ContextStringProvider
import com.synckro.util.StringProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    fun provideDatabase(
        @ApplicationContext ctx: Context,
    ): SynckroDatabase {
        val builder =
            Room
                .databaseBuilder(ctx, SynckroDatabase::class.java, SynckroDatabase.NAME)
                .addMigrations(
                    SynckroDatabase.MIGRATION_1_2,
                    SynckroDatabase.MIGRATION_2_3,
                    SynckroDatabase.MIGRATION_3_4,
                    SynckroDatabase.MIGRATION_4_5,
                    SynckroDatabase.MIGRATION_5_6,
                    SynckroDatabase.MIGRATION_6_7,
                    SynckroDatabase.MIGRATION_7_8,
                    SynckroDatabase.MIGRATION_8_9,
                    SynckroDatabase.MIGRATION_9_10,
                    SynckroDatabase.MIGRATION_10_11,
                    SynckroDatabase.MIGRATION_11_12,
                    SynckroDatabase.MIGRATION_12_13,
                )
        // Destructive fallback is only acceptable while the schema is still
        // pre-1.0. In release builds we refuse to drop user sync state and
        // require explicit migrations.
        if (com.synckro.BuildConfig.DEBUG) {
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

    @Provides
    fun provideConflictRecordDao(db: SynckroDatabase): ConflictRecordDao = db.conflictRecordDao()

    /**
     * Provides the DAO used to access and modify local-index entries.
     *
     * @return The [LocalIndexDao] instance retrieved from the database.
     */
    @Provides
    fun provideLocalIndexDao(db: SynckroDatabase): LocalIndexDao = db.localIndexDao()

    @Provides @Singleton
    fun provideFakeCloudProvider(): FakeCloudProvider = FakeCloudProvider()

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
    fun provideWorkManager(
        @ApplicationContext ctx: Context,
    ): WorkManager = WorkManager.getInstance(ctx)

    /**
     * Provides a SyncScheduler that orchestrates synchronization tasks using the supplied WorkManager.
     *
     * @return The created SyncScheduler instance.
     */
    @Provides @Singleton
    fun provideSyncScheduler(workManager: WorkManager): SyncScheduler = SyncScheduler(workManager)

    /**
     * Provides the singleton [DataStore]<[Preferences]> used by [SettingsRepository].
     *
     * Using [PreferenceDataStoreFactory] rather than the `preferencesDataStore` property
     * delegate keeps the DataStore strictly singleton within the Hilt component — the
     * delegate would create a fresh instance each time the extension property is accessed
     * on a new [Context] in a non-component-scoped context.
     */
    @Provides @Singleton
    fun provideSettingsDataStore(
        @ApplicationContext ctx: Context,
    ): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { ctx.preferencesDataStoreFile("settings") },
        )

    /**
     * Provides the application's synchronization engine used to coordinate sync tasks.
     *
     * Wires the full [runReal] pipeline: [LocalFsEnumerator], the multibound
     * [RemoteEnumerator] map, [SyncPairDao], [LocalIndexDao], [SyncEventRepository],
     * and a [SafLocalFileAccess] factory that creates a file-access instance scoped
     * to each sync pair's SAF tree URI at the start of every sync run.
     *
     * @return A `SyncEngine` instance used to perform and manage synchronization operations.
     */
    @Provides @Singleton
    fun provideSyncEngine(
        @ApplicationContext ctx: Context,
        conflictRepository: ConflictRepository,
        providers: Map<CloudProviderType, @JvmSuppressWildcards CloudProviderFactory>,
        localFsEnumerator: LocalFsEnumerator,
        remoteEnumerators: Map<CloudProviderType, @JvmSuppressWildcards RemoteEnumerator>,
        syncPairDao: SyncPairDao,
        localIndexDao: LocalIndexDao,
        eventRepository: SyncEventRepository,
    ): SyncEngine {
        val resolver: ContentResolver = ctx.contentResolver
        return SyncEngine(
            conflictRepository = conflictRepository,
            providers = providers,
            localFsEnumerator = localFsEnumerator,
            remoteEnumerators = remoteEnumerators,
            syncPairDao = syncPairDao,
            localIndexDao = localIndexDao,
            eventRepository = eventRepository,
            localFileAccess = { treeUri -> SafLocalFileAccess(resolver, treeUri) },
        )
    }

    @Provides @IntoMap
    @CloudProviderKey(CloudProviderType.ONEDRIVE)
    @Singleton
    fun provideOneDriveAuthManager(impl: OneDriveAuthManager): AuthManager = impl

    @Provides @IntoMap
    @CloudProviderKey(CloudProviderType.GOOGLE_DRIVE)
    @Singleton
    fun provideGoogleDriveAuthManager(impl: GoogleDriveAuthManager): AuthManager = impl

    @Provides @Singleton
    fun provideOneDriveCacheCompatibilityChecker(impl: OneDriveAuthManager): OneDriveCacheCompatibilityChecker = impl

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
