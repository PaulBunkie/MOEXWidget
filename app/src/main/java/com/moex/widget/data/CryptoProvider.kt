package com.moex.widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    override fun getDisplayName(): String {
        return symbol.replace("USDT", "/USDT")
    }

    override fun fetch24hCandles(): Result<List<Candle>> {
        if (!isNetworkAvailable()) {
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            // Binance API endpoint for klines (candles)
            // interval=1h, limit=24 (last 24 hours)
            val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1h&limit=24"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val candles = parseBinanceResponse(body)
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
            // Binance API endpoint for daily klines (candles)
            val url = "https://api.binance.com/api/v3/klines?symbol=$symbol&interval=1d&limit=$days"

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return Result.failure(Exception("Empty response"))

            if (!response.isSuccessful) {
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            val candles = parseBinanceResponse(body)
            Result.success(candles)
        } catch (e: Exception) {
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
        val jsonArray = JSONArray(jsonString)
        val candles = mutableListOf<Candle>()

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
                // Skip malformed rows
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