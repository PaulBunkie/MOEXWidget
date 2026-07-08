package com.moex.widget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.moex.widget.R
import com.moex.widget.data.Instrument
import com.moex.widget.worker.WidgetUpdateWorker

/**
 * Small variant of MOEX widget provider.
 * Delegates to the WidgetUpdateWorker for actual work, and uses MOEXWidgetProvider for shared click handlers.
 */
class MOEXWidgetProviderSmall : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate (small): called with ${appWidgetIds.size} widgets, ids=${appWidgetIds.joinToString()}")
        // Schedule periodic updates
        WidgetUpdateWorker.schedulePeriodic(context)

        // Trigger immediate data refresh for all widget instances
        for (appWidgetId in appWidgetIds) {
            val key = getInstrumentForWidget(context, appWidgetId)
            Log.d(TAG, "onUpdate (small): widget $appWidgetId -> instrument=$key")
            triggerRefresh(context, appWidgetId)
       }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "onEnabled (small): widget added to home screen")
        WidgetUpdateWorker.schedulePeriodic(context)
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "onDisabled (small): last small widget removed")
    }

    override fun onReceive(context: Context, intent: android.content.Intent) {
        Log.d(TAG, "onReceive (small): action=${intent.action}")

        when (intent.action) {
            MOEXWidgetProvider.ACTION_MANUAL_REFRESH -> {
                Log.d(TAG, "Manual refresh triggered by tap! (small)")
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    triggerRefresh(context, appWidgetId)
                }
            }
            MOEXWidgetProvider.ACTION_SHOW_OVERLAY -> {
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    val instrumentKey = getInstrumentForWidget(context, appWidgetId)
                    try {
                        val instrument = Instrument.fromKey(instrumentKey)
                        val url = instrument.getInvestingUrl(context, appWidgetId)
                        val browserIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browserIntent)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to open Investing.com (small)", e)
                    }
                }
            }
            MOEXWidgetProvider.ACTION_TOGGLE_PERIOD -> {
                Log.d(TAG, "Period toggle triggered by tap on chart! (small)")
                val appWidgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    WidgetUpdateWorker.enqueuePeriodToggle(context, intArrayOf(appWidgetId))
                } else {
                    Log.w(TAG, "Period toggle: invalid widget ID (small)")
                }
            }
            else -> {
                super.onReceive(context, intent)
            }
        }
    }

    private fun triggerRefresh(context: Context, appWidgetId: Int) {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val key = "instrument_$appWidgetId"
        val instrumentKey = prefs.getString(key, "STOCK:SBER") ?: "STOCK:SBER"
        Log.d(TAG, "triggerRefresh (small): appWidgetId=$appWidgetId, instrumentKey=$instrumentKey")

        WidgetUpdateWorker.enqueueRefresh(
            context,
            instrumentKey,
            intArrayOf(appWidgetId)
        )
    }

    private fun getInstrumentForWidget(context: Context, appWidgetId: Int): String {
        val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
        val key = "instrument_$appWidgetId"
        val value = prefs.getString(key, "STOCK:SBER") ?: "STOCK:SBER"
        Log.d(TAG, "getInstrumentForWidget (small): id=$appWidgetId, key=$key, value=$value")
        return value
    }

    companion object {
        private const val TAG = "MOEXWidgetProviderSmall"

        /**
         * Sets up click handling on the small widget to trigger manual refresh.
         * Uses MOEXWidgetProviderSmall as the intent target so that onReceive()
         * on the small provider is invoked, not the large one.
         */
        fun updateWidgetClickHandler(
            context: Context,
            remoteViews: android.widget.RemoteViews,
            appWidgetId: Int
        ) {
            Log.d(TAG, "updateWidgetClickHandler: setting up for widget $appWidgetId")

            val refreshIntent = android.content.Intent(context, MOEXWidgetProviderSmall::class.java).apply {
                action = MOEXWidgetProvider.ACTION_MANUAL_REFRESH
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }

            val refreshPendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                appWidgetId * 3,
                refreshIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val showOverlayIntent = android.content.Intent(context, MOEXWidgetProviderSmall::class.java).apply {
                action = MOEXWidgetProvider.ACTION_SHOW_OVERLAY
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }

            val showOverlayPendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                appWidgetId * 3 + 1,
                showOverlayIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val togglePeriodIntent = android.content.Intent(context, MOEXWidgetProviderSmall::class.java).apply {
                action = MOEXWidgetProvider.ACTION_TOGGLE_PERIOD
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }

            val togglePeriodPendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                appWidgetId * 3 + 2,
                togglePeriodIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            remoteViews.setOnClickPendingIntent(R.id.chart_image, togglePeriodPendingIntent)
            remoteViews.setOnClickPendingIntent(R.id.ticker_text, showOverlayPendingIntent)
            remoteViews.setOnClickPendingIntent(R.id.price_text, refreshPendingIntent)
        }
    }
}