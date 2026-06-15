# MOEX Widget - Android виджет котировок MOEX и Crypto

Android-приложение с домашним виджетом, который отображает график цены выбранного инструмента (акции MOEX или криптовалюты) за последние 24 часа и обновляется автоматически раз в час.

## Функциональность

- **Виджет на рабочем столе** - отображает график цены инструмента за 24 часа
- **Поддержка двух рынков**:
  - **MOEX (акции)** - Moscow Exchange ISS API
  - **Crypto (криптовалюты)** - Binance API
- **Выбор инструмента** - экран настройки при добавлении виджета
- **Автоматическое обновление** - раз в час через WorkManager
- **Ручное обновление** - тап на виджет для немедленного обновления
- **Кэширование** - локальное хранение последнего результата
- **Fallback** - при отсутствии интернета использует кэш
- **Цветовая индикация тренда** - зеленый для растущих, розовый для падающих инструментов

## Поддерживаемые инструменты

### MOEX (Акции)
SBER, GAZP, LKOH, YNDX, GMKN, ROSN, SNGS, TCSG, VTBR, ALRS, AFLT, MGNT, PHOR, PLZL, TATN, HYDR, IRAO, MOEX, MTSS, RUAL

### Crypto (Криптовалюты)
BTCUSDT, ETHUSDT, BNBUSDT, SOLUSDT, XRPUSDT, DOGEUSDT, ADAUSDT, DOTUSDT, MATICUSDT, SHIBUSDT, AVAXUSDT, LINKUSDT, UNIUSDT, ATOMUSDT, LTCUSDT

## Как работает обновление данных

### Автоматическое обновление (раз в час)
- Используется **WorkManager** с периодическим запуском каждые 60 минут
- Система Android гарантирует выполнение примерно раз в час (допускается системная задержка)
- Дополнительно настроен `android:updatePeriodMillis="3600000"` в `widget_info.xml`
- **Разрешения не требуются** - WorkManager работает в рамках стандартных возможностей Android
- При обновлении данные кэшируются - при отсутствии интернета показывается последний кэш

### Обновление по тапу
- При нажатии на виджет (тикер, цену или график) запускается немедленное обновление
- Работает через PendingIntents на всех элементах виджета
- Триггерит `ACTION_MANUAL_REFRESH` в `MOEXWidgetProvider`
- Запускает `WidgetUpdateWorker` для загрузки свежих данных

### Поток обновления
```
AppWidgetProvider.onUpdate() / Tap → Manual Refresh
        ↓
WorkManager (60 min periodic / one-time)
        ↓
PriceProvider (MOEX ISS API / Binance API)
        ↓
Candle data → Cache
        ↓
ChartRenderer (Bitmap)
        ↓
RemoteViews update
```

## Технологии

- **Kotlin** - основной язык программирования
- **Android AppWidget API** - для создания виджета
- **WorkManager** - для фоновой синхронизации (периодической и одноразовой)
- **OkHttp** - HTTP клиент для запросов к API
- **Canvas** - рисование графика как Bitmap

## Структура проекта

```
app/src/main/java/com/moex/widget/
├── data/
│   ├── Candle.kt          # Модель данных свечи
│   ├── Instrument.kt      # Модель инструмента (Stock/Crypto)
│   ├── PriceProvider.kt   # Интерфейс провайдера данных
│   ├── MoexApiClient.kt   # Клиент MOEX ISS API
│   ├── CryptoProvider.kt  # Клиент Binance API
│   └── CacheManager.kt    # Менеджер кэширования
├── chart/
│   └── ChartRenderer.kt   # Рендерер графика (цвет тренда)
├── worker/
│   ├── WidgetUpdateWorker.kt  # WorkManager worker
│   └── WidgetUpdateService.kt # Сервис обновления
└── widget/
    ├── MOEXWidgetProvider.kt  # AppWidgetProvider
    └── WidgetConfigActivity.kt # Экран настройки виджета
```

## Установка

1. Откройте проект в Android Studio
2. Подождите пока Gradle синхронизирует зависимости
3. Запустите приложение на устройстве или эмуляторе
4. Добавьте виджет на рабочий стол

## Использование

1. Добавьте виджет "MOEX Stock Chart" на рабочий стол
2. Откроется экран настройки:
   - Выберите рынок (MOEX или Crypto)
   - Выберите инструмент из списка
   - Нажмите "Добавить виджет"
3. Виджет автоматически загрузит данные за последние 24 часа
4. Для ручного обновления нажмите на виджет
5. Виджет обновляется автоматически раз в час

## API

### MOEX ISS API
- Endpoint: `https://iss.moex.com/iss/engines/stock/markets/shares/boards/TQBR/securities/{ticker}/candles.json`
- Интервал: 60 минут
- Период: последние 24 часа

### Binance API
- Endpoint: `https://api.binance.com/api/v3/klines?symbol={symbol}&interval=1h&limit=24`
- Интервал: 1 час
- Период: последние 24 часа (24 свечи)

## Планы на развитие

- Переключение интервалов (1h / 5m / 1d)
- Кэш истории за неделю
- Уведомления при резком изменении цены
- Больше криптовалютных пар