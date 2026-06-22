package com.moex.widget.worker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.moex.widget.widget.MOEXWidgetProvider

/**
 * Simple service to handle manual widget refresh triggered by tap.
 * Delegates to the WidgetUpdateWorker for actual data fetching and rendering.
 */
class WidgetUpdateService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appWidgetIds = intent?.getIntArrayExtra(MOEXWidgetProvider.EXTRA_APPWIDGET_IDS) ?: intArrayOf()
        val ticker = intent?.getStringExtra(MOEXWidgetProvider.EXTRA_TICKER) ?: "SBER"

        // Trigger the worker to refresh data
        WidgetUpdateWorker.enqueueRefresh(this, ticker, appWidgetIds)

        return START_NOT_STICKY
    }
}