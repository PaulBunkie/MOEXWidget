# MOEX Widget - Android виджет котировок MOEX и Crypto

Android-приложение с домашним виджетом, который отображает график цены выбранного инструмента (акции MOEX или криптовалюты) за последние 24 часа и обновляется автоматически раз в час.

## Функциональность

- **Два размера виджета** - большой (4x2) и компактный (2x2) на выбор
- **Виджет на рабочем столе** - отображает график цены инструмента за 24 часа
- **Поддержка двух рынков**:
  - **MOEX (акции)** - Moscow Exchange ISS API
  - **Crypto (криптовалюты)** - Binance API
- **Выбор инструмента** - экран настройки при добавлении виджета
- **Автоматическое обновление** - раз в час через WorkManager
- **Ручное обновление** - тап на виджет для немедленного обновления
- **Кэширование** - Room база данных для хранения свечей
- **Fallback** - при отсутствии интернета использует кэш
- **Цветовая индикация тренда** - зеленый для растущих, розовый для падающих инструментов
- **Локализация** - интерфейс на русском и английском языках

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
- При обновлении данные кэшируются в Room - при отсутствии интернета показывается последний кэш

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
Candle data → Room DB (CandleDao)
        ↓
ChartRenderer (Bitmap)
        ↓
RemoteViews update
```

## Технологии

- **Kotlin** - основной язык программирования
- **Android AppWidget API** - для создания виджета
- **WorkManager** - для фоновой синхронизации (периодической и одноразовой)
- **Room** - база данных для кэширования свечей
- **OkHttp** - HTTP клиент для запросов к API
- **Gson** - парсинг JSON-ответов от API
- **Canvas** - рисование графика как Bitmap
- **Локализация** - русский и английский языки (`values-ru/strings.xml`)

## Структура проекта

```
app/src/main/java/com/moex/widget/
├── LauncherProxyActivity.kt     # Прозрачная launcher-activity для маркета
├── data/
│   ├── AppDatabase.kt           # Room база данных
│   ├── Candle.kt                # Модель данных свечи
│   ├── CandleDao.kt             # DAO для доступа к свечам
│   ├── CandleEntity.kt          # Room entity для свечей
│   ├── Instrument.kt            # Модель инструмента (Stock/Crypto)
│   ├── PriceProvider.kt         # Интерфейс провайдера данных
│   ├── MoexApiClient.kt         # Клиент MOEX ISS API
│   ├── CryptoProvider.kt        # Клиент Binance API
│   └── YahooProvider.kt         # Клиент Yahoo Finance API
├── chart/
│   └── ChartRenderer.kt         # Рендерер графика (цвет тренда)
├── widget/
│   ├── MOEXWidgetProvider.kt    # AppWidgetProvider (большой виджет)
│   ├── MOEXWidgetProviderSmall.kt # AppWidgetProvider (маленький виджет)
│   └── WidgetConfigActivity.kt  # Экран настройки виджета
└── worker/
    ├── WidgetUpdateWorker.kt    # WorkManager worker
    └── WidgetUpdateService.kt   # Сервис обновления
```

### Ресурсы
```
app/src/main/res/
├── layout/
│   ├── widget_layout.xml            # Макет большого виджета (4x2)
│   ├── widget_layout_small.xml      # Макет маленького виджета (2x2)
│   └── activity_widget_config.xml   # Экран настройки
├── drawable/
│   ├── ic_widget_preview_large.png  # Превью большого виджета
│   └── ic_widget_preview_small.png  # Превью маленького виджета
├── xml/
│   ├── widget_info.xml              # Конфиг большого виджета
│   └── widget_small_info.xml        # Конфиг маленького виджета
└── values/
    ├── strings.xml                  # Строки (EN)
    └── colors.xml                   # Цвета
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
2. Найдите "MOEX Stock Chart" - доступны два размера:
   - **MOEX Stock Chart** - большой виджет (4x2) с графиком и label'ами
   - **MOEX Stock Chart (Small)** - компактный виджет (2x2) с мини-графиком
3. Выберите нужный размер и перетащите на рабочий стол
4. Откроется экран настройки:
   - Выберите рынок (MOEX или Crypto)
   - Выберите инструмент из списка
   - Нажмите "Добавить виджет"
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

## Планы на развитие

- Переключение интервалов (1h / 5m / 1d)
- Кэш истории за неделю
- Уведомления при резком изменении цены
- Больше криптовалютных пар