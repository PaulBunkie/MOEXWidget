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

        titleText.text = "Настройка виджета"

        // Market selection
        marketRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            instrumentContainer.removeAllViews()

            when (checkedId) {
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
    }

    private fun saveInstrument(instrument: Instrument) {
        val prefs = getSharedPreferences("widget_prefs", MODE_PRIVATE)
        val key = "instrument_$appWidgetId"
        val value = instrument.toKey()
        Log.d(TAG, "saveInstrument: key=$key, value=$value")
        val result = prefs.edit()
            .putString(key, value)
            .commit()
        Log.d(TAG, "saveInstrument commit result=$result")

        // Verify read-back
        val readBack = prefs.getString(key, "NOT_FOUND")
        Log.d(TAG, "saveInstrument readBack=$readBack")
    }

    companion object {
        private const val TAG = "WidgetConfig"
    }
}
