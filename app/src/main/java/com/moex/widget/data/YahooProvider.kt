package com.moex.widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Provider for US stock data from Yahoo Finance API.
 * Fetches 24-hour candle data for tickers like AAPL, NVDA, etc.
 *
 * Uses the Yahoo Finance v8 chart endpoint:
 * https://query1.finance.yahoo.com/v8/finance/chart/{ticker}?interval=1h&range=1d
 */
class YahooProvider(
    private val context: Context,
    private val ticker: String
) : PriceProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "YahooProvider"
    }

    override fun getDisplayName(): String = ticker

    override fun fetch24hCandles(days: Int): Result<List<Candle>> {
        Log.d(TAG, "fetch24hCandles: ticker=$ticker, days=$days")
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No internet connection for fetch24hCandles")
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            val range = if (days <= 1) "1d" else "5d"
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?interval=1h&range=$range"
            Log.d(TAG, "fetch24hCandles URL: $url")

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            Log.d(TAG, "fetch24hCandles: executing request for $ticker")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: run {
                Log.w(TAG, "fetch24hCandles: empty response body for $ticker")
                return Result.failure(Exception("Empty response"))
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "fetch24hCandles: HTTP ${response.code} for $ticker, body=${body.take(200)}")
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            Log.d(TAG, "fetch24hCandles: response received for $ticker, size=${body.length}")
            val candles = parseYahooResponse(body)
            Log.d(TAG, "fetch24hCandles: parsed ${candles.size} candles for $ticker")
            Result.success(candles)
        } catch (e: Exception) {
            Log.e(TAG, "fetch24hCandles exception for $ticker", e)
            Result.failure(e)
        }
    }

    override fun fetchDailyCandles(days: Int): Result<List<Candle>> {
        Log.d(TAG, "fetchDailyCandles: ticker=$ticker, days=$days")
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No internet connection for fetchDailyCandles")
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            val range = when {
                days <= 5 -> "5d"
                days <= 10 -> "10d"
                days <= 30 -> "1mo"
                days <= 60 -> "2mo"
                days <= 90 -> "3mo"
                else -> "6mo"
            }
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?interval=1d&range=$range"
            Log.d(TAG, "fetchDailyCandles URL: $url, range=$range")

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            Log.d(TAG, "fetchDailyCandles: executing request for $ticker")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: run {
                Log.w(TAG, "fetchDailyCandles: empty response body for $ticker")
                return Result.failure(Exception("Empty response"))
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "fetchDailyCandles: HTTP ${response.code} for $ticker, body=${body.take(200)}")
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            Log.d(TAG, "fetchDailyCandles: response received for $ticker, size=${body.length}")
            val candles = parseYahooResponse(body)
            Log.d(TAG, "fetchDailyCandles: parsed ${candles.size} candles for $ticker")
            Result.success(candles)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDailyCandles exception for $ticker", e)
            Result.failure(e)
        }
    }

    /**
     * Parses Yahoo Finance JSON response into list of Candle objects.
     *
     * Yahoo Finance chart response format:
     * {
     *   "chart": {
     *     "result": [
     *       {
     *         "timestamp": [1499040000, ...],
     *         "indicators": {
     *           "quote": [
     *             {
     *               "open": [0.01634, ...],
     *               "high": [0.80000, ...],
     *               "low": [0.01575, ...],
     *               "close": [0.01577, ...]
     *             }
     *           ]
     *         }
     *       }
     *     ]
     *   }
     * }
     *
     * Note: Yahoo timestamps are in seconds (Unix epoch), we convert to milliseconds.
     */
    private fun parseYahooResponse(jsonString: String): List<Candle> {
        try {
            val root = JSONObject(jsonString)
            val chart = root.getJSONObject("chart")
            Log.d(TAG, "parseYahooResponse: chart object parsed for $ticker")
            Log.d(TAG, "parseYahooResponse: raw json first 500 chars=${jsonString.take(500)}")

            // Check for error conditions
            if (chart.has("error") && !chart.isNull("error")) {
                val error = chart.getJSONObject("error")
                val description = error.optString("description", "Unknown Yahoo error")
                Log.e(TAG, "parseYahooResponse: Yahoo API error for $ticker: $description")
                throw Exception("Yahoo API error: $description")
            }

            val result = chart.getJSONArray("result")
            if (result.length() == 0) {
                Log.e(TAG, "parseYahooResponse: No data returned from Yahoo for $ticker")
                throw Exception("No data returned from Yahoo for $ticker")
            }

            val firstResult = result.getJSONObject(0)
            val timestamps: JSONArray = firstResult.getJSONArray("timestamp")
            Log.d(TAG, "parseYahooResponse: timestamps count=${timestamps.length()} for $ticker")

            val indicators = firstResult.getJSONObject("indicators")
            val quote = indicators.getJSONArray("quote")
            val quoteData = quote.getJSONObject(0)

            val opens = quoteData.getJSONArray("open")
            val highs = quoteData.getJSONArray("high")
            val lows = quoteData.getJSONArray("low")
            val closes = quoteData.getJSONArray("close")

            Log.d(TAG, "parseYahooResponse: opens=${opens.length()}, highs=${highs.length()}, lows=${lows.length()}, closes=${closes.length()} for $ticker")

            val candles = mutableListOf<Candle>()
            val length = minOf(
                timestamps.length(),
                opens.length(),
                highs.length(),
                lows.length(),
                closes.length()
            )
            var skippedEntries = 0

            for (i in 0 until length) {
                try {
                    // Skip null entries (Yahoo may return null for some values)
                    if (timestamps.isNull(i) || opens.isNull(i) ||
                        highs.isNull(i) || lows.isNull(i) || closes.isNull(i)
                    ) {
                        skippedEntries++
                        continue
                    }

                val timeSec = timestamps.getLong(i)
                val open = opens.getDouble(i)
                val high = highs.getDouble(i)
                val low = lows.getDouble(i)
                val close = closes.getDouble(i)

                val timeMs = timeSec * 1000L
                if (i < 3) {
                    Log.d(TAG, "parseYahooResponse: timestamp[$i] raw=$timeSec, ms=$timeMs, date=${Date(timeMs)}")
                }
                candles.add(Candle(timeMs, open, high, low, close))
                } catch (e: Exception) {
                    skippedEntries++
                    if (skippedEntries <= 3) {
                        Log.w(TAG, "parseYahooResponse: skipped entry $i for $ticker: ${e.message}")
                    }
                }
            }

            if (skippedEntries > 0) {
                Log.w(TAG, "parseYahooResponse: skipped $skippedEntries null/malformed entries for $ticker")
            }

            val sorted = candles.sortedBy { it.time }
            Log.d(TAG, "parseYahooResponse: returning ${sorted.size} valid candles for $ticker")
            return sorted
        } catch (e: Exception) {
            Log.e(TAG, "parseYahooResponse: fatal error for $ticker", e)
            return emptyList()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}