package com.moex.widget.data

/**
 * Represents a financial instrument that can be displayed in the widget.
 * Supports MOEX stocks, cryptocurrency pairs, and US stocks (Yahoo Finance).
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

    /**
     * Represents a US stock from Yahoo Finance.
     * @property ticker The stock ticker symbol (e.g., "AAPL", "NVDA")
     */
    data class YahooStock(val ticker: String) : Instrument() {
        override val displayName: String get() = ticker
        override val type: String get() = "YAHOO"
    }

    abstract val displayName: String
    abstract val type: String

    /**
     * Returns a unique key for this instrument.
     */
    fun toKey(): String = "$type:${when (this) {
        is Stock -> ticker
        is Crypto -> symbol
        is YahooStock -> ticker
    }}"

    /**
     * Returns the Investing.com URL for this instrument.
     * Uses a pre-stored slug for 100% accuracy, or falls back to search.
     */
    fun getInvestingUrl(context: android.content.Context, appWidgetId: Int): String {
        val prefs = context.getSharedPreferences("widget_prefs", android.content.Context.MODE_PRIVATE)
        val slug = prefs.getString("investing_slug_$appWidgetId", null)
        
        if (slug != null) {
            return if (slug.startsWith("http")) slug else "https://www.investing.com$slug"
        }

        // Fallback for old widgets (simple search)
        val q = when (this) {
            is Stock -> ticker
            is Crypto -> symbol.replace("USDT", "")
            is YahooStock -> ticker
        }
        return "https://www.investing.com/search/?q=${q.lowercase()}"
    }

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
                "YAHOO" -> YahooStock(parts[1])
                else -> throw IllegalArgumentException("Unknown instrument type: ${parts[0]}")
            }
        }

        /**
         * List of popular MOEX stocks for quick selection (sorted alphabetically).
         */
        val POPULAR_STOCKS = listOf(
            "IMOEXF", "AFLT", "ALRS", "GAZP", "GMKN", "HYDR",
            "IRAO", "LKOH", "MGNT", "MOEX", "MTSS",
            "PHOR", "PLZL", "ROSN", "RUAL", "SBER",
            "SNGS", "TATN", "TCSG", "VTBR", "YNDX"
        )

        /**
         * List of popular cryptocurrency pairs for quick selection (sorted alphabetically).
         */
        val POPULAR_CRYPTOS = listOf(
            "ADAUSDT", "ATOMUSDT", "AVAXUSDT", "BNBUSDT", "BTCUSDT",
            "DOGEUSDT", "DOTUSDT", "ETHUSDT", "LINKUSDT", "LTCUSDT",
            "MATICUSDT", "SHIBUSDT", "SOLUSDT", "UNIUSDT", "XRPUSDT"
        )

        /**
         * List of popular US stocks (Yahoo Finance) for quick selection (sorted alphabetically).
         */
        val POPULAR_YAHOO_STOCKS = listOf(
            "^DJI", "AAPL", "AMD", "AMZN", "BA", "CRM",
            "DIS", "GOOGL", "INTC", "JNJ", "JPM",
            "KO", "MA", "META", "MSFT", "NFLX",
            "NVDA", "PYPL", "TSLA", "V", "WMT"
        )
    }
}