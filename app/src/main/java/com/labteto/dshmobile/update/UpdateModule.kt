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

    @Binds
    abstract fun selfUpdateHooks(impl: com.labteto.dshmobile.runtime.ResumeAfterUpdateHooks): SelfUpdateHooks
}

/** What else has to happen right before this app hands its own update to the system, and when that fails. */
interface SelfUpdateHooks {
    /** Runs just before the install session is committed; the process may end right after. */
    suspend fun beforeInstall()

    /** The install was refused or cancelled; this process goes on. */
    fun installFailed()
}
