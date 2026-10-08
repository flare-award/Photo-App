# Serendip — архитектура

Документ описывает, как устроено приложение и почему именно так. Всё, что
касается фоновой работы, опирается на официальную документацию Android
(Foreground services, *Restrictions on starting a foreground service from the
background*, *Foreground service types*, CameraX), а не на обходные приёмы.

Содержание

1. [Слои и пакеты](#1-слои-и-пакеты)
2. [Модель данных](#2-модель-данных)
3. [Планировщик (`CaptureScheduler`)](#3-планировщик-capturescheduler)
4. [Движок (`AutoCaptureEngine`) и конвейер съёмки](#4-движок-autocaptureengine-и-конвейер-съёмки)
5. [Foreground service и правила Android](#5-foreground-service-и-правила-android)
6. [Перезагрузка, обновление, смерть процесса](#6-перезагрузка-обновление-смерть-процесса)
7. [Камера](#7-камера)
8. [Хранилище фотографий](#8-хранилище-фотографий)
9. [Уведомления](#9-уведомления)
10. [Фоновая работа и батарея](#10-фоновая-работа-и-батарея)
11. [UI](#11-ui)
12. [Тесты и сборка](#12-тесты-и-сборка)

---

## 1. Слои и пакеты

```
presentation ──▶ di.AppGraph ──▶ service / scheduler / camera / storage / notifications / data / system
                                       │
                                       ▼
                                    domain  (чистый Kotlin, без Android)
```

| Пакет | Ответственность |
|---|---|
| `domain.model` | `AutoPhotoSettings`, `SchedulerState`, `CaptureEvent`, `SystemStatus`, enum-ы причин |
| `domain.repository` | интерфейсы `SettingsRepository`, `SchedulerStateRepository`, `CaptureEventRepository`, `AppFlagsRepository` |
| `domain.scheduler` | `CaptureScheduler` — вся математика расписания, детерминированно тестируется |
| `domain.camera` | `CameraCaptureSource`, `CaptureRequest`, `CaptureResult` |
| `data.settings` | DataStore Preferences: настройки + флаги; состояние планировщика (отдельный файл) |
| `data.db` | Room: таблица `capture_events` (история и галерея) |
| `camera` | `CameraXCaptureSource` — одиночный снимок |
| `storage` | `MediaStorePhotoStorage` — постоянное хранилище, проверка файла |
| `scheduler` | `AutoCaptureEngine` (единственная точка решений), `CaptureAlarmScheduler`, `CaptureAlarmReceiver` |
| `service` | `AutoCaptureService` (FGS `camera`), `ServiceController`, `BootCompletedReceiver`, `ServiceCommandReceiver` |
| `notifications` | `SerendipNotifications` — каналы, FGS-уведомление, «новое фото», «нужно внимание» |
| `system` | `PermissionChecker`, `BatteryOptimizationChecker`, `ScreenStateProvider`, `WakeLocks`, `SystemStatusMonitor`, `AppLog` |
| `di` | `AppGraph` — ручной граф (один экземпляр на процесс, живёт в `SerendipApp`) |
| `presentation` | Compose-экраны, ViewModel-и, тема, навигация |

DI-фреймворка нет намеренно: граф маленький, а ручная сборка прозрачна и не
стоит времени компиляции.

---

## 2. Модель данных

Хранится ровно то, что требуется, и ничего больше.

**Настройки** (`AutoPhotoSettings`, DataStore `serendip_settings`)

| Поле | Тип | По умолчанию |
|---|---|---|
| `enabled` | Boolean | `false` |
| `dailyPhotoLimit` | Int 1…20 | `3` |
| `scheduleMode` | `FULLY_RANDOM` / `RANDOM_INTERVAL` | `FULLY_RANDOM` |
| `minIntervalMinutes` / `maxIntervalMinutes` | Int (5 мин … 12 ч, min ≤ max) | `30` / `120` |
| `notifyOnSuccess` | Boolean | `true` |
| `skipWhenScreenOff` | Boolean | `false` |

Плюс флаг `onboardingCompleted`. Значения проходят `sanitized()` при чтении, так
что повреждённые или устаревшие данные никогда не дают некорректного состояния.

**Состояние планировщика** (`SchedulerState`, DataStore `serendip_scheduler`) —
единственный источник истины для расписания:

`dayKey` (локальная дата), `zoneId`, `planSignature` (подпись настроек, по
которым строился план), `photosTakenToday`, `plannedSlots` (моменты режима
«Полностью случайно»), `nextCaptureAt`, `lastSuccessAt`, `lastAttemptAt`,
`consecutiveFailures`, `captureInProgressSince`, `pauseReason`.

**История** (`CaptureEvent`, Room `capture_events`): `timestamp`, `zoneId`,
`localDate`, `outcome` (`SUCCESS` / `FAILED` / `SKIPPED_SCREEN_OFF` / `MISSED`),
`photoUri?`, `failureReason?`, `missedCount`. Галерея — это те же записи с
`outcome = SUCCESS` и непустым `photoUri`; при удалении файла (из приложения
или извне) `photoUri` обнуляется, запись в истории остаётся.

Статус системы (`SystemStatus`: разрешение камеры, уведомления,
`BackgroundRestriction`) **не** хранится — он читается у ОС при каждом показе.

---

## 3. Планировщик (`CaptureScheduler`)

Чистый Kotlin, без таймеров, без Android. Вход — настройки, состояние, `now`,
часовой пояс; выход — новое состояние. Все переходы детерминированы и покрыты
тестами (`CaptureSchedulerTest`, 23 теста).

### `reconcile(settings, state, now, zone, minLeadMs)`

Вызывается при каждом «тике» движка и приводит состояние в соответствие с
реальностью, **ничего не пересоздавая без нужды**:

1. **Новый день / другой часовой пояс** → счётчик `photosTakenToday = 0`,
   свежий план на новый день.
2. **Изменились настройки** (`planSignature` отличается) → план перестраивается
   с учётом уже сделанных сегодня снимков (лимит не превышается, уже сделанные
   не повторяются).
3. **Пропущенные моменты** (телефон был выключен / процесс мёртв): все слоты
   старше допустимого окна считаются пропущенными (`missedSlots`), в историю
   пишется одна запись `MISSED`, и **никакого догоняющего залпа** — следующий
   момент выбирается естественно от `now`.
4. Если `nextCaptureAt` отсутствует, а лимит не исчерпан — назначается следующий.
5. Зависшая попытка (`captureInProgressSince` старше таймаута) снимается.
6. При старте по действию пользователя (`minLeadMs = 90 с`) момент, который
   «вот-вот», слегка откладывается, чтобы приложение не щёлкало сразу после включения.

### Режимы

* **Полностью случайно** — N моментов за день выбираются из равномерного
  распределения на оставшемся окне дня с минимальным зазором (20 мин, при
  нехватке времени — 5 мин). Так нет «серий» и нет дырявых хвостов. План
  хранится в `plannedSlots` и переживает перезапуск; перестраивается только по
  причинам из `ReplanReason`.
* **Случайный интервал** — после каждой попытки (успех, пропуск, ошибка)
  выбирается новая пауза `random(min…max)`; дневной лимит соблюдается, после
  его исчерпания следующий момент не назначается до полуночи.

### Один момент — одна съёмка

`isDue(state, now)` → `claim(state, now)` сохраняет `captureInProgressSince` **до**
обращения к камере; `onAttemptFinished` фиксирует результат и назначает
следующий момент. Движок делает это под одним `Mutex`, а состояние
персистится после каждого шага — поэтому будильник, таймер, смена настроек и
перезапуск сервиса не могут породить две съёмки на один момент.

### Пауза

`pause(reason)` обнуляет план и запоминает `PauseReason`; `resume()` снимает
паузу и сбрасывает `planSignature`, так что следующий `reconcile` строит свежий
план. Причины, требующие действия пользователя: `REBOOT`, `APP_UPDATED`,
`CAMERA_PERMISSION_MISSING`, `CAMERA_ACCESS_BLOCKED`, `SERVICE_NOT_ALLOWED`,
`NO_BACK_CAMERA`, `SERVICE_NOT_RUNNING`.

### Пробуждения

`nextWakeAt` = ближайший из: `nextCaptureAt`, момент сразу после полуночи
(чтобы счётчик дня обновился даже без запланированных снимков).

---

## 4. Движок (`AutoCaptureEngine`) и конвейер съёмки

Один экземпляр на процесс. Все внешние события превращаются в `tick(trigger)`
под `Mutex`:

| Триггер | Откуда |
|---|---|
| `START` | сервис вышел в foreground (`engine.start(reason)`) |
| `ALARM` | `CaptureAlarmReceiver` |
| `TIMER` | внутрипроцессный таймер (проверяет часы раз в минуту — `delay` не идёт в глубоком сне) |
| `SETTINGS_CHANGED` | поток настроек (лимит, режим, интервалы, фильтр экрана) |
| `TIME_CHANGED` | `ACTION_TIME_CHANGED` / `TIMEZONE_CHANGED` / `DATE_CHANGED` (receiver живёт только пока жив сервис) |

Порядок проверок в одном тике:

```
режим включён? ─no─▶ снять будильники, Stopped
   │yes
пауза? ──требует пользователя и старт не от пользователя──▶ Paused(reason)
   │
CAMERA разрешена? ─no─▶ pause(CAMERA_PERMISSION_MISSING) + уведомление
   │
reconcile(): день, настройки, пропуски (MISSED), следующий момент
   │
isDue? ─yes─▶ claim() → persist → runAttempt()
   │                      ├─ skipWhenScreenOff && экран выключен → SKIPPED_SCREEN_OFF (не считается успехом)
   │                      ├─ < 50 МБ свободно → FAILED(STORAGE_LOW)
   │                      ├─ partial wake lock (≤ 60 с)
   │                      ├─ CameraCaptureSource.capture() → MediaStore + verify()
   │                      ├─ SUCCESS → запись в Room → (если включено) уведомление с миниатюрой
   │                      └─ FAILED(reason) → запись в Room; «блокирующие» причины → пауза
   │          onAttemptFinished() → новое состояние → persist
   │
назначить пробуждение: AlarmManager + таймер; status = Running(nextWakeAt)
```

Будильник (`CaptureAlarmScheduler`) — два неточных будильника без
`SCHEDULE_EXACT_ALARM`: `setWindow` (окно 10 мин) и `setAndAllowWhileIdle`
(работает в Doze, система может отложить до ~9–15 мин). Для «случайных моментов»
такая точность более чем достаточна и не требует спорного разрешения на точные
будильники.

`CaptureAlarmReceiver` отдаёт работу движку и ждёт её не дольше бюджета
broadcast-а (`goAsync`, 8 с); сама съёмка живёт в scope движка со своим wake
lock, поэтому никогда не обрывается на середине. **Receiver не запускает
сервис камеры** — из фона это запрещено (см. §5). Если будильник сработал, а
сервис не жив, движок ставит паузу `SERVICE_NOT_RUNNING` и показывает
уведомление с кнопкой «Возобновить».

---

## 5. Foreground service и правила Android

Факты, на которых всё построено (официальная документация, состояние на 2026 год):

1. **Доступ к камере из фона запрещён с Android 11** — приложение без видимой
   активити или foreground service типа `camera` получает ошибку открытия
   камеры. Отсюда `AutoCaptureService` с `android:foregroundServiceType="camera"`,
   разрешениями `FOREGROUND_SERVICE` и `FOREGROUND_SERVICE_CAMERA`.
2. **Android 12+ (targetSdk ≥ 31): запуск foreground service из фона запрещён**
   (`ForegroundServiceStartNotAllowedException`), за исключением списка
   исключений (взаимодействие с уведомлением, `BOOT_COMPLETED`, точный
   будильник и т.п.).
3. **Android 14+ (targetSdk ≥ 34): для типов «while-in-use» (`camera`,
   `microphone`, `location`) действует дополнительное правило.** Даже если запуск
   из фона формально разрешён исключением (например, из `BOOT_COMPLETED`),
   `startForeground(..., TYPE_CAMERA)` бросает `SecurityException`, если у
   приложения нет «while-in-use»-доступа. Такой доступ есть, когда сервис
   стартует **пока видна активити приложения** или **по нажатию пользователя
   на действие уведомления** (`PendingIntent.getForegroundService`).
4. Android 15/16 ввели лимиты времени для некоторых типов FGS; тип `camera` под
   них не попадает. API 37 новых правил для камеры не добавляет.

Как это реализовано:

* Сервис стартует **только** из `ServiceController.startFromVisibleApp()`
  (вызывается из `MainActivity`: переключатель, кнопка «Возобновить»,
  `onStart()`), либо через действие «Возобновить» в уведомлении
  (`PendingIntent.getForegroundService`). Больше нигде в коде
  `startForegroundService` не вызывается.
* `AutoCaptureService.onStartCommand` проверяет разрешение камеры, затем
  `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_CAMERA)` внутри
  `try`. `SecurityException` и `IllegalStateException` (включая
  `ForegroundServiceStartNotAllowedException`, `Missing/InvalidForegroundServiceTypeException`)
  → `engine.pauseRequiringUser(SERVICE_NOT_ALLOWED)` + `stopSelf()`. Никаких
  повторов по кругу, никаких обходов.
* Сервис `START_STICKY`, `stopWithTask="false"`: убирание приложения из
  «недавних» его не останавливает. Он сам останавливается, когда пользователь
  выключает режим (наблюдает поток настроек), и держит уведомление честным
  («Сегодня 2 из 5 · следующее около 14:30»).
* Пока сервис жив, он — «хозяин» движка (`hosted = true`); без него движок не
  пытается снимать, а только сообщает пользователю.

---

## 6. Перезагрузка, обновление, смерть процесса

| Событие | Что происходит |
|---|---|
| **Процесс убит / сервис перезапущен системой** | `START_STICKY` → `onStartCommand(null)` → `startForeground` (под `try`) → `engine.start(SYSTEM_RESTART)` → `reconcile()` восстанавливает план из DataStore, пропущенное помечается `MISSED`, ничего не «догоняется». |
| **Будильник пришёл, сервиса нет** | пауза `SERVICE_NOT_RUNNING`, уведомление «Возобновить»; сервис **не** стартует из receiver-а. |
| **Перезагрузка** (`BOOT_COMPLETED`) | `BootCompletedReceiver` → `pauseRequiringUser(REBOOT)`: настройки, счётчик дня и расписание уже на диске; показывается уведомление с «Возобновить». Открытие приложения также возобновляет (из `onStart`). |
| **Обновление приложения** (`MY_PACKAGE_REPLACED`) | то же с причиной `APP_UPDATED`. |
| **Полночь** | движок просыпается сразу после полуночи (`nextWakeAt`), `reconcile()` видит новый `dayKey` → счётчик 0, новый план. |
| **Смена времени / пояса** | receiver в сервисе → `TIME_CHANGED` → план пересчитывается относительно нового локального дня. |
| **Отзыв разрешения камеры** | следующий тик → пауза `CAMERA_PERMISSION_MISSING`; на главном экране баннер с кнопками «Разрешить» / «Открыть настройки». |

Почему после перезагрузки нужно действие пользователя: `BOOT_COMPLETED` входит в
список исключений для запуска FGS из фона, но **не даёт while-in-use-доступа**,
поэтому запуск сервиса типа `camera` оттуда закончится `SecurityException` на
Android 14+. Вместо того чтобы ловить это исключение «на удачу», приложение
честно просит пользователя об одном нажатии.

---

## 7. Камера

`CameraXCaptureSource` (CameraX 1.6, `camera-camera2` + `camera-lifecycle`):

* только `ImageCapture` (без Preview/Analysis/VideoCapture — микрофон не
  используется и не запрашивается);
* `CameraSelector.DEFAULT_BACK_CAMERA`; отсутствие задней камеры → `NO_BACK_CAMERA`;
* собственный `LifecycleOwner` на одну съёмку (`STARTED` → снимок → `DESTROYED`),
  `unbindAll()` в `finally`;
* `FLASH_MODE_OFF`, `CAPTURE_MODE_MAXIMIZE_QUALITY`, JPEG 92, до 12 МП (4:3);
* снимок пишется напрямую в MediaStore (`OutputFileOptions.Builder(resolver, collection, values)`);
* `CameraState` наблюдается во время съёмки: `ERROR_CAMERA_IN_USE`,
  `ERROR_MAX_CAMERAS_IN_USE`, `ERROR_CAMERA_DISABLED`, `ERROR_CAMERA_FATAL_ERROR`,
  `ERROR_DO_NOT_DISTURB_MODE_ENABLED` обрывают попытку сразу, а не по таймауту;
* таймауты: провайдер 10 с, съёмка 25 с; поздно пришедший файл после таймаута удаляется;
* ошибки превращаются в `FailureReason` (`CAMERA_IN_USE`, `CAMERA_ACCESS_BLOCKED`,
  `CAMERA_UNAVAILABLE`, `CAMERA_ERROR`, `CAPTURE_TIMEOUT`, `STORAGE_WRITE_FAILED`,
  `IMAGE_INVALID`…) — пользователь видит только их человекочитаемые тексты,
  исключение целиком уходит в `AppLog`.

Звук затвора приложение не воспроизводит; там, где платформа принудительно
включает звук (региональное требование), он остаётся системным.

---

## 8. Хранилище фотографий

`MediaStorePhotoStorage`: публичный альбом `Pictures/Serendip/` в
`MediaStore.Images` — постоянное, принадлежащее пользователю хранилище, видимое
в системной галерее, переживающее перезапуск, перезагрузку и смерть процесса.
Кэш и временные каталоги не используются вообще.

После съёмки файл **проверяется** (`verify`): размер > 1 КБ, заголовок
декодируется (`inJustDecodeBounds`), размеры > 0, флаг `IS_PENDING` снят. Не
прошедший проверку файл удаляется, попытка считается `IMAGE_INVALID`, и в
галерее никогда не появляется битый кадр.

Если пользователь удалил фото из системной галереи, `PhotosViewModel` при
открытии экрана сверяет записи с MediaStore (`reconcilePhotos`) и обнуляет
`photoUri` у исчезнувших — история сохраняется, «битых» миниатюр нет.

Свободное место проверяется перед каждой съёмкой (порог 50 МБ) → `STORAGE_LOW`.

---

## 9. Уведомления

| Канал | Важность | Содержимое |
|---|---|---|
| `service` | Low, беззвучный | обязательное FGS-уведомление: «Автоматические фотографии включены · Сегодня X из Y · следующее около HH:mm», действие «Выключить» |
| `photos` | Default | «Новая фотография · Сделана в HH:mm» с миниатюрой (BigPicture); только после успешной, проверенной и записанной съёмки и только если `notifyOnSuccess` |
| `attention` | High | «Автоматические фотографии на паузе» + причина; действие «Возобновить» (`PendingIntent.getForegroundService`) |

`POST_NOTIFICATIONS` запрашивается на Android 13+ в онбординге и в настройках;
без него съёмка продолжает работать (FGS-уведомление система может скрыть),
`SecurityException` при `notify()` перехватывается.

---

## 10. Фоновая работа и батарея

`BatteryOptimizationChecker` использует только публичные API:

* `ActivityManager.isBackgroundRestricted` → **⛔ Ограничено** (пользователь
  выбрал «Ограничено» в настройках батареи приложения — система будет убивать сервис);
* `PowerManager.isIgnoringBatteryOptimizations` → **✅ Работа без ограничений**;
* иначе → **⚠️ Android может ограничивать фоновую работу**.

Кнопка «Разрешить работу без ограничений» пробует по очереди
`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (разрешение
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` объявлено), `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`,
страницу приложения. Статус перечитывается при каждом возвращении на экран
(`ON_RESUME`). Везде в UI явно написано: это снижает риск остановки, но **не
гарантирует** съёмку — решение всегда за системой и производителем.

---

## 11. UI

* Jetpack Compose + Material 3 **1.4.0 (stable)**. Expressive-API в 1.4 были
  удалены перед релизом и появятся стабильно только в 1.5; приложение не
  использует экспериментальные Expressive-компоненты. Переход на
  `MaterialExpressiveTheme` / `MotionScheme` сведётся к замене `MaterialTheme`
  в `presentation/theme/Theme.kt`, когда 1.5.0 станет stable.
* Тема **только тёмная** (`darkColorScheme`, золотой акцент на почти чёрном
  фоне, системные бары прозрачные, edge-to-edge). Светлой темы и переключателя
  нет намеренно; XML-тема окна тоже тёмная, чтобы первый кадр не мигал.
* Экраны: Онбординг → Главная · Фотографии · История · Настройки (нижняя
  навигация) · полноэкранный просмотр (`photo/{eventId}`, открывается и из
  уведомления через `MainActivity.ACTION_OPEN_PHOTO`).
* Состояния: загрузка, пусто, нет разрешения, ограничение фона, пауза,
  недоступная камера, файл удалён — у каждого свой текст; сырых исключений в UI нет.
* ViewModel-и получают `AppGraph` через `CompositionLocal` (`LocalAppGraph`) и
  фабрику `viewModel { … }`; данные — `StateFlow`/`combine` поверх DataStore, Room и
  `SystemStatusMonitor`.

---

## 12. Тесты и сборка

* `app/src/test/.../CaptureSchedulerTest.kt` — 23 unit-теста планировщика: новый
  день, смена настроек, пропуски без догоняющего залпа, лимит, оба режима,
  пауза/возобновление, смена пояса, зависшая попытка, зазоры между моментами.
  Запуск: `./gradlew :app:testDebugUnitTest`.
* Сборка: AGP 9.3.3 (встроенный Kotlin — плагин `org.jetbrains.kotlin.android`
  не применяется), Gradle 9.7.1, Kotlin 2.4.20, KSP 2.3.12, compileSdk 37,
  targetSdk 36, minSdk 29, Java 17. Release: R8 full mode + shrinkResources;
  правила — `app/proguard-rules.pro` (библиотечные правила приходят из AAR).
* Room-схема экспортируется в `app/schemas/` (версия 1).
