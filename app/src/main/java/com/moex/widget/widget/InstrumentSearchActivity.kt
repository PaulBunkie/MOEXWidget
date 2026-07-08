package com.moex.widget.widget

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moex.widget.R
import com.moex.widget.data.Instrument
import com.moex.widget.data.MoexApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class InstrumentSearchActivity : AppCompatActivity() {

    private var selectedInstrument: Instrument? = null
    private lateinit var marketType: String
    private var searchJob: Job? = null
    private val client = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_instrument_search)

        marketType = intent.getStringExtra("MARKET_TYPE") ?: "YAHOO"
        
        val searchEditText = findViewById<EditText>(R.id.searchEditText)
        val resultsContainer = findViewById<LinearLayout>(R.id.resultsContainer)
        val selectButton = findViewById<Button>(R.id.selectButton)

        searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                searchJob?.cancel()
                searchJob = lifecycleScope.launch {
                    delay(800) // Increase debounce to 800ms
                    if (query.length >= 2) {
                        performApiSearch(query, resultsContainer)
                    } else if (query.isEmpty()) {
                        updateResults("", resultsContainer)
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        selectButton.setOnClickListener {
            val instrument = selectedInstrument
            if (instrument == null) return@setOnClickListener

            selectButton.isEnabled = false
            selectButton.text = getString(R.string.loading)

            lifecycleScope.launch(Dispatchers.IO) {
                // 1. Fetch Investing Slug
                val slug = fetchInvestingSlug(instrument.toKey().split(":").last())
                
                // 2. If MOEX, fetch metadata
                if (marketType == "STOCK") {
                    MoexApiClient(this@InstrumentSearchActivity).searchAndSaveMetadata(instrument.toKey().split(":").last())
                }

                withContext(Dispatchers.Main) {
                    val data = Intent().apply {
                        putExtra("SELECTED_INSTRUMENT_KEY", instrument.toKey())
                        putExtra("SELECTED_SLUG", slug)
                    }
                    setResult(RESULT_OK, data)
                    finish()
                }
            }
        }
        
        // Initial results
        updateResults("", resultsContainer)
    }

    private suspend fun performApiSearch(query: String, container: LinearLayout) {
        val results = withContext(Dispatchers.IO) {
            when (marketType) {
                "STOCK" -> searchMoex(query)
                "CRYPTO" -> searchBinance(query)
                "YAHOO" -> searchYahoo(query)
                else -> emptyList()
            }
        }
        
        withContext(Dispatchers.Main) {
            displayResults(results, container)
        }
    }

    private fun searchYahoo(query: String): List<Pair<String, String>> {
        return try {
            val url = "https://query2.finance.yahoo.com/v1/finance/search?q=$query&quotesCount=10&newsCount=0"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            
            val json = JSONObject(body)
            val quotes = json.getJSONArray("quotes")
            
            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until quotes.length()) {
                val obj = quotes.getJSONObject(i)
                val symbol = obj.getString("symbol")
                val name = obj.optString("shortname", obj.optString("longname", "N/A"))
                list.add(symbol to name)
            }
            list
        } catch (e: Exception) {
            Log.e("YahooSearch", "Search failed", e)
            emptyList()
        }
    }

    private fun searchMoex(query: String): List<Pair<String, String>> {
        return try {
            // Берем всё, но фильтруем в коде по группам
            val url = "https://iss.moex.com/iss/securities.json?q=$query&iss.only=securities"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            
            val json = JSONObject(body)
            val securities = json.getJSONObject("securities")
            val columns = securities.getJSONArray("columns")
            val data = securities.getJSONArray("data")
            
            // Динамически находим индексы колонок, чтобы не ошибиться
            var secidIdx = -1
            var nameIdx = -1
            var groupIdx = -1
            
            for (i in 0 until columns.length()) {
                when (columns.getString(i).lowercase()) {
                    "secid" -> secidIdx = i
                    "shortname" -> nameIdx = i
                    "group" -> groupIdx = i
                }
            }
            
            if (secidIdx == -1 || nameIdx == -1 || groupIdx == -1) return emptyList()

            val list = mutableListOf<Pair<String, String>>()
            for (i in 0 until data.length()) {
                val row = data.getJSONArray(i)
                val secId = row.getString(secidIdx)
                val name = row.getString(nameIdx)
                val group = row.getString(groupIdx)
                
                // Оставляем только Акции, Расписки (CYAN) и Индексы.
                // Также вводим лимит на длину тикера (max 6 символов), чтобы отсечь технический мусор типа MCFCNYTR.
                if ((group == "stock_shares" || group == "stock_dr" || group == "stock_index") && secId.length <= 6) {
                    list.add(secId to name)
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun searchBinance(query: String): List<Pair<String, String>> {
        return try {
            val url = "https://api.binance.com/api/v3/ticker/price"
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return emptyList()
            
            val data = JSONArray(body)
            val list = mutableListOf<Pair<String, String>>()
            val upperQuery = query.uppercase()
            for (i in 0 until data.length()) {
                val obj = data.getJSONObject(i)
                val symbol = obj.getString("symbol")
                if (symbol.contains(upperQuery)) {
                    list.add(symbol to symbol)
                }
                if (list.size >= 20) break
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun displayResults(results: List<Pair<String, String>>, container: LinearLayout) {
        container.removeAllViews()
        if (results.isEmpty()) {
            val tv = TextView(this).apply { text = "No results found" }
            container.addView(tv)
            return
        }

        val radioGroup = RadioGroup(this)
        container.addView(radioGroup)

        for (res in results) {
            val rb = RadioButton(this).apply {
                text = "${res.first} (${res.second})"
                setOnClickListener {
                    selectedInstrument = when (marketType) {
                        "STOCK" -> Instrument.Stock(res.first)
                        "CRYPTO" -> Instrument.Crypto(res.first)
                        else -> Instrument.YahooStock(res.first)
                    }
                }
            }
            radioGroup.addView(rb)
        }
    }

    private fun fetchInvestingSlug(ticker: String): String? {
        return try {
            val url = "https://www.investing.com/search/service/searchTopBar?search_text=$ticker"
            val request = Request.Builder()
                .url(url)
                .header("X-Requested-With", "XMLHttpRequest")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return null
            
            val json = JSONObject(body)
            val quotes = json.optJSONArray("quotes")
            if (quotes != null && quotes.length() > 0) {
                // Return the first match's URL
                quotes.getJSONObject(0).getString("url")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun updateResults(query: String, container: LinearLayout) {
        container.removeAllViews()
        val results = when (marketType) {
            "STOCK" -> Instrument.POPULAR_STOCKS
            "CRYPTO" -> Instrument.POPULAR_CRYPTOS
            else -> Instrument.POPULAR_YAHOO_STOCKS
        }.filter { it.contains(query, ignoreCase = true) }

        val radioGroup = RadioGroup(this)
        container.addView(radioGroup)

        for (ticker in results) {
            val rb = RadioButton(this).apply {
                text = ticker
                setOnClickListener {
                    selectedInstrument = when (marketType) {
                        "STOCK" -> Instrument.Stock(ticker)
                        "CRYPTO" -> Instrument.Crypto(ticker)
                        else -> Instrument.YahooStock(ticker)
                    }
                }
            }
            radioGroup.addView(rb)
        }

        // Also add the query itself if it's not empty as a custom entry
        if (query.isNotEmpty() && !results.contains(query.uppercase())) {
            val customTicker = query.uppercase()
            val rb = RadioButton(this).apply {
                text = getString(R.string.custom_instrument, customTicker)
                setOnClickListener {
                    selectedInstrument = when (marketType) {
                        "STOCK" -> Instrument.Stock(customTicker)
                        "CRYPTO" -> Instrument.Crypto(customTicker)
                        else -> Instrument.YahooStock(customTicker)
                    }
                }
            }
            radioGroup.addView(rb)
        }
    }
}