package com.moex.widget.data

/**
 * Represents a single trading candle from MOEX ISS API.
 */
data class Candle(
    val time: Long,      // Unix timestamp in milliseconds
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double
)