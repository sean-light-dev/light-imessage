package com.thelightphone.sdk

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.thelightphone.sdk.shared.LightConstants
import com.thelightphone.sdk.shared.LightServerPushCredentials
import kotlin.time.Instant
import kotlinx.coroutines.flow.map
import org.unifiedpush.android.connector.data.PushEndpoint

private val endpointKey = stringPreferencesKey("PUSH_ENDPOINT")
private val registrationDateKey = longPreferencesKey("PUSH_REGISTRATION")
private val instanceKey = stringPreferencesKey("PUSH_INSTANCE")

@OptIn(kotlin.time.ExperimentalTime::class)
class LightPushManager(private val context: Context) {
    private val dataStore by lazy { context.dataStore }

    val pushCredentialsFlow
        get() =
                dataStore.data.map {
                    val endpoint = it[endpointKey] ?: return@map null
                    val registrationDate =
                            it[registrationDateKey]?.run { Instant.fromEpochMilliseconds(this) }
                                    ?: return@map null
                    LightServerPushCredentials(
                            pushEndpoint = endpoint,
                            pushRegistrationDate = registrationDate,
                            instance = it[instanceKey] ?: LightConstants.PUSH_INSTANCE_REMOTE,
                            token = endpoint.substringAfterLast('/').takeIf { it.isNotEmpty() },
                            distributorPackage = context.packageName,
                    )
                }

    suspend fun updatePushCredentials(pushEndpoint: PushEndpoint, instance: String) {
        // TODO save pubKeySet
        dataStore.edit { prefs ->
            prefs[endpointKey] = pushEndpoint.url
            prefs[registrationDateKey] = System.currentTimeMillis()
            prefs[instanceKey] = instance
        }
    }

    suspend fun clearPushCredentials() {
        dataStore.edit { prefs ->
            prefs.remove(endpointKey)
            prefs.remove(registrationDateKey)
            prefs.remove(instanceKey)
        }
    }
}
