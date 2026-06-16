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
import com.moex.widget.worker.WidgetUpdateWorker

/**
 * Small widget provider (2x2 size).
 * Uses the same logic as MOEXWidgetProvider but with a smaller layout.
 */
class MOEXWidgetProviderSmall : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        WidgetUpdateWorker.schedulePeriodic(context)

        for (appWidgetId in appWidgetIds) {
            triggerRefresh(context, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        WidgetUpdateWorker.schedulePeriodic(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.d(TAG, "onReceive: action=${intent.action}")

        if (MOEXWidgetProvider.ACTION_MANUAL_REFRESH == intent.action) {
            Log.d(TAG, "Manual refresh triggered by tap on small widget!")
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, MOEXWidgetProviderSmall::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (appWidgetId in appWidgetIds) {
                triggerRefresh(context, appWidgetId)
            }
        }
    }

    private fun triggerRefresh(context: Context, appWidgetId: Int) {
        val instrumentKey = getInstrumentForWidget(context, appWidgetId)
        Log.d(TAG, "triggerRefresh: appWidgetId=$appWidgetId, instrumentKey=$instrumentKey")

        WidgetUpdateWorker.enqueueRefresh(
            context,
            instrumentKey,
            intArrayOf(appWidgetId)
        )
    }

    companion object {
        private const val TAG = "MOEXWidgetSmall"

        private fun getInstrumentForWidget(context: Context, appWidgetId: Int): String {
            val prefs = context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
            val key = "instrument_$appWidgetId"
            val value = prefs.getString(key, "STOCK:SBER") ?: "STOCK:SBER"
            Log.d(TAG, "getInstrumentForWidget: key=$key, value=$value")
            return value
        }

        fun updateWidgetClickHandler(context: Context, remoteViews: RemoteViews, appWidgetId: Int) {
            val intent = Intent(context, MOEXWidgetProviderSmall::class.java).apply {
                action = MOEXWidgetProvider.ACTION_MANUAL_REFRESH
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