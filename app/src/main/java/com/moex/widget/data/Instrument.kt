package com.moex.widget.data

/**
 * Represents a financial instrument that can be displayed in the widget.
 * Supports both MOEX stocks and cryptocurrency pairs.
 */
sealed class Instrument {
    /**
     * Represents a stock from Moscow Exchange (MOEX).
     * @property ticker The stock ticker symbol (e.g., "SBER", "GAZP")
     */
    data class Stock(val ticker: String) : Instrument() {
        override val displayName: String get() = ticker
        override val type: String get() = "STOCK"
    }

    /**
     * Represents a cryptocurrency pair from Binance.
     * @property symbol The crypto pair symbol (e.g., "BTCUSDT", "ETHUSDT")
     */
    data class Crypto(val symbol: String) : Instrument() {
        override val displayName: String get() = symbol.replace("USDT", "/USDT")
        override val type: String get() = "CRYPTO"
    }

    abstract val displayName: String
    abstract val type: String

    /**
     * Returns a unique key for this instrument.
     */
    fun toKey(): String = "$type:${when (this) {
        is Stock -> ticker
        is Crypto -> symbol
    }}"

    companion object {
        /**
         * Creates an Instrument from a stored key string.
         */
        fun fromKey(key: String): Instrument {
            val parts = key.split(":", limit = 2)
            if (parts.size != 2) throw IllegalArgumentException("Invalid instrument key: $key")

            return when (parts[0]) {
                "STOCK" -> Stock(parts[1])
                "CRYPTO" -> Crypto(parts[1])
                else -> throw IllegalArgumentException("Unknown instrument type: ${parts[0]}")
            }
        }

        /**
         * List of popular MOEX stocks for quick selection.
         */
        val POPULAR_STOCKS = listOf(
            "SBER", "GAZP", "LKOH", "YNDX", "GMKN",
            "ROSN", "SNGS", "TCSG", "VTBR", "ALRS",
            "AFLT", "MGNT", "PHOR", "PLZL", "TATN",
            "HYDR", "IRAO", "MOEX", "MTSS", "RUAL"
        )

        /**
         * List of popular cryptocurrency pairs for quick selection.
         */
        val POPULAR_CRYPTOS = listOf(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
            "DOGEUSDT", "ADAUSDT", "DOTUSDT", "MATICUSDT", "SHIBUSDT",
            "AVAXUSDT", "LINKUSDT", "UNIUSDT", "ATOMUSDT", "LTCUSDT"
        )
    }
}