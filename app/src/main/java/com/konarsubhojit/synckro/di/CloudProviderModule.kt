package com.konarsubhojit.synckro.di

import com.konarsubhojit.synckro.domain.model.CloudProviderType
import com.konarsubhojit.synckro.domain.provider.CloudProvider
import com.konarsubhojit.synckro.providers.fake.FakeCloudProvider
import com.konarsubhojit.synckro.providers.gdrive.GoogleDriveProvider
import com.konarsubhojit.synckro.providers.onedrive.OneDriveProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

/**
 * Hilt module that contributes all [CloudProvider] implementations into a
 * multibound `Map<CloudProviderType, CloudProvider>`. [SyncEngine] injects this
 * map and resolves the right provider at run time based on [SyncPair.provider].
 */
@Module
@InstallIn(SingletonComponent::class)
object CloudProviderModule {

    @Provides @IntoMap @CloudProviderKey(CloudProviderType.FAKE) @Singleton
    fun provideFakeCloudProvider(impl: FakeCloudProvider): CloudProvider = impl

    @Provides @IntoMap @CloudProviderKey(CloudProviderType.GOOGLE_DRIVE) @Singleton
    fun provideGoogleDriveProvider(impl: GoogleDriveProvider): CloudProvider = impl

    @Provides @IntoMap @CloudProviderKey(CloudProviderType.ONEDRIVE) @Singleton
    fun provideOneDriveProvider(impl: OneDriveProvider): CloudProvider = impl
}
