package com.moex.widget.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data access object for candle history.
 * Supports insert and query by instrument key and period.
 */
@Dao
interface CandleDao {

    /**
     * Returns all candles for a given instrument and period, ordered by time ascending.
     */
    @Query("SELECT * FROM candles WHERE instrumentKey = :instrumentKey AND appWidgetId = :appWidgetId AND period = :period ORDER BY time ASC")
    suspend fun getCandles(instrumentKey: String, appWidgetId: Int, period: String = "1h"): List<CandleEntity>

    @Query("SELECT * FROM candles WHERE instrumentKey = :instrumentKey AND appWidgetId = :appWidgetId ORDER BY time ASC")
    suspend fun getAllCandles(instrumentKey: String, appWidgetId: Int): List<CandleEntity>

    @Query("DELETE FROM candles WHERE instrumentKey = :instrumentKey AND appWidgetId = :appWidgetId")
    suspend fun deleteCandlesForWidget(instrumentKey: String, appWidgetId: Int)

    @Query("DELETE FROM candles WHERE instrumentKey = :instrumentKey AND appWidgetId = :appWidgetId AND period = :period")
    suspend fun deleteCandlesForWidgetByPeriod(instrumentKey: String, appWidgetId: Int, period: String)

    /**
     * Inserts a batch of candles. Existing rows with same primary key are replaced.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<CandleEntity>)

    /**
     * Deletes hourly candles older than cutoffMs (keeps 7 days of hourly data).
     */
    @Query("DELETE FROM candles WHERE period = '1h' AND time < :cutoffMs")
    suspend fun deleteOldHourly(cutoffMs: Long): Int

    /**
     * Deletes daily candles older than cutoffMs (keeps 60 days of daily data).
     */
    @Query("DELETE FROM candles WHERE period = '1d' AND time < :cutoffMs")
    suspend fun deleteOldDaily(cutoffMs: Long): Int
}
