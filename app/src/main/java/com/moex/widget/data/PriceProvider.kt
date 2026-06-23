package com.moex.widget.data

/**
 * Interface for fetching candle data from different sources.
 * Each implementation handles a specific type of instrument (MOEX stocks, Crypto, etc.)
 */
interface PriceProvider {
    /**
     * Fetches 24-hour candle data (hourly candles) for the given instrument.
     * @return Result containing list of candles or an error
     */
    fun fetch24hCandles(days: Int): Result<List<Candle>>

    /**
     * Fetches daily candle data for the given number of days.
     * @param days Number of days of historical data to fetch
     * @return Result containing list of daily candles or an error
     */
    fun fetchDailyCandles(days: Int = 30): Result<List<Candle>>

    /**
     * Returns the display name of the instrument.
     */
    fun getDisplayName(): String
}