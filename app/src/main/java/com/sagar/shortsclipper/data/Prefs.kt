package com.sagar.shortsclipper.data

import android.content.Context
import com.sagar.shortsclipper.BuildConfig
import com.sagar.shortsclipper.model.OutputQuality

/** Tiny SharedPreferences wrapper for the AI key and output quality. */
object Prefs {
    private const val FILE = "shorts_clipper_prefs"
    private const val K_API = "gemini_api_key"
    private const val K_QUALITY = "output_quality"

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Returns a key entered in-app if present; otherwise the key baked in at build time
     * (via env var or local.properties). Lets you ship a build that "just works" without
     * typing the key, while still allowing an in-app override.
     */
    fun getApiKey(context: Context): String {
        val saved = sp(context).getString(K_API, "").orEmpty()
        return if (saved.isNotBlank()) saved else BuildConfig.GEMINI_API_KEY
    }

    fun setApiKey(context: Context, value: String) =
        sp(context).edit().putString(K_API, value).apply()

    fun getQuality(context: Context): OutputQuality {
        val name = sp(context).getString(K_QUALITY, OutputQuality.FHD.name)
        return runCatching { OutputQuality.valueOf(name!!) }.getOrDefault(OutputQuality.FHD)
    }

    fun setQuality(context: Context, quality: OutputQuality) =
        sp(context).edit().putString(K_QUALITY, quality.name).apply()
}
