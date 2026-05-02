package com.synckro.di

import com.synckro.domain.model.CloudProviderType
import dagger.MapKey

/** Dagger map key for multibindings keyed by [CloudProviderType]. */
@MapKey
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.RUNTIME)
annotation class CloudProviderKey(val value: CloudProviderType)
