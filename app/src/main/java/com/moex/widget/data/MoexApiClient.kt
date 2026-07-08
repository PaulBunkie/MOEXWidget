package com.moex.widget.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val prefs = context.getSharedPreferences("moex_metadata", Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }

    companion object {
        private const val TAG = "MoexApiClient"
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
    override fun fetch24hCandles(days: Int): Result<List<Candle>> {
        Log.d(TAG, "fetch24hCandles: ticker=$ticker, days=$days")
        if (days <= 1) {
            return fetchCandles(ticker, 60)
        } else {
            return fetchCandles(ticker, 60, days)
        }
    }

    /**
     * Fetches daily candle data for the given number of days.
     * MOEX ISS API interval=24 returns daily candles.
     */
    override fun fetchDailyCandles(days: Int): Result<List<Candle>> {
        Log.d(TAG, "fetchDailyCandles: ticker=$ticker, days=$days")
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No internet connection for fetchDailyCandles")
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            val endDate = Date()
            val startDate = Date(System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000L)

            val url = buildCandlesUrl(ticker, startDate, endDate, 24)
            Log.d(TAG, "fetchDailyCandles URL: $url")

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
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
            val candles = parseCandlesResponse(body)
            Log.d(TAG, "fetchDailyCandles: parsed ${candles.size} candles for $ticker")
            Result.success(candles)
        } catch (e: Exception) {
            Log.e(TAG, "fetchDailyCandles exception for $ticker", e)
            Result.failure(e)
        }
    }

    /**
     * Fetches candles for the last 24 hours for a given ticker.
     * Uses 60-minute interval candles.
     */
    fun fetchCandles(ticker: String, intervalMinutes: Int = 60, daysBack: Int = 1): Result<List<Candle>> {
        Log.d(TAG, "fetchCandles: ticker=$ticker, interval=$intervalMinutes")
        if (!isNetworkAvailable()) {
            Log.w(TAG, "No internet connection for fetchCandles")
            return Result.failure(Exception("No internet connection"))
        }

        return try {
            val endDate = Date()
            val startDate = Date(System.currentTimeMillis() - daysBack.toLong() * 24 * 60 * 60 * 1000L)

            // MOEX ISS API endpoint for candles
            val url = buildCandlesUrl(ticker, startDate, endDate, intervalMinutes)
            Log.d(TAG, "fetchCandles URL: $url")

            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            Log.d(TAG, "fetchCandles: executing request for $ticker")
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: run {
                Log.w(TAG, "fetchCandles: empty response body for $ticker")
                return Result.failure(Exception("Empty response"))
            }

            if (!response.isSuccessful) {
                Log.w(TAG, "fetchCandles: HTTP ${response.code} for $ticker, body=${body.take(200)}")
                return Result.failure(Exception("HTTP ${response.code}: $body"))
            }

            Log.d(TAG, "fetchCandles: response received for $ticker, size=${body.length}")
            val candles = parseCandlesResponse(body)
            Log.d(TAG, "fetchCandles: parsed ${candles.size} candles for $ticker")
            Result.success(candles)
        } catch (e: Exception) {
            Log.e(TAG, "fetchCandles exception for $ticker", e)
            Result.failure(e)
        }
    }

    /**
     * Builds MOEX ISS API URL for candle data.
     * Uses metadata (engine/market/board) found via search.
     * Defaults to stock/shares/TQBR if not found.
     */
    private fun buildCandlesUrl(
        ticker: String,
        from: Date,
        till: Date,
        interval: Int
    ): String {
        val fromStr = dateFormat.format(from)
        val tillStr = dateFormat.format(till)

        val engine = prefs.getString("engine_$ticker", "stock")
        val market = prefs.getString("market_$ticker", "shares")
        val board = prefs.getString("board_$ticker", "TQBR")

        // Specialized boards for some markets
        val boardPart = if (board != null && board.isNotEmpty()) "/boards/$board" else ""

        val url = "https://iss.moex.com/iss/engines/$engine/markets/$market${boardPart}/securities/" +
                "$ticker/candles.json?from=$fromStr&till=$tillStr&interval=$interval"
        Log.d(TAG, "buildCandlesUrl: ticker=$ticker, engine=$engine, market=$market, board=$board, url=$url")
        return url
    }

    /**
     * Searches for a security to find its engine, market and board.
     * This allows supporting futures (IMOEXF), indices, and different boards.
     */
    suspend fun searchAndSaveMetadata(ticker: String): Boolean {
        // Check if we already have metadata
        val existingEngine = prefs.getString("engine_$ticker", null)
        if (existingEngine != null) {
            return true
        }

        return withContext(Dispatchers.IO) {
            try {
                // Using: https://iss.moex.com/iss/securities/{ticker}.json
                val detailUrl = "https://iss.moex.com/iss/securities/$ticker.json?iss.only=boards&boards.only=boardid,market,engine,is_primary"
                val detailRequest = Request.Builder().url(detailUrl).build()
                val detailResponse = client.newCall(detailRequest).execute()
                val detailBody = detailResponse.body?.string() ?: return@withContext false

                val detailJson = org.json.JSONObject(detailBody)
                val boards = detailJson.getJSONObject("boards")
                val boardsData = boards.getJSONArray("data")
                val boardsCols = boards.getJSONArray("columns")

                var bIdIdx = -1
                var mktIdx = -1
                var engIdx = -1
                var primaryIdx = -1

                for (i in 0 until boardsCols.length()) {
                    when (boardsCols.getString(i).lowercase()) {
                        "boardid" -> bIdIdx = i
                        "market" -> mktIdx = i
                        "engine" -> engIdx = i
                        "is_primary" -> primaryIdx = i
                    }
                }

                for (i in 0 until boardsData.length()) {
                    val row = boardsData.getJSONArray(i)
                    val isPrimary = row.optInt(primaryIdx, 0) == 1
                    if (isPrimary || boardsData.length() == 1) {
                        val board = row.getString(bIdIdx)
                        val market = row.getString(mktIdx)
                        val engine = row.getString(engIdx)

                        prefs.edit()
                            .putString("engine_$ticker", engine)
                            .putString("market_$ticker", market)
                            .putString("board_$ticker", board)
                            .apply()
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                Log.e(TAG, "Error searching metadata for $ticker", e)
                false
            }
        }
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
        try {
            val json = org.json.JSONObject(jsonString)
            if (!json.has("candles")) {
                Log.e(TAG, "parseCandlesResponse: missing 'candles' key in response for $ticker")
                return emptyList()
            }
            val candlesJson = json.getJSONObject("candles")
            val columns = candlesJson.getJSONArray("columns")
            val data = candlesJson.getJSONArray("data")
            Log.d(TAG, "parseCandlesResponse: columns=${columns.length()}, dataRows=${data.length()}")

            // Map column names to indices
            val columnIndex = mutableMapOf<String, Int>()
            for (i in 0 until columns.length()) {
                columnIndex[columns.getString(i).lowercase()] = i
            }

            val openIdx = columnIndex["open"]
            val closeIdx = columnIndex["close"]
            val highIdx = columnIndex["high"]
            val lowIdx = columnIndex["low"]
            val beginIdx = columnIndex["begin"]

            if (openIdx == null || closeIdx == null || highIdx == null || lowIdx == null || beginIdx == null) {
                Log.e(TAG, "parseCandlesResponse: missing required columns for $ticker, columnIndex=$columnIndex")
                return emptyList()
            }

            Log.d(TAG, "parseCandlesResponse: indices open=$openIdx, close=$closeIdx, high=$highIdx, low=$lowIdx, begin=$beginIdx")

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Europe/Moscow")
            }

            val candles = mutableListOf<Candle>()
            var skippedRows = 0

            for (i in 0 until data.length()) {
                val row = data.getJSONArray(i)

                try {
                    if (beginIdx >= row.length()) {
                        skippedRows++
                        continue
                    }
                    val timeStr = row.getString(beginIdx)
                    val parsedTime = dateFormat.parse(timeStr)
                    if (parsedTime == null) {
                        skippedRows++
                        continue
                    }
                    val time = parsedTime.time

                    if (openIdx >= row.length() || closeIdx >= row.length() ||
                        highIdx >= row.length() || lowIdx >= row.length()) {
                        skippedRows++
                        continue
                    }

                    val open = row.getDouble(openIdx)
                    val close = row.getDouble(closeIdx)
                    val high = row.getDouble(highIdx)
                    val low = row.getDouble(lowIdx)

                    candles.add(Candle(time, open, high, low, close))
                } catch (e: Exception) {
                    skippedRows++
                    if (skippedRows <= 3) {
                        Log.w(TAG, "parseCandlesResponse: skipped row $i for $ticker: ${e.message}")
                    }
                }
            }

            if (skippedRows > 0) {
                Log.w(TAG, "parseCandlesResponse: skipped $skippedRows malformed rows for $ticker")
            }

            // Sort by time ascending
            val sorted = candles.sortedBy { it.time }
            Log.d(TAG, "parseCandlesResponse: returning ${sorted.size} valid candles for $ticker")
            return sorted
        } catch (e: Exception) {
            Log.e(TAG, "parseCandlesResponse: fatal error for $ticker", e)
            return emptyList()
        }
    }
}