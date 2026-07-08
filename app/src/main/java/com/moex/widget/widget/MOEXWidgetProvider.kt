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
import com.moex.widget.data.Instrument
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
        Log.d(TAG, "onUpdate: called with ${appWidgetIds.size} widgets, ids=${appWidgetIds.joinToString()}")
        // Schedule periodic updates if not already scheduled
        WidgetUpdateWorker.schedulePeriodic(context)

        // Trigger immediate data refresh for all widget instances
        for (appWidgetId in appWidgetIds) {
            val key = getInstrumentForWidget(context, appWidgetId)
            Log.d(TAG, "onUpdate: widget $appWidgetId -> instrument=$key")
            triggerRefresh(context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "onEnabled: widget added to home screen")
        // Widget added to home screen - schedule periodic updates
        WidgetUpdateWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "onDisabled: last widget removed")
        // Last widget removed - nothing to clean up
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")

        when (intent.action) {
            ACTION_MANUAL_REFRESH -> {
                Log.d(TAG, "Manual refresh triggered by tap!")
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    triggerRefresh(context, appWidgetId)
                }
            }
            ACTION_PERIOD_NEXT -> {
                Log.d(TAG, "Period NEXT triggered")
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetUpdateWorker.enqueuePeriodChange(context, appWidgetId, 1)
                }
            }
            ACTION_PERIOD_PREV -> {
                Log.d(TAG, "Period PREV triggered")
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetUpdateWorker.enqueuePeriodChange(context, appWidgetId, -1)
                }
            }
            else -> {
                // Let the system handle all other actions (APPWIDGET_UPDATE, APPWIDGET_ENABLED, etc.)
                super.onReceive(context, intent)
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
        const val ACTION_MANUAL_REFRESH = "com.stock.crypto.widget.ACTION_MANUAL_REFRESH"
        const val ACTION_PERIOD_NEXT = "com.stock.crypto.widget.ACTION_PERIOD_NEXT"
        const val ACTION_PERIOD_PREV = "com.stock.crypto.widget.ACTION_PERIOD_PREV"
        const val ACTION_TOGGLE_PERIOD = "com.stock.crypto.widget.ACTION_TOGGLE_PERIOD"
        const val ACTION_SHOW_OVERLAY = "com.stock.crypto.widget.ACTION_SHOW_OVERLAY"
        const val EXTRA_TICKER = "ticker"
        const val EXTRA_APPWIDGET_IDS = "appWidgetIds"
        const val PREF_LAST_CLICK_TIME = "last_click_time_"
        const val DOUBLE_CLICK_DELAY = 500L // ms

        private const val TAG = "MOEXWidgetProvider"

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

        /**
         * Sets up click handling on the widget to trigger manual refresh.
         */
        fun updateWidgetClickHandler(
            context: Context,
            remoteViews: RemoteViews,
            appWidgetId: Int,
            investingUrl: String
        ) {
            val refreshIntent = Intent(context, MOEXWidgetProvider::class.java).apply {
                action = ACTION_MANUAL_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }

            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                appWidgetId * 3,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Direct Activity Intent to avoid background launch restrictions on real devices
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(investingUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val browserPendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId * 3 + 1,
                browserIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val periodNextIntent = Intent(context, MOEXWidgetProvider::class.java).apply {
                action = ACTION_PERIOD_NEXT
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val periodNextPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId * 4 + 2, periodNextIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val periodPrevIntent = Intent(context, MOEXWidgetProvider::class.java).apply {
                action = ACTION_PERIOD_PREV
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val periodPrevPendingIntent = PendingIntent.getBroadcast(
                context, appWidgetId * 4 + 3, periodPrevIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            remoteViews.setOnClickPendingIntent(R.id.chart_click_left, periodPrevPendingIntent)
            remoteViews.setOnClickPendingIntent(R.id.chart_click_right, periodNextPendingIntent)
            remoteViews.setOnClickPendingIntent(R.id.ticker_text, browserPendingIntent)
            remoteViews.setOnClickPendingIntent(R.id.price_text, refreshPendingIntent)
        }
    }
}