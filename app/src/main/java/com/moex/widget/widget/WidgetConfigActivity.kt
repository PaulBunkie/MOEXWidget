package com.moex.widget.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.moex.widget.R
import com.moex.widget.data.Instrument
import com.moex.widget.worker.WidgetUpdateWorker

/**
 * Configuration activity for the MOEX widget.
 * Allows users to select the market (MOEX or Crypto) and instrument.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var selectedInstrument: Instrument? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_widget_config)

        // Set the result to CANCELED in case the user backs out
        setResult(RESULT_CANCELED)

        // Get the widget ID from the intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setupUI()
    }

    private fun setupUI() {
        val marketRadioGroup = findViewById<RadioGroup>(R.id.marketRadioGroup)
        val instrumentContainer = findViewById<LinearLayout>(R.id.instrumentContainer)
        val confirmButton = findViewById<Button>(R.id.confirmButton)
        val titleText = findViewById<TextView>(R.id.titleText)

        titleText.text = getString(R.string.widget_config_title)

        // Market selection
        marketRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            instrumentContainer.removeAllViews()

            when (checkedId) {
                R.id.radioYahoo -> showYahooList(instrumentContainer)
                R.id.radioMoex -> showStockList(instrumentContainer)
                R.id.radioCrypto -> showCryptoList(instrumentContainer)
            }
        }

        // Confirm button
        confirmButton.setOnClickListener {
            val instrument = selectedInstrument
            Log.d(TAG, "Confirm clicked, selectedInstrument=$instrument, appWidgetId=$appWidgetId")
            if (instrument != null) {
                saveInstrument(instrument)
                Log.d(TAG, "Instrument saved: ${instrument.toKey()} for widget $appWidgetId")

                // Send result to widget provider
                val resultValue = Intent().apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                }
                setResult(RESULT_OK, resultValue)

                // Directly enqueue the worker to refresh this widget
                Log.d(TAG, "Enqueuing worker refresh for widget $appWidgetId with ${instrument.toKey()}")
                WidgetUpdateWorker.enqueueRefresh(
                    this,
                    instrument.toKey(),
                    intArrayOf(appWidgetId)
                )

                finish()
            } else {
                Log.w(TAG, "No instrument selected!")
            }
        }
    }

    private fun showStockList(container: LinearLayout) {
        val stocks = Instrument.POPULAR_STOCKS

        for (stock in stocks) {
            val radioButton = RadioButton(this).apply {
                text = stock
                id = android.view.View.generateViewId()
                setPadding(16, 8, 16, 8)
                textSize = 16f
            }

            radioButton.setOnClickListener {
                selectedInstrument = Instrument.Stock(stock)
            }

            container.addView(radioButton)
        }

        addSearchPlaceholder(container)
    }

    private fun showCryptoList(container: LinearLayout) {
        val cryptos = Instrument.POPULAR_CRYPTOS

        for (crypto in cryptos) {
            val displayText = crypto.replace("USDT", "/USDT")
            val radioButton = RadioButton(this).apply {
                text = displayText
                id = android.view.View.generateViewId()
                setPadding(16, 8, 16, 8)
                textSize = 16f
            }

            radioButton.setOnClickListener {
                selectedInstrument = Instrument.Crypto(crypto)
            }

            container.addView(radioButton)
        }

        addSearchPlaceholder(container)
    }

    private fun showYahooList(container: LinearLayout) {
        val yahooStocks = Instrument.POPULAR_YAHOO_STOCKS

        for (stock in yahooStocks) {
            val radioButton = RadioButton(this).apply {
                text = stock
                id = android.view.View.generateViewId()
                setPadding(16, 8, 16, 8)
                textSize = 16f
            }

            radioButton.setOnClickListener {
                selectedInstrument = Instrument.YahooStock(stock)
            }

            container.addView(radioButton)
        }

        addSearchPlaceholder(container)
    }

    /**
     * Adds a disabled placeholder for the upcoming "Search instrument" feature.
     * This is a visual hint for future premium functionality.
     */
    private fun addSearchPlaceholder(container: LinearLayout) {
        val searchButton = RadioButton(this).apply {
            text = getString(R.string.search_instrument)
            id = android.view.View.generateViewId()
            setPadding(16, 8, 16, 8)
            textSize = 16f
            isEnabled = false
            alpha = 0.4f
        }
        container.addView(searchButton)
    }

    private fun saveInstrument(instrument: Instrument) {
        val prefs = getSharedPreferences("widget_prefs", MODE_PRIVATE)
        val key = "instrument_$appWidgetId"
        val value = instrument.toKey()
        Log.d(TAG, "saveInstrument: key=$key, value=$value")

        // Detect widget size
        val widgetManager = AppWidgetManager.getInstance(this)
        val widgetInfo = widgetManager.getAppWidgetInfo(appWidgetId)
        val isSmall = widgetInfo?.provider == android.content.ComponentName(
            this, MOEXWidgetProviderSmall::class.java
        )
        Log.d(TAG, "saveInstrument: isSmall=$isSmall, provider=${widgetInfo?.provider}")

        val result = prefs.edit()
            .putString(key, value)
            .putBoolean("small_$appWidgetId", isSmall)
            .commit()
        Log.d(TAG, "saveInstrument commit result=$result")
    }

    companion object {
        private const val TAG = "WidgetConfig"
    }
}
