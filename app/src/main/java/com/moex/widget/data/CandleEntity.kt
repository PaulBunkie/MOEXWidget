package com.moex.widget.data

import androidx.room.Entity
import androidx.room.Index

/**
 * Room entity for persistent candle storage.
 * Each row is one candle for one instrument in one time period.
 */
@Entity(
    tableName = "candles",
    primaryKeys = ["instrumentKey", "time", "period", "appWidgetId"],
    indices = [Index("instrumentKey"), Index("instrumentKey", "appWidgetId")]
)
data class CandleEntity(
    val instrumentKey: String,
    val time: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val period: String = "1h",   // задел на переключение день/неделя
    val appWidgetId: Int = 0
)
