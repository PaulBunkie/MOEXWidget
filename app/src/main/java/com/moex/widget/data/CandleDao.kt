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
    @Query("SELECT * FROM candles WHERE instrumentKey = :instrumentKey AND period = :period ORDER BY time ASC")
    suspend fun getCandles(instrumentKey: String, period: String = "1h"): List<CandleEntity>

    /**
     * Inserts a batch of candles. Existing rows with same primary key are replaced.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCandles(candles: List<CandleEntity>)
}