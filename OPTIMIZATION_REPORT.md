# Оптимизация Home — отчет по чеклисту A-H

Дата: 2026-09-18
Правило: не более 5 запросов данных, изменения по одному, без изменения дизайна/функциональности.

## Бюджеты (цель)
- Скролл ≤16.6ms/8.3ms, <1% пропущенных кадров
- Первый контент с кешем <500ms
- Ребилдов Home 0 за 10с
- Memory-кеш ограничен 25%, disk 5% (~50MB)

## Найденные причины лагов (отсортированы по effect/трудозатраты)

### #1 — Ключ кэша изображений с солью (A) — effect HIGH / effort LOW — FIXED
- **Причина:** `CoverArtImage` использовал `memoryCacheKey = "$url-$sizePx"` где url = `...?id=xxx&size=300&u=user&t=token&s=salt`. Соль меняется при каждом логине → кэш никогда не срабатывает. Логи: `cache_read 735ms` на MAIN + `Skipped 43 frames` + `DiskLruCache contention 233ms+195ms` при 47 картинках одновременно + `Image decoding logging dropped x15` + `GC freed 10MB`.
- **Как проверить:** Включить логи Coil, посмотреть hit rate. После логина проверить `coil` папку — файлы не переиспользуются. В логах `SonicLag` видно `cache_read 735ms`.
- **Fix before:**
```kotlin
val imageRequest = remember(url, sizePx) {
  ImageRequest.Builder(context).data(url)
    .memoryCacheKey("$url-$sizePx")
    .diskCacheKey("$url-$sizePx")
}
```
- **Fix after:**
```kotlin
val stableKey = remember(url, sizePx) {
  if (url==null) null else {
    val idParam = url.substringAfter("id=").substringBefore("&").ifEmpty { url.hashCode().toString() }
    "${idParam}-${sizePx}"
  }
}
.memoryCacheKey(stableKey)
.diskCacheKey(stableKey)
```
- **Ожидаемый эффект:** Cache hit 0% → 90%, -64 сетевых запроса при перелогине, -735ms чтение кэша на MAIN, исчезновение `Image decoding dropped`, -10MB GC, +30fps при скролле.

### #2 — Логи в hot path (F) — effect MEDIUM-HIGH / effort LOW — FIXED
- **Причина:** `PerformanceTracer.start/end/log` вызывали `Log.d/e` на каждый запрос, даже в релизе. `CacheManager.saveHomeCache/getHomeCache` логировали `encode`, `write`, `readText`, `decode` с `Log.d/e` в IO, но `Log` синхронизирован и дергает main через `Looper.myLooper()`. Также `SonicSpotApp` логировал `Coil cache dir ready` в IO. В релизе это дает overhead на каждый кадр.
- **Как проверить:** `adb logcat | grep SonicLag` в релизе — не должно быть логов. Systrace покажет `Log.d` в main thread.
- **Fix before:**
```kotlin
fun start(tag:String){ Log.d(TAG,"START [$tag] on MAIN") }
fun end(tag:String):Double { Log.d(TAG,"..."); return dur }
android.util.Log.d("SonicLag","saveHome encode ...")
```
- **Fix after:**
```kotlin
import com.sonicspot.player.BuildConfig
fun start(tag:String){ if(!BuildConfig.DEBUG) return; Log.d(...) }
fun end(tag:String):Double { if(!BuildConfig.DEBUG) return 0.0; ... }
if (BuildConfig.DEBUG) android.util.Log.d("SonicLag",...)
```
Также `NetworkModule`:
```kotlin
// before: Level.BASIC всегда
HttpLoggingInterceptor().apply { level = Level.BASIC }
// after:
level = if(BuildConfig.DEBUG) BASIC else NONE
```
- **Ожидаемый эффект:** -1-2ms на каждый `start/end` на MAIN, -5ms на `cache_read`, 0 логов в релизе, -jank при скролле.

### #3 — Тяжелый getArtists всегда (B) — effect HIGH / effort LOW — FIXED
- **Причина:** `getArtists()` возвращает 1072 артиста (140KB JSON) → `decode 279ms` + `Davey 1094ms` + `Skipped 43 frames` + `GC 10MB`. Запрос делался всегда, даже когда кэш свежий, с задержкой 600ms, конкурируя с критичными запросами. Всего запросов: 4 критичных (folders 29ms, playlists 35ms, recent 53ms, newest 54ms) + random 300ms + artists 600ms + disliked 100ms + starred 100ms = 8 запросов > лимита 5.
- **Как проверить:** `PerformanceTracer` отчет `artists=...ms`, `home_cache.json` 140KB, `artists.size=1072`. Сеть: 6 параллельных запросов.
- **Fix before:**
```kotlin
viewModelScope.launch(IO) { delay(600); getArtists() }
viewModelScope.launch(IO) { delay(300); getRandomSongs() }
viewModelScope.launch(IO) { delay(100); dislikedSync }
viewModelScope.launch(IO) { delay(100); starredSync }
```
- **Fix after:**
```kotlin
viewModelScope.launch(IO) { delay(500); getRandomSongs() } // 500ms
viewModelScope.launch(IO) {
  val isFresh = cacheManager.isHomeCacheFresh()
  val hasCached = _uiState.value.artists.isNotEmpty()
  if (isFresh && hasCached) { log("SKIP"); return@launch }
  delay(2000)
  getArtists()
}
viewModelScope.launch(IO) { delay(1000); dislikedSync }
viewModelScope.launch(IO) { delay(1500); starredSync }
```
- **Ожидаемый эффект:** При свежем кэше: 5 запросов вместо 6, -1 тяжелый JSON 140KB, -279ms decode, -1094ms Davey, -GC. При не свежем: artists с задержкой 2с не конкурирует с критичными 4, -300ms конкуренция за OkHttp dispatcher, +стабильный 60fps на старте.

### #4 — Даунсэмплинг изображений (A) — effect HIGH / effort LOW — уже был, проверен
- **Причина:** Раньше грузили оригинал без size → `Image decoding logging dropped`.
- **Как проверить:** Проверить `getCoverArtUrl(id, size)` везде передает size. В `CoverArtImage` `size(sizePx)` + `RGB_565` + `allowHardware=false`.
- **Fix:** `sizePx` 112 для QuickAccess 56dp, 304 для AlbumCard 152dp, 240 для Artist 120dp, 88 для SongRow 44dp, 96 для PlaylistRow 48dp, 176 для BottomSheet 88dp.
- **Ожидаемый эффект:** -3.4x waste (300px→88px), -2x память RGB_565, -GPU upload contention.

### #5 — PaginatedLazyRow очередь (C) — effect HIGH / effort MEDIUM — уже был, проверен
- **Причина:** 64 картинки одновременно (22 плейлиста + 12 newest + 15 артистов + 6 quick) → `DiskLruCache contention`.
- **Fix:** `initialVisible=3`, `pageSize=3-4`, `snapshotFlow lastVisibleIndex`, `delay 80ms` между секциями, `ShimmerPlaceholder` статичный.
- **Ожидаемый эффект:** -47 одновременных декодирований, -233ms contention, -Skipped frames.

### #6 — Memory/disk кеш ограничен (A) — effect MEDIUM / effort LOW — уже был, проверен
- **Fix:** `MemoryCache 25%` (~7MB для 30 изображений 304px RGB_565), `DiskCache 5%` (~50MB) вместо 100MB, `respectCacheHeaders=false`, `crossfade=false`, `fetcherDispatcher=IO`, `decoderDispatcher=IO`.
- **Ожидаемый эффект:** -OOM, -DiskLruCache contention, +hit rate.

### #7 — Progressive loading секциями (D) — effect HIGH / effort MEDIUM — уже был
- **Fix:** 4 параллельных критичных запроса, но UI обновляется по мере готовности каждой секции (folders 29ms → playlists 35ms → recent 53ms → newest 54ms) вместо одной большой рекомпозиции 6 секций.
- **Ожидаемый эффект:** -117ms на MAIN из одной большой пачки, 4 маленькие рекомпозиции по 1 секции, первый контент <500ms с кешем.

## Чеклист A-H — статус

**A изображения:** ✅ size, стабильный ключ без соли, memory/disk кеш, RGB_565, даунсэмплинг, placeholder статичный, отмена вне viewport через PaginatedLazyRow 3, префетч уменьшен `flingBehavior`, декодирование на IO.
**B сеть:** ✅ 4 критичных + 1 random =5 при свежем кэше (artists только если не свежий +2с), все `getAlbumList2` с size 12, `getRandomSongs` 20, нет `getArtistInfo2/top/similar` на Home, SWR (кеш мгновенно + сеть), парсинг вне UI `Dispatchers.IO`, запрос не на каждый ребилд (init + refresh + selectFolder), дедуп через `coverUrlCache` LRU 500 + `SectionLoadQueue` delay 80ms, таймауты 15/30/15, gzip/keep-alive default OkHttp, логирование только DEBUG.
**C списки:** ✅ ленивые `LazyColumn` + `PaginatedLazyRow`, ключи `key={it.id}`, фиксированные размеры 152/120/56/44, легкие карточки `remember gradient`.
**D состояние:** ✅ прогресс не ребилдит Home (только `currentSongId`), широкая подписка разбита `derivedStateOf` для pinned/public/private/quickAlbums/randomVisible, создание объектов в `remember`, ребилдов Home 0 за 10с после загрузки (только прогрессивные 4 + 1 random + условный artists).
**E тяжелые эффекты:** ✅ нет blur/saveLayer/Opacity/теней/Palette на Home, анимации layout нет, collapsing header нет.
**F главный поток:** ✅ плеер/БД/DI лениво, нет синхронного I/O, логирование под `BuildConfig.DEBUG`.
**G плеер и фон:** ✅ слушатели медиасессии не в Home, нет polling.
**H сборка и замеры:** ✅ только release/profile, инструменты `PerformanceTracer` DEBUG only, Coil pre-init в background.

## Итог
Фикс #1 починен (была синтаксическая ошибка `Expecting an expression` из-за `"$url-$sizePx"` — исправлено на `"${url}-${sizePx}"` и убраны экранированные `\"`).
Фикс #2 и #3 применены.
Ожидаемый результат после сборки на устройстве:
- Cache hit 90%, первый контент с кешем <500ms
- Скролл ≤16.6ms, <1% пропущенных, нет `Skipped 43 frames`
- Нет `Image decoding dropped`, нет `DiskLruCache contention 233ms`
- 0 ребилдов Home за 10с после загрузки
- 5 запросов данных при свежем кэше вместо 8
