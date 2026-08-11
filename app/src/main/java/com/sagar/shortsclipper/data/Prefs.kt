package com.sagar.shortsclipper.data

import android.content.Context
import android.content.SharedPreferences
import com.sagar.shortsclipper.BuildConfig
import com.sagar.shortsclipper.model.AiProvider
import com.sagar.shortsclipper.model.OutputQuality

/** SharedPreferences wrapper for output quality and per-provider AI settings. */
object Prefs {
    private const val FILE = "shorts_clipper_prefs"
    private const val K_QUALITY = "output_quality"
    private const val K_PROVIDER = "ai_provider"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Writes synchronously. These values are tiny, and an async apply() can be lost
     * when an aggressive OEM launcher kills the process straight from the recents
     * list — which is how saved keys go missing between sessions.
     */
    private fun write(context: Context, block: SharedPreferences.Editor.() -> Unit) {
        val editor = sp(context).edit()
        editor.block()
        editor.commit()
    }

    // ----- Output quality -----

    fun getQuality(context: Context): OutputQuality {
        val name = sp(context).getString(K_QUALITY, OutputQuality.FHD.name)
        return runCatching { OutputQuality.valueOf(name!!) }.getOrDefault(OutputQuality.FHD)
    }

    fun setQuality(context: Context, quality: OutputQuality) =
        write(context) { putString(K_QUALITY, quality.name) }

    // ----- AI provider -----

    fun getProvider(context: Context): AiProvider {
        val name = sp(context).getString(K_PROVIDER, AiProvider.GEMINI.name)
        return runCatching { AiProvider.valueOf(name!!) }.getOrDefault(AiProvider.GEMINI)
    }

    fun setProvider(context: Context, provider: AiProvider) =
        write(context) { putString(K_PROVIDER, provider.name) }

    /**
     * Per-provider key. For Gemini, falls back to the key baked in at build time
     * (env var / local.properties) when nothing is entered in-app.
     */
    fun getApiKey(context: Context, provider: AiProvider): String {
        val saved = sp(context).getString("api_${provider.name}", "").orEmpty()
        if (saved.isNotBlank()) return saved
        return if (provider == AiProvider.GEMINI) BuildConfig.GEMINI_API_KEY else ""
    }

    fun setApiKey(context: Context, provider: AiProvider, value: String) =
        write(context) { putString("api_${provider.name}", value) }

    /** Per-provider model; defaults to the provider's default when unset. */
    fun getModel(context: Context, provider: AiProvider): String {
        val saved = sp(context).getString("model_${provider.name}", "").orEmpty()
        return if (saved.isNotBlank()) saved else provider.defaultModel
    }

    fun setModel(context: Context, provider: AiProvider, value: String) =
        write(context) { putString("model_${provider.name}", value) }

    // ----- YouTube (OAuth device flow) -----

    private const val K_YT_CLIENT_ID = "yt_client_id"
    private const val K_YT_CLIENT_SECRET = "yt_client_secret"
    private const val K_YT_REFRESH = "yt_refresh_token"
    private const val K_YT_ACCESS = "yt_access_token"
    private const val K_YT_ACCESS_EXP = "yt_access_expiry"
    private const val K_YT_CHANNEL = "yt_channel_title"
    private const val K_YT_PRIVACY = "yt_privacy"

    fun getYtClientId(context: Context) = sp(context).getString(K_YT_CLIENT_ID, "").orEmpty()
    fun setYtClientId(context: Context, v: String) =
        write(context) { putString(K_YT_CLIENT_ID, v.trim()) }

    fun getYtClientSecret(context: Context) = sp(context).getString(K_YT_CLIENT_SECRET, "").orEmpty()
    fun setYtClientSecret(context: Context, v: String) =
        write(context) { putString(K_YT_CLIENT_SECRET, v.trim()) }

    fun getYtRefreshToken(context: Context) = sp(context).getString(K_YT_REFRESH, "").orEmpty()
    fun getYtAccessToken(context: Context) = sp(context).getString(K_YT_ACCESS, "").orEmpty()
    fun getYtAccessExpiry(context: Context) = sp(context).getLong(K_YT_ACCESS_EXP, 0L)
    fun getYtChannelTitle(context: Context) = sp(context).getString(K_YT_CHANNEL, "").orEmpty()

    fun getYtPrivacy(context: Context) = sp(context).getString(K_YT_PRIVACY, "private").orEmpty()
    fun setYtPrivacy(context: Context, v: String) =
        write(context) { putString(K_YT_PRIVACY, v) }

    fun saveYtTokens(
        context: Context,
        refreshToken: String?,
        accessToken: String,
        accessExpiryEpochMs: Long
    ) {
        write(context) {
            if (!refreshToken.isNullOrBlank()) putString(K_YT_REFRESH, refreshToken)
            putString(K_YT_ACCESS, accessToken)
            putLong(K_YT_ACCESS_EXP, accessExpiryEpochMs)
        }
    }

    fun setYtChannelTitle(context: Context, v: String) =
        write(context) { putString(K_YT_CHANNEL, v) }

    fun clearYtTokens(context: Context) {
        write(context) {
            remove(K_YT_REFRESH)
            remove(K_YT_ACCESS)
            remove(K_YT_ACCESS_EXP)
            remove(K_YT_CHANNEL)
        }
    }
}
