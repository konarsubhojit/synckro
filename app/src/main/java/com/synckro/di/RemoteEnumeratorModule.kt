package com.synckro.di

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.sync.RemoteEnumerator
import com.synckro.providers.fake.FakeRemoteEnumerator
import com.synckro.providers.gdrive.GoogleDriveRemoteEnumerator
import com.synckro.providers.onedrive.OneDriveRemoteEnumerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

/**
 * Hilt module that contributes all [RemoteEnumerator] implementations into a
 * multibound `Map<CloudProviderType, RemoteEnumerator>`. The sync engine
 * resolves the right enumerator at run time based on the active
 * [com.synckro.domain.model.SyncPair]'s provider.
 */
@Module
@InstallIn(SingletonComponent::class)
object RemoteEnumeratorModule {
    @Provides @IntoMap
    @CloudProviderKey(CloudProviderType.FAKE)
    @Singleton
    fun provideFakeRemoteEnumerator(impl: FakeRemoteEnumerator): RemoteEnumerator = impl

    @Provides @IntoMap
    @CloudProviderKey(CloudProviderType.GOOGLE_DRIVE)
    @Singleton
    fun provideGoogleDriveRemoteEnumerator(impl: GoogleDriveRemoteEnumerator): RemoteEnumerator = impl

    @Provides @IntoMap
    @CloudProviderKey(CloudProviderType.ONEDRIVE)
    @Singleton
    fun provideOneDriveRemoteEnumerator(impl: OneDriveRemoteEnumerator): RemoteEnumerator = impl
}
