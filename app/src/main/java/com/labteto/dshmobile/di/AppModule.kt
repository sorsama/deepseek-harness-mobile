package com.labteto.dshmobile.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.labteto.dshmobile.connection.ConnectionManager
import com.labteto.dshmobile.connection.HostsStore
import com.labteto.dshmobile.core.wire.WireJson
import com.labteto.dshmobile.data.SessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "dsh_mobile")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWireJson(): Json = WireJson

    @Provides
    fun provideIoDispatcher(): kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO

    /**
     * The process-wide session store. Built here rather than by an `@Inject` constructor because the store itself is
     * platform-neutral (it moves to `:sharedUi` for iOS): Android hands it its resources, preferences and log through
     * [AndroidStorePlatform], the connection as `HarnessConnection`, and `HostsStore` as its `LastSessionStore`.
     */
    @Provides
    @Singleton
    fun provideSessionStore(
        @ApplicationContext context: Context,
        connection: ConnectionManager,
        hostsStore: HostsStore,
    ): SessionStore = SessionStore(AndroidStorePlatform(context), connection, hostsStore)
}
