package com.moex.widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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

    override fun getDisplayName(): String = ticker

    override fun fetch24hCandles(): Result<List<Candle>> {
        if (!isNetworkAvailable()) {
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?interval=1h&range=1d"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val candles = parseYahooResponse(body)
            Result.success(candles)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun fetchDailyCandles(days: Int): Result<List<Candle>> {
        if (!isNetworkAvailable()) {
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

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val candles = parseYahooResponse(body)
            Result.success(candles)
        } catch (e: Exception) {
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
        val root = JSONObject(jsonString)
        val chart = root.getJSONObject("chart")

        // Check for error conditions
        if (chart.has("error") && !chart.isNull("error")) {
            val error = chart.getJSONObject("error")
            val description = error.optString("description", "Unknown Yahoo error")
            throw Exception("Yahoo API error: $description")
        }

        val result = chart.getJSONArray("result")
        if (result.length() == 0) {
            throw Exception("No data returned from Yahoo for $ticker")
        }

        val firstResult = result.getJSONObject(0)
        val timestamps: JSONArray = firstResult.getJSONArray("timestamp")

        val indicators = firstResult.getJSONObject("indicators")
        val quote = indicators.getJSONArray("quote")
        val quoteData = quote.getJSONObject(0)

        val opens = quoteData.getJSONArray("open")
        val highs = quoteData.getJSONArray("high")
        val lows = quoteData.getJSONArray("low")
        val closes = quoteData.getJSONArray("close")

        val candles = mutableListOf<Candle>()
        val length = minOf(
            timestamps.length(),
            opens.length(),
            highs.length(),
            lows.length(),
            closes.length()
        )

        for (i in 0 until length) {
            try {
                // Skip null entries (Yahoo may return null for some values)
                if (timestamps.isNull(i) || opens.isNull(i) ||
                    highs.isNull(i) || lows.isNull(i) || closes.isNull(i)
                ) {
                    continue
                }

                val timeSec = timestamps.getLong(i)
                val open = opens.getDouble(i)
                val high = highs.getDouble(i)
                val low = lows.getDouble(i)
                val close = closes.getDouble(i)

                // Convert seconds to milliseconds
                candles.add(Candle(timeSec * 1000L, open, high, low, close))
            } catch (e: Exception) {
                // Skip malformed entries
                continue
            }
        }

        return candles.sortedBy { it.time }
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}