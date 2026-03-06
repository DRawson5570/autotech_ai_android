package net.aurorasentient.autotechgateway.auth

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "AuthClient"

/**
 * Auth response from the server (signin/signup).
 */
data class AuthResponse(
    val id: String,
    val name: String,
    val email: String,
    val role: String,
    val token: String,
    val tokenType: String,
    val expiresAt: Long,
    val profileImageUrl: String
)

/**
 * Result of an auth operation — either success with data or failure with message.
 */
sealed class AuthResult {
    data class Success(val response: AuthResponse) : AuthResult()
    data class Error(val message: String) : AuthResult()
}

/**
 * HTTP client for authenticating against the Autotech AI server.
 * Uses the Open WebUI auth endpoints.
 */
class AuthClient(private val serverUrl: String = "https://automotive.aurora-sentient.net") {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * Sign in with email and password.
     */
    suspend fun signIn(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("email", email.trim().lowercase())
                addProperty("password", password)
            }

            val request = Request.Builder()
                .url("$serverUrl/api/v1/auths/signin")
                .post(gson.toJson(body).toRequestBody(JSON_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseAuthResponse(responseBody)
            } else {
                val detail = extractErrorDetail(responseBody)
                Log.w(TAG, "Sign in failed (${response.code}): $detail")
                AuthResult.Error(detail)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign in error: ${e.message}", e)
            AuthResult.Error("Connection failed. Check your internet connection and try again.")
        }
    }

    /**
     * Sign up with email, name, and password.
     */
    suspend fun signUp(email: String, name: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        try {
            val body = JsonObject().apply {
                addProperty("email", email.trim().lowercase())
                addProperty("name", name.trim())
                addProperty("password", password)
                addProperty("profile_image_url", "")
            }

            val request = Request.Builder()
                .url("$serverUrl/api/v1/auths/signup")
                .post(gson.toJson(body).toRequestBody(JSON_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                parseAuthResponse(responseBody)
            } else {
                val detail = extractErrorDetail(responseBody)
                Log.w(TAG, "Sign up failed (${response.code}): $detail")
                AuthResult.Error(detail)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sign up error: ${e.message}", e)
            AuthResult.Error("Connection failed. Check your internet connection and try again.")
        }
    }

    /**
     * Validate that a stored JWT is still valid by hitting a protected endpoint.
     */
    suspend fun validateToken(token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/api/v1/auths/")
                .get()
                .header("Authorization", "Bearer $token")
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "Token validation failed: ${e.message}")
            false
        }
    }

    private fun parseAuthResponse(responseBody: String): AuthResult {
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject

            // Check for "pending" role — admin hasn't approved yet
            val role = json.get("role")?.asString ?: ""
            if (role == "pending") {
                return AuthResult.Error(
                    "Your account is pending approval. Please contact the shop administrator."
                )
            }

            AuthResult.Success(AuthResponse(
                id = json.get("id")?.asString ?: "",
                name = json.get("name")?.asString ?: "",
                email = json.get("email")?.asString ?: "",
                role = role,
                token = json.get("token")?.asString ?: "",
                tokenType = json.get("token_type")?.asString ?: "Bearer",
                expiresAt = json.get("expires_at")?.asLong ?: 0L,
                profileImageUrl = json.get("profile_image_url")?.asString ?: ""
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse auth response: ${e.message}")
            AuthResult.Error("Unexpected server response. Please try again.")
        }
    }

    private fun extractErrorDetail(responseBody: String): String {
        return try {
            val json = JsonParser.parseString(responseBody).asJsonObject
            json.get("detail")?.asString ?: "Authentication failed"
        } catch (_: Exception) {
            "Authentication failed"
        }
    }
}
