package com.moex.widget.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Manages local caching of MOEX candle data to minimize network requests
 * and provide offline fallback.
 */
class CacheManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Saves candle data for a given ticker to local cache.
     */
    fun saveCandles(ticker: String, candles: List<Candle>) {
        val json = gson.toJson(candles)
        prefs.edit()
            .putString(getCandlesKey(ticker), json)
            .putLong(getTimestampKey(ticker), System.currentTimeMillis())
            .apply()
    }

    /**
     * Loads cached candle data for a given ticker.
     * Returns null if no cached data exists or cache is too old (>2 hours).
     */
    fun loadCandles(ticker: String): List<Candle>? {
        val timestamp = prefs.getLong(getTimestampKey(ticker), 0L)
        val age = System.currentTimeMillis() - timestamp

        // Cache valid for 2 hours as fallback
        if (age > CACHE_DURATION_MS) return null

        val json = prefs.getString(getCandlesKey(ticker), null) ?: return null
        return try {
            val type = object : TypeToken<List<Candle>>() {}.type
            gson.fromJson<List<Candle>>(json, type)
        } catch (e: Exception) {
            null
        }
    }

    private fun getCandlesKey(ticker: String) = "candles_$ticker"
    private fun getTimestampKey(ticker: String) = "timestamp_$ticker"

    companion object {
        private const val PREFS_NAME = "moex_widget_cache"
        private const val CACHE_DURATION_MS = 2 * 60 * 60 * 1000L // 2 hours
    }
}