# MOEX Widget - Android виджет котировок

Домашний виджет для Android, отображающий график цены финансового инструмента. Три периода (1h / 1w / 1d), три рынка (MOEX / Crypto / US Stocks). Автоматическое обновление раз в час, ручное обновление по тапу.

## Функциональность

### Виджет
- **Тикер** — крупное жирное название инструмента
- **Текущая цена** — рядом с тикером, крупный шрифт
- **Три периода графика** — переключение тапом на график:
  - **1h** — последние 24 часа (часовые свечи, подписи HH:mm)
  - **1w** — последние 10 дней (дневные свечи, подписи dd.MM)
  - **1d** — последние 30 дней (дневные свечи, подписи dd.MM)
- **График цены** — линейный график закрытия свечей с:
  - горизонтальными и вертикальными grid-линиями
  - подписями цен справа и времени снизу
  - цветовой индикацией тренда: 🟢 зелёный растёт, 🔴 розовый падает
  - заливкой под линией графика
  - точками на данных (при ≤48 свечах)
- **Два размера** — большой (4×2, с подписями) и компактный (2×2, без подписей)

### Данные
- **Три рынка** на выбор:
  - **MOEX** — акции Московской биржи (SBER, GAZP, LKOH, ...)
  - **Crypto** — криптовалюты с Binance (BTCUSDT, ETHUSDT, ...)
  - **US Stocks** — американские акции через Yahoo Finance (AAPL, NVDA, TSLA, ...)
- **Автоматическое обновление** — раз в час через WorkManager
- **Ручное обновление** — тап на виджет (тикер, цена или график)
- **Кэширование** — Room база данных; при отсутствии интернета показывается последний кэш
- **Цвет тренда** — зелёный (рост) / розовый (падение) для линии, заливки и точек

## Поддерживаемые инструменты

### MOEX (Акции)
AFLT, ALRS, GAZP, GMKN, HYDR, IRAO, LKOH, MGNT, MOEX, MTSS, PHOR, PLZL, ROSN, RUAL, SBER, SNGS, TATN, TCSG, VTBR, YNDX

### Crypto (Криптовалюты)
ADAUSDT, ATOMUSDT, AVAXUSDT, BNBUSDT, BTCUSDT, DOGEUSDT, DOTUSDT, ETHUSDT, LINKUSDT, LTCUSDT, MATICUSDT, SHIBUSDT, SOLUSDT, UNIUSDT, XRPUSDT

### US Stocks (Yahoo Finance)
AAPL, AMD, AMZN, BA, CRM, DIS, GOOGL, INTC, JNJ, JPM, KO, MA, META, MSFT, NFLX, NVDA, PYPL, TSLA, V, WMT

## Как работает обновление данных

### Автоматическое обновление (раз в час)
- **WorkManager** с периодическим запуском каждые 60 минут
- Дополнительно настроен `android:updatePeriodMillis="3600000"` в `widget_info.xml`
- **Разрешения не требуются** — WorkManager работает в рамках стандартных возможностей Android
- При обновлении данные кэшируются в Room — при отсутствии интернета показывается последний кэш

### Обновление по тапу
- Триггерит `ACTION_MANUAL_REFRESH` в `MOEXWidgetProvider`
- Запускает `WidgetUpdateWorker` для загрузки свежих данных

### Поток обновления
```
AppWidgetProvider.onUpdate() / Tap → Manual Refresh
        ↓
WorkManager (60 min periodic / one-time)
        ↓
PriceProvider (MOEX ISS API / Binance API / Yahoo Finance)
        ↓
Candle data → Room DB (CandleDao)
        ↓
ChartRenderer (Bitmap)
        ↓
RemoteViews update
```

## Технологии

- **Kotlin** — основной язык программирования
- **Android AppWidget API** — создание виджетов
- **WorkManager** — фоновая синхронизация (периодическая и одноразовая)
- **Room** — база данных для кэширования свечей
- **OkHttp** — HTTP клиент для запросов к API
- **Gson** — парсинг JSON-ответов
- **Canvas** — рисование графика как Bitmap
- **Локализация** — русский и английский языки (`values-ru/strings.xml`)

## Структура проекта

```
app/src/main/java/com/moex/widget/
├── LauncherProxyActivity.kt     # Прозрачная launcher-activity для маркета
├── data/
│   ├── AppDatabase.kt           # Room база данных
│   ├── Candle.kt                # Модель данных свечи
│   ├── CandleDao.kt             # DAO для доступа к свечам
│   ├── CandleEntity.kt          # Room entity для свечей
│   ├── Instrument.kt            # Модель инструмента (Stock/Crypto/YahooStock)
│   ├── PriceProvider.kt         # Интерфейс провайдера данных
│   ├── MoexApiClient.kt         # Клиент MOEX ISS API
│   ├── CryptoProvider.kt        # Клиент Binance API
│   └── YahooProvider.kt         # Клиент Yahoo Finance API
├── chart/
│   └── ChartRenderer.kt         # Рендерер графика (цвет тренда, заливка, подписи)
├── widget/
│   ├── MOEXWidgetProvider.kt    # AppWidgetProvider (большой виджет 4×2)
│   ├── MOEXWidgetProviderSmall.kt # AppWidgetProvider (маленький виджет 2×2)
│   └── WidgetConfigActivity.kt  # Экран настройки виджета
└── worker/
    ├── WidgetUpdateWorker.kt    # WorkManager worker
    └── WidgetUpdateService.kt   # Сервис обновления
```

### Ресурсы
```
app/src/main/res/
├── layout/
│   ├── widget_layout.xml            # Макет большого виджета (4×2, с подписями)
│   ├── widget_layout_small.xml      # Макет маленького виджета (2×2, без подписей)
│   └── activity_widget_config.xml   # Экран настройки виджета
├── drawable/
│   ├── ic_widget_preview_large.png  # Превью большого виджета
│   └── ic_widget_preview_small.png  # Превью маленького виджета
├── xml/
│   ├── widget_info.xml              # Конфиг большого виджета
│   └── widget_small_info.xml        # Конфиг маленького виджета
├── values/
│   ├── strings.xml                  # Строки (EN)
│   ├── colors.xml                   # Цвета
│   └── themes.xml                   # Темы
└── values-ru/
    └── strings.xml                  # Строки (RU)
```

## Сборка

1. Откройте проект в Android Studio
2. Подождите пока Gradle синхронизирует зависимости
3. Запустите приложение на устройстве или эмуляторе
4. Добавьте виджет на рабочий стол

### Сборка release APK

1. Убедитесь, что keystore настроен в `app/build.gradle.kts` (блок `signingConfigs`)
2. В Android Studio: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
3. APK будет создан в `app/build/outputs/apk/release/app-release.apk`
4. Загрузите APK на маркет (RuStore, RuMarket и т.д.)

> **Примечание:** `LauncherProxyActivity` — прозрачная activity с LAUNCHER intent-filter, необходимая для корректного определения типа устройства (MOBILE) маркетами приложений.

## Использование

1. Откройте список виджетов на рабочем столе
2. Найдите "Stock & Crypto Chart" — доступны два размера:
   - **Stock & Crypto Chart** — большой виджет (4×2) с графиком и подписями цен/времени
   - **Stock & Crypto Chart (Small)** — компактный виджет (2×2) с мини-графиком без подписей
3. Перетащите нужный размер на рабочий стол
4. Откроется экран настройки:
   - Выберите рынок (MOEX / Crypto / US Stocks)
   - Выберите инструмент из списка
   - Нажмите «Добавить виджет»
5. Виджет автоматически загрузит данные за последние 24 часа
6. Для ручного обновления нажмите на виджет
7. Виджет обновляется автоматически раз в час

## API

### MOEX ISS API
- Endpoint: `https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/{ticker}/candles.json`
- Интервал: 60 минут
- Период: последние 24 часа

### Binance API
- Endpoint: `https://api.binance.com/api/v3/klines?symbol={symbol}&interval=1h&limit=24`
- Интервал: 1 час
- Период: последние 24 часа (24 свечи)

### Yahoo Finance API
- Интервал: 1 час
- Период: последние 24 часа

## Планы на развитие

- Уведомления при резком изменении цены
- Больше криптовалютных пар и американских акций
- Кэш истории за месяц
