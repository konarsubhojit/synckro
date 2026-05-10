package com.synckro.di

import com.synckro.domain.model.CloudProviderType
import com.synckro.domain.provider.CloudProviderFactory
import com.synckro.providers.fake.FakeCloudProvider
import com.synckro.providers.gdrive.GoogleDriveProviderFactory
import com.synckro.providers.onedrive.OneDriveProviderFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import javax.inject.Singleton

/**
 * Hilt module that contributes all [CloudProviderFactory] implementations into a
 * multibound `Map<CloudProviderType, CloudProviderFactory>`. [SyncEngine] injects this
 * map and resolves the right provider at run time based on [SyncPair.provider].
 */
@Module
@InstallIn(SingletonComponent::class)
object CloudProviderModule {
    @Provides @IntoMap
    @CloudProviderKey(CloudProviderType.FAKE)
    @Singleton
    fun provideFakeCloudProviderFactory(impl: FakeCloudProvider): CloudProviderFactory =
        object : CloudProviderFactory {
            override fun providerFor(accountId: String) = impl
        }

    @Provides @IntoMap
    @CloudProviderKey(CloudProviderType.GOOGLE_DRIVE)
    @Singleton
    fun provideGoogleDriveProviderFactory(impl: GoogleDriveProviderFactory): CloudProviderFactory = impl

    @Provides @IntoMap
    @CloudProviderKey(CloudProviderType.ONEDRIVE)
    @Singleton
    fun provideOneDriveProviderFactory(impl: OneDriveProviderFactory): CloudProviderFactory = impl
}
