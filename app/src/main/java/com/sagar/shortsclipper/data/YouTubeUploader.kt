package com.sagar.shortsclipper.data

import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * YouTube auth + upload using the OAuth 2.0 Device Flow (the "TV / limited input" flow).
 *
 * Why device flow: it doesn't bind to the app's signing SHA-1, so it keeps working no
 * matter how the APK is signed (e.g. on CI), and needs no extra Google SDK — just HTTP.
 * The broad `youtube` scope (supported by the device flow) is accepted by videos.insert.
 *
 * All methods are blocking; call them from a background dispatcher.
 */
object YouTubeUploader {

    private const val SCOPE = "https://www.googleapis.com/auth/youtube"
    private const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
    private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    private const val CHANNELS_URL =
        "https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true"
    private const val UPLOAD_URL =
        "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status"

    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.MINUTES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()

    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val intervalSec: Int,
        val expiresInSec: Int
    )

    data class Tokens(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSec: Int
    )

    sealed interface PollOutcome {
        object Pending : PollOutcome
        object SlowDown : PollOutcome
        data class Authorized(val tokens: Tokens) : PollOutcome
        data class Failed(val message: String) : PollOutcome
    }

    /** Step 1: ask Google for a device + user code. */
    fun requestDeviceCode(clientId: String): DeviceCode {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("scope", SCOPE)
            .build()
        val request = Request.Builder().url(DEVICE_CODE_URL).post(body).build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Couldn't start sign-in (${resp.code}): ${errorMessage(raw)}")
            }
            val o = JSONObject(raw)
            val verification = if (o.has("verification_url")) {
                o.getString("verification_url")
            } else {
                o.optString("verification_uri", "https://www.google.com/device")
            }
            return DeviceCode(
                deviceCode = o.getString("device_code"),
                userCode = o.getString("user_code"),
                verificationUrl = verification,
                intervalSec = o.optInt("interval", 5),
                expiresInSec = o.optInt("expires_in", 1800)
            )
        }
    }

    /** Step 2 (polled): exchange the device code for tokens once the user approves. */
    fun poll(clientId: String, clientSecret: String, deviceCode: String): PollOutcome {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("device_code", deviceCode)
            .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()

        val raw = try {
            client.newCall(request).execute().use { it.body?.string().orEmpty() }
        } catch (e: IOException) {
            return PollOutcome.Pending // transient network blip; keep polling
        }

        val o = try {
            JSONObject(raw)
        } catch (e: Exception) {
            return PollOutcome.Failed("Unexpected response while signing in.")
        }

        if (o.has("access_token")) {
            return PollOutcome.Authorized(
                Tokens(
                    accessToken = o.getString("access_token"),
                    refreshToken = if (o.has("refresh_token")) o.getString("refresh_token") else null,
                    expiresInSec = o.optInt("expires_in", 3600)
                )
            )
        }
        return when (o.optString("error")) {
            "authorization_pending" -> PollOutcome.Pending
            "slow_down" -> PollOutcome.SlowDown
            "access_denied" -> PollOutcome.Failed("Access denied.")
            "expired_token" -> PollOutcome.Failed("The code expired. Connect again.")
            else -> PollOutcome.Failed(
                o.optString("error_description").ifBlank { o.optString("error").ifBlank { "Sign-in failed." } }
            )
        }
    }

    /** Refreshes an access token using the stored refresh token. */
    fun refresh(clientId: String, clientSecret: String, refreshToken: String): Tokens {
        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url(TOKEN_URL).post(body).build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Session expired (${resp.code}). Reconnect YouTube.")
            }
            val o = JSONObject(raw)
            return Tokens(o.getString("access_token"), null, o.optInt("expires_in", 3600))
        }
    }

    /** Returns the signed-in channel's title (best-effort). */
    fun fetchChannelTitle(accessToken: String): String? {
        val request = Request.Builder()
            .url(CHANNELS_URL)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val items = JSONObject(resp.body?.string().orEmpty()).optJSONArray("items")
                if (items == null || items.length() == 0) return null
                items.getJSONObject(0).getJSONObject("snippet").optString("title").ifBlank { null }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Resumable upload of [file]. Returns the new video id. */
    fun upload(
        accessToken: String,
        file: File,
        title: String,
        description: String,
        tags: List<String>,
        privacyStatus: String
    ): String {
        val metadata = JSONObject().apply {
            put(
                "snippet",
                JSONObject().apply {
                    put("title", title.ifBlank { "Untitled Short" }.take(100))
                    put("description", description)
                    put("categoryId", "22") // People & Blogs
                    if (tags.isNotEmpty()) put("tags", JSONArray(tags))
                }
            )
            put(
                "status",
                JSONObject()
                    .put("privacyStatus", privacyStatus)
                    .put("selfDeclaredMadeForKids", false)
            )
        }.toString()

        // 1) Start a resumable session.
        val initRequest = Request.Builder()
            .url(UPLOAD_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("X-Upload-Content-Type", "video/*")
            .header("X-Upload-Content-Length", file.length().toString())
            .post(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .build()

        val sessionUrl = client.newCall(initRequest).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw RuntimeException(
                    "Upload couldn't start (${resp.code}): ${errorMessage(resp.body?.string().orEmpty())}"
                )
            }
            resp.header("Location") ?: throw RuntimeException("No upload URL returned by YouTube.")
        }

        // 2) Send the bytes.
        val putRequest = Request.Builder()
            .url(sessionUrl)
            .header("Authorization", "Bearer $accessToken")
            .put(file.asRequestBody("video/*".toMediaType()))
            .build()

        client.newCall(putRequest).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw RuntimeException("Upload failed (${resp.code}): ${errorMessage(raw)}")
            }
            return JSONObject(raw).optString("id").ifBlank {
                throw RuntimeException("Upload finished but no video id was returned.")
            }
        }
    }

    private fun errorMessage(raw: String): String {
        return try {
            val err = JSONObject(raw).opt("error")
            when (err) {
                is JSONObject -> err.optString("message").ifBlank { raw.take(200) }
                is String -> err
                else -> raw.take(200)
            }
        } catch (e: Exception) {
            raw.take(200)
        }
    }
}
