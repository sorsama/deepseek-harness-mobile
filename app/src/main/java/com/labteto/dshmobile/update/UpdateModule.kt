package com.labteto.dshmobile.update

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** The updater's seams, bound to their Android implementations. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UpdateModule {
    @Binds
    abstract fun feedSource(impl: DefaultUpdateFeedSource): UpdateFeedSource

    @Binds
    abstract fun apkInspector(impl: PackageManagerApkInspector): ApkInspector

    @Binds
    abstract fun deviceConditions(impl: AndroidDeviceConditions): DeviceConditions

    @Binds
    abstract fun updaterPrefs(impl: SharedUpdaterPrefs): UpdaterPrefs
}
