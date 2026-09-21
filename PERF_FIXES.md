# Исправления плавности скролла (сентябрь 2026)

Цель: плавный скролл на всех экранах как в Spotify. Ниже — найденные причины лагов
и что именно изменено. Проверь diff и закоммить.

## Причины (по убыванию вреда)

### 1. «Пауза загрузок обложек при скролле» — фриз на КАЖДОМ жесте (все экраны)
`ProvidePauseImageLoadsDuringScroll` + `LocalPauseImageLoads` меняли CompositionLocal
в момент начала/конца скролла. Это инвалидировало ВЕСЬ список: все видимые карточки
пересобирались, а `CoverArtImage` пересоздавал `ImageRequest` для каждой обложки разом.
То есть каждое касание скролла = полная перекомпозиция окна. Это главный источник
дерганого скролла на Home / Album / Artist / Favorites / Playlist.

### 2. Позиция плеера на 20 Гц в КОРНЕ FullPlayerScreen — «весь телефон лагает»
`fullPlayerPositionFlow` тикал каждые 50 мс и собирался в корне экрана: весь плеер
(градиенты, обложка, очередь, тексты) пересобирался 20 раз/сек при воспроизведении.
Постоянная нагрузка → нагрев → системный троттлинг (телефон тормозит целиком).
Плюс `playerState` обновлялся каждые 500 мс (теперь 1 с).

### 3. FullscreenLyrics: полноэкранный `blur(20.dp)` + software-битмап
Один из самых дорогих эффектов на Android (полный проход RenderEffect по экрану;
на API < 31 вообще software-реализация), ещё и обновлялся на 20 Гц.

### 4. Обложки FullPlayer — software RGB_565 (`allowHardware(false)`)
Software-битмап заливается в GL-текстуру на каждом кадре. Старый комментарий
«чтобы избежать GPU upload» был ошибочным — ровно наоборот.

### 5. Мелочи
- Search: `items(...chunked(2))` без `key` — позиционная инвалидация всего списка.
- Home/Library: `derivedStateOf` там, где достаточно `remember(keys)`; вычисляемые
  геттеры UiState фильтровали весь список на каждый рекомпоз.
- `PerformanceTracer` писал в logcat КАЖДЫЙ main-спан (в debug-сборке logcat сам по
  себе ест fps).
- Модели/UiState без `@Immutable`.

## Что изменено

| Файл | Изменение |
|---|---|
| `ui/components/SpotifyComponents.kt` | Удалён механизм паузы через CompositionLocal. `CoverArtImage` создаёт `ImageRequest` один раз на (url, size). Обёртки `ProvidePauseImageLoadsDuringScroll` оставлены как прозрачные (API не сломан). Ограничение параллелизма декода несут пулы в `SonicSpotApp` (3 декодера / 4 фетчера). |
| `ui/screens/player/FullPlayerScreen.kt` | Позиция (10 Гц) изолирована: собирают только `IsolatedProgressSlider` и маленькие обёртки (`SyncedLyricsPreview`, `FullscreenSyncedLyrics`, `FullscreenBottomControls`); контент текстов — отдельные skippable-композиции, пересборка только при смене строки. Обложки переведены на HARDWARE. Фон текста: вместо `blur(20.dp)` — декод 64px с билинейным апскейлом (визуально тот же blur, стоит ноль). |
| `player/PlayerManager.kt` | Поллер сторожа зависаний остался 50 мс, но UI-потоки прогресса публикуются максимум 10 раз/сек, `playerState` — раз в секунду. |
| `debug/PerformanceTracer.kt` | Логируются только медленные спаны (>16 мс). |
| `ui/screens/search/SearchScreen.kt` | Ряды по 2 мемоизированы (`remember`), `items` с `key`/`contentType`. |
| `ui/screens/home/HomeScreen.kt` | Фильтры/`take` через `remember(keys)`, без `derivedStateOf` в строках. |
| `ui/screens/library/LibraryScreen.kt` | Фильтры плейлистов мемоизированы, `key` для табов. |
| `ui/screens/recently/RecentlyAddedScreen.kt` | `key`/`contentType` для шиммер-сетки. |
| `data/model/Models.kt` | `@Immutable` для `AlbumDetail`, `PlaylistDetail`. |
| `ui/screens/**/\*ViewModel.kt` | `@Immutable` для всех UiState. |

## Как собирать/проверять

1. **Сравнивай с Spotify только release-сборку.** Debug-сборка Compose-приложения
   принципиально медленнее (нет R8, диагностика, logcat) — на ней «как Spotify» не
   бывает ни у кого. Android Studio: **Build > Generate Signed Bundle/APK** или
   `./gradlew assembleRelease`.
2. Проверка: быстрый флинг на каждом экране (Home, Медиатека, Альбом, Артист,
   Избранное, Поиск, Недавно добавленные) — должен быть ровный, без подвисаний
   в момент старта/остановки жеста.
3. Открытый полноэкранный плеер с играющим треком не должен греть телефон.

## Если после этого где-то остался джанк

Профилировать конкретный экран: Android Studio Profiler → Compose recomposition
counts (Layout Inspector). Подозревать в первую очередь: `Modifier.shadow/blur`,
крупные градиенты поверх картинок (overdraw), списки без `key`.
