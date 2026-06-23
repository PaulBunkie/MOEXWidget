package com.moex.widget.worker

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.moex.widget.widget.MOEXWidgetProvider

/**
 * Simple service to handle manual widget refresh triggered by tap.
 * Delegates to the WidgetUpdateWorker for actual data fetching and rendering.
 */
class WidgetUpdateService : Service() {

    companion object {
        private const val TAG = "WidgetUpdateService"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: service started")

        val appWidgetIds = intent?.getIntArrayExtra(MOEXWidgetProvider.EXTRA_APPWIDGET_IDS) ?: intArrayOf()
        val ticker = intent?.getStringExtra(MOEXWidgetProvider.EXTRA_TICKER) ?: "SBER"
        
        Log.d(TAG, "onStartCommand: ticker=$ticker, widgetIds=${appWidgetIds.contentToString()}")

        // Trigger the worker to refresh data
        WidgetUpdateWorker.enqueueRefresh(this, ticker, appWidgetIds)
        
        Log.d(TAG, "onStartCommand: refresh enqueued, stopping service")
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: service destroyed")
    }
}