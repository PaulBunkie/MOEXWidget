package com.moex.widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Client for Moscow Exchange ISS API.
 * Fetches historical candle data for a given ticker.
 * Implements PriceProvider interface for the widget data pipeline.
 */
class MoexApiClient(context: Context, private val ticker: String = "SBER") : PriceProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }

    /**
     * Checks if network is available.
     */
    fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    override fun getDisplayName(): String = ticker

    /**
     * Fetches 24-hour candle data using the PriceProvider interface.
     */
    override fun fetch24hCandles(): Result<List<Candle>> {
        return fetchCandles(ticker, 60)
    }

    /**
     * Fetches candles for the last 24 hours for a given ticker.
     * Uses 60-minute interval candles.
     */
    fun fetchCandles(ticker: String, intervalMinutes: Int = 60): Result<List<Candle>> {
        if (!isNetworkAvailable()) {
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            val endDate = Date()
            val startDate = Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L)

            // MOEX ISS API endpoint for candles
            val url = buildCandlesUrl(ticker, startDate, endDate, intervalMinutes)

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val candles = parseCandlesResponse(body)
            Result.success(candles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Builds MOEX ISS API URL for candle data.
     * Format: https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/{ticker}/candles.json
     */
    private fun buildCandlesUrl(
        ticker: String,
        from: Date,
        till: Date,
        interval: Int
    ): String {
        val fromStr = dateFormat.format(from)
        val tillStr = dateFormat.format(till)

        return "https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/" +
                "$ticker/candles.json?from=$fromStr&till=$tillStr&interval=$interval"
    }

    /**
     * Parses MOEX ISS JSON response into list of Candle objects.
     *
     * MOEX ISS candle response format:
     * {
     *   "candles": {
     *     "columns": ["open","close","high","low","value","volume","begin","end"],
     *     "data": [ [...], [...] ]
     *   }
     * }
     */
    private fun parseCandlesResponse(jsonString: String): List<Candle> {
        val json = org.json.JSONObject(jsonString)
        val candlesJson = json.getJSONObject("candles")
        val columns = candlesJson.getJSONArray("columns")
        val data = candlesJson.getJSONArray("data")

        // Map column names to indices
        val columnIndex = mutableMapOf<String, Int>()
        for (i in 0 until columns.length()) {
            columnIndex[columns.getString(i).lowercase()] = i
        }

        val openIdx = columnIndex["open"] ?: 0
        val closeIdx = columnIndex["close"] ?: 1
        val highIdx = columnIndex["high"] ?: 2
        val lowIdx = columnIndex["low"] ?: 3
        val beginIdx = columnIndex["begin"] ?: 6 // "begin" is the candle start time

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Europe/Moscow")
        }

        val candles = mutableListOf<Candle>()

        for (i in 0 until data.length()) {
            val row = data.getJSONArray(i)

            try {
                val timeStr = row.getString(beginIdx)
                val time = dateFormat.parse(timeStr)?.time ?: continue

                val open = row.getDouble(openIdx)
                val close = row.getDouble(closeIdx)
                val high = row.getDouble(highIdx)
                val low = row.getDouble(lowIdx)

                candles.add(Candle(time, open, high, low, close))
            } catch (e: Exception) {
                // Skip malformed rows
                continue
            }
        }

        // Sort by time ascending
        return candles.sortedBy { it.time }
    }
}