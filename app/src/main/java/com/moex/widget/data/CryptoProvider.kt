package com.moex.widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Provider for cryptocurrency data from Binance API.
 * Fetches 24-hour candle data for crypto pairs like BTCUSDT, ETHUSDT, etc.
 */
class CryptoProvider(
    private val context: Context,
    private val symbol: String
) : PriceProvider {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    companion object {
        private const val TAG = "CryptoProvider"
    }

    override fun getDisplayName(): String {
        return symbol.replace("USDT", "/USDT")
    }

    override fun fetch24hCandles(days: Int): Result<List<Candle>> {
        Log.d(TAG, "fetch24hCandles: symbol=$symbol, days=$days")
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No internet connection for fetch24hCandles")
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            // Binance API endpoint for klines (candles)
            val limit = if (days <= 1) 24 else days * 8
            val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1h&limit=$limit"
            Log.d(TAG, "fetch24hCandles URL: $url")

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            Log.d(TAG, "fetch24hCandles: executing request for $symbol")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: run {
                Log.w(TAG, "fetch24hCandles: empty response body for $symbol")
                return Result.failure(Exception("Empty response"))
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "fetch24hCandles: HTTP ${response.code} for $symbol, body=${body.take(200)}")
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            Log.d(TAG, "fetch24hCandles: response received for $symbol, size=${body.length}")
            val candles = parseBinanceResponse(body)
            Log.d(TAG, "fetch24hCandles: parsed ${candles.size} candles for $symbol")
            Result.success(candles)
        } catch (e: Exception) {
            Log.e(TAG, "fetch24hCandles exception for $symbol", e)
            Result.failure(e)
        }
    }

    override fun fetchDailyCandles(days: Int): Result<List<Candle>> {
        Log.d(TAG, "fetchDailyCandles: symbol=$symbol, days=$days")
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No internet connection for fetchDailyCandles")
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            // Binance API endpoint for daily klines (candles)
            val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1d&limit=$days"
            Log.d(TAG, "fetchDailyCandles URL: $url")

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            Log.d(TAG, "fetchDailyCandles: executing request for $symbol")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: run {
                Log.w(TAG, "fetchDailyCandles: empty response body for $symbol")
                return Result.failure(Exception("Empty response"))
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "fetchDailyCandles: HTTP ${response.code} for $symbol, body=${body.take(200)}")
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            Log.d(TAG, "fetchDailyCandles: response received for $symbol, size=${body.length}")
            val candles = parseBinanceResponse(body)
            Log.d(TAG, "fetchDailyCandles: parsed ${candles.size} candles for $symbol")
            Result.success(candles)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDailyCandles exception for $symbol", e)
            Result.failure(e)
        }
    }

    /**
     * Parses Binance klines response into list of Candle objects.
     *
     * Binance klines format:
     * [
     *   [
     *     1499040000000,      // Open time
     *     "0.01634000",       // Open
     *     "0.80000000",       // High
     *     "0.01575800",       // Low
     *     "0.01577100",       // Close
     *     "148976.11427815",  // Volume
     *     1499644799999,      // Close time
     *     "2434.19055334",    // Quote asset volume
     *     308,                // Number of trades
     *     "1756.87402397",    // Taker buy base asset volume
     *     "28.46694368",      // Taker buy quote asset volume
     *     "17928899.62484339" // Ignore
     *   ]
     * ]
     */
    private fun parseBinanceResponse(jsonString: String): List<Candle> {
        try {
            val jsonArray = JSONArray(jsonString)
            Log.d(TAG, "parseBinanceResponse: arrayLength=${jsonArray.length()} for $symbol")
            val candles = mutableListOf<Candle>()
            var skippedRows = 0

            for (i in 0 until jsonArray.length()) {
                val kline = jsonArray.getJSONArray(i)

                try {
                    val openTime = kline.getLong(0)
                    val open = kline.getString(1).toDouble()
                    val high = kline.getString(2).toDouble()
                    val low = kline.getString(3).toDouble()
                    val close = kline.getString(4).toDouble()

                    candles.add(Candle(openTime, open, high, low, close))
                } catch (e: Exception) {
                    skippedRows++
                    if (skippedRows <= 3) {
                        Log.w(TAG, "parseBinanceResponse: skipped row $i for $symbol: ${e.message}")
                    }
                }
            }

            if (skippedRows > 0) {
                Log.w(TAG, "parseBinanceResponse: skipped $skippedRows malformed rows for $symbol")
            }

            val sorted = candles.sortedBy { it.time }
            Log.d(TAG, "parseBinanceResponse: returning ${sorted.size} valid candles for $symbol")
            return sorted
        } catch (e: Exception) {
            Log.e(TAG, "parseBinanceResponse: fatal error for $symbol", e)
            return emptyList()
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}