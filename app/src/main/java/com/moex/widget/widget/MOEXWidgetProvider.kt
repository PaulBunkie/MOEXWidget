package com.moex.widget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import com.moex.widget.R
import com.moex.widget.worker.WidgetUpdateService
import com.moex.widget.worker.WidgetUpdateWorker

/**
 * AppWidgetProvider for MOEX stock chart widget.
 * Handles widget lifecycle, manual refresh, and delegates updates to WorkManager.
 */
class MOEXWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Schedule periodic updates if not already scheduled
        WidgetUpdateWorker.schedulePeriodic(context)

        // Trigger immediate data refresh for all widget instances
        for (appWidgetId in appWidgetIds) {
            triggerRefresh(context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        // Widget added to home screen - schedule periodic updates
        WidgetUpdateWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        // Last widget removed - nothing to clean up
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: action=${intent.action}")

        if (ACTION_MANUAL_REFRESH == intent.action) {
            Log.d(TAG, "Manual refresh triggered by tap!")
            // Manual refresh triggered by tapping the widget
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, MOEXWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (appWidgetId in appWidgetIds) {
                triggerRefresh(context, appWidgetId)
            }
        }
    }

    private fun triggerRefresh(context: Context, appWidgetId: Int) {
        val instrumentKey = getInstrumentForWidget(context, appWidgetId)
        Log.d(TAG, "triggerRefresh: appWidgetId=$appWidgetId, instrumentKey=$instrumentKey")

        // Enqueue work for background refresh
        WidgetUpdateWorker.enqueueRefresh(
            context,
            instrumentKey,
            intArrayOf(appWidgetId)
        )
    }

    companion object {
        const val ACTION_MANUAL_REFRESH = "com.moex.widget.ACTION_MANUAL_REFRESH"
        const val EXTRA_TICKER = "ticker"
        const val EXTRA_APPWIDGET_IDS = "appWidgetIds"

        /**
         * Returns the instrument key for a given widget instance.
         * Defaults to "STOCK:SBER" if no instrument is configured.
         */
        private fun getInstrumentForWidget(context: Context, appWidgetId: Int): String {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val key = "instrument_$appWidgetId"
            val value = prefs.getString(key, "STOCK:SBER") ?: "STOCK:SBER"
            Log.d(TAG, "getInstrumentForWidget: key=$key, value=$value")
            return value
        }

        private const val TAG = "MOEXWidgetProvider"

        /**
         * Sets up click handling on the widget to trigger manual refresh.
         */
        fun updateWidgetClickHandler(
            context: Context,
            remoteViews: RemoteViews,
            appWidgetId: Int
        ) {
            val intent = Intent(context, MOEXWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            remoteViews.setOnClickPendingIntent(R.id.chart_image, pendingIntent)
            remoteViews.setOnClickPendingIntent(R.id.ticker_text, pendingIntent)
            remoteViews.setOnClickPendingIntent(R.id.price_text, pendingIntent)
        }
    }
}