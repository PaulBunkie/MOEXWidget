package com.moex.widget.data

/**
 * Interface for fetching candle data from different sources.
 * Each implementation handles a specific type of instrument (MOEX stocks, Crypto, etc.)
 */
interface PriceProvider {
    /**
     * Fetches 24-hour candle data for the given instrument.
     * @return Result containing list of candles or an error
     */
    fun fetch24hCandles(): Result<List<Candle>>

    /**
     * Returns the display name of the instrument.
     */
    fun getDisplayName(): String
}