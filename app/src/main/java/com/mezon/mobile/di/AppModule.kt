package com.mezon.mobile.di

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.mezon.mobile.BuildConfig
import com.mezon.mobile.core.NotificationCenter
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences>
    by preferencesDataStore(name = "mezon_session")

private val httpUrlInLogLine = Regex("https?://\\S+")
private val sensitiveHeaderInLogLine = Regex(
    "(?i)^(Authorization|Cookie|Set-Cookie|Proxy-Authorization|X-Api-Key)\\s*:\\s*.+$"
)
private val tokenQueryParam = Regex("(?i)(\\?|&)(token|access_token|refresh_token|id_token)=[^&\\s]*")

private fun formatOkHttpLogLine(message: String): String {
    val redactedHeader = sensitiveHeaderInLogLine.replace(message) { match ->
        val name = match.value.substringBefore(':').trim()
        "$name: ***"
    }
    return httpUrlInLogLine.replace(redactedHeader) { match ->
        val scrubbed = tokenQueryParam.replace(match.value) { m -> "${m.groupValues[1]}${m.groupValues[2]}=***" }
        val uri = runCatching { Uri.parse(scrubbed) }.getOrNull()
        if (uri != null && (uri.scheme == "http" || uri.scheme == "https")) {
            val path = uri.encodedPath.orEmpty().ifEmpty { "/" }
            val host = uri.host?.replace('.', '|') ?: "?"
            "path=$path host=$host"
        } else {
            scrubbed
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @IoDispatcher
    @Singleton
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @MainDispatcher
    @Singleton
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @ApplicationScope
    @Singleton
    fun provideApplicationScope(
        @IoDispatcher io: CoroutineDispatcher
    ): CoroutineScope = CoroutineScope(SupervisorJob() + io)

    @Provides
    @Singleton
    fun provideDataStore(
        @ApplicationContext context: Context
    ): DataStore<Preferences> = context.dataStore

    @Provides
    @Singleton
    fun provideNotificationCenter(): NotificationCenter = NotificationCenter.getInstance(0)

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .addInterceptor(
            okhttp3.logging.HttpLoggingInterceptor { message ->
                Log.d("OkHttp", formatOkHttpLogLine(message))
            }.apply {
                level = if (BuildConfig.DEBUG)
                    okhttp3.logging.HttpLoggingInterceptor.Level.BASIC
                else
                    okhttp3.logging.HttpLoggingInterceptor.Level.NONE
            }
        )
        .build()

    @Provides
    @Singleton
    fun provideHttpClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }
}
