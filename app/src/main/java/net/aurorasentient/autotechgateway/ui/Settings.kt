package net.aurorasentient.autotechgateway.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * App settings stored in DataStore (equivalent of config.json in the Windows gateway).
 */

private val Context.dataStore by preferencesDataStore(name = "gateway_settings")

object SettingsKeys {
    val SHOP_ID = stringPreferencesKey("shop_id")
    val API_KEY = stringPreferencesKey("api_key")
    val SERVER_URL = stringPreferencesKey("server_url")
    val LAST_ADAPTER_ADDRESS = stringPreferencesKey("last_adapter_address")
    val LAST_CONNECTION_TYPE = stringPreferencesKey("last_connection_type")
    val WIFI_HOST = stringPreferencesKey("wifi_host")
    val WIFI_PORT = stringPreferencesKey("wifi_port")
    val AUTO_CONNECT = stringPreferencesKey("auto_connect")
    val AUTO_TUNNEL = stringPreferencesKey("auto_tunnel")
}

data class AppSettings(
    val shopId: String = "",
    val apiKey: String = "",
    val serverUrl: String = "https://automotive.aurora-sentient.net",
    val lastAdapterAddress: String = "",
    val lastConnectionType: String = "",
    val wifiHost: String = "192.168.0.10",
    val wifiPort: String = "35000",
    val autoConnect: Boolean = false,
    val autoTunnel: Boolean = false
)

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            shopId = prefs[SettingsKeys.SHOP_ID] ?: "",
            apiKey = prefs[SettingsKeys.API_KEY] ?: "",
            serverUrl = prefs[SettingsKeys.SERVER_URL] ?: "https://automotive.aurora-sentient.net",
            lastAdapterAddress = prefs[SettingsKeys.LAST_ADAPTER_ADDRESS] ?: "",
            lastConnectionType = prefs[SettingsKeys.LAST_CONNECTION_TYPE] ?: "",
            wifiHost = prefs[SettingsKeys.WIFI_HOST] ?: "192.168.0.10",
            wifiPort = prefs[SettingsKeys.WIFI_PORT] ?: "35000",
            autoConnect = prefs[SettingsKeys.AUTO_CONNECT] == "true",
            autoTunnel = prefs[SettingsKeys.AUTO_TUNNEL] == "true"
        )
    }

    suspend fun updateShopId(shopId: String) {
        context.dataStore.edit { it[SettingsKeys.SHOP_ID] = shopId }
    }

    suspend fun updateApiKey(apiKey: String) {
        context.dataStore.edit { it[SettingsKeys.API_KEY] = apiKey }
    }

    suspend fun updateServerUrl(url: String) {
        context.dataStore.edit { it[SettingsKeys.SERVER_URL] = url }
    }

    suspend fun updateLastAdapter(address: String, type: String) {
        context.dataStore.edit {
            it[SettingsKeys.LAST_ADAPTER_ADDRESS] = address
            it[SettingsKeys.LAST_CONNECTION_TYPE] = type
        }
    }

    suspend fun updateWifiSettings(host: String, port: String) {
        context.dataStore.edit {
            it[SettingsKeys.WIFI_HOST] = host
            it[SettingsKeys.WIFI_PORT] = port
        }
    }

    suspend fun updateWifiHost(host: String) {
        context.dataStore.edit { it[SettingsKeys.WIFI_HOST] = host }
    }

    suspend fun updateWifiPort(port: Int) {
        context.dataStore.edit { it[SettingsKeys.WIFI_PORT] = port.toString() }
    }

    suspend fun updateAutoConnect(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.AUTO_CONNECT] = enabled.toString() }
    }

    suspend fun updateAutoTunnel(enabled: Boolean) {
        context.dataStore.edit { it[SettingsKeys.AUTO_TUNNEL] = enabled.toString() }
    }
}
