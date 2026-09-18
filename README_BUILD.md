# Spotidrome — Сборка подписанного APK через Android Studio

Этот проект уже содержит **все нужные библиотеки** (через Gradle/Maven). Ничего дополнительно качать не нужно — Android Studio сама скачает зависимости при первом Sync.

### Зависимости (уже подключены в app/build.gradle.kts)
- **Compose BOM 2024.10.01** + Material3, Material Icons Extended, Animation
- **Media3 ExoPlayer 1.2.1** (exoplayer, session, ui, common)
- **Hilt 2.50** + hilt-navigation-compose
- **Retrofit 2.9.0** + OkHttp 4.12.0 + kotlinx-serialization-json 1.6.2
- **Coil 2.5.0** (compose + svg)
- **DataStore Preferences 1.0.0**, Coroutines Android 1.7.3
- **16KB page size support**: `graphics-path:1.0.1`, `useLegacyPackaging=false` (обязательно для Android 15+ с ноября 2025)

---

## Вариант 1 — Самый простой (рекомендуется): через UI Android Studio

1. **Открой проект** в Android Studio (File → Open → выбери папку `SonicSpot` где `settings.gradle.kts`)
2. Дождись **Gradle Sync** (внизу справа). Если попросит обновить SDK — согласись, поставь **Platform 35** и **Build-Tools 35.0.0**
3. Меню: **Build → Generate Signed Bundle / APK...**
4. Выбери **APK** → Next
5. **Create new...** keystore:
   - Key store path: `D:\Projects\Spotidrom mobile\navidroid\release.keystore` (или где хочешь)
   - Passwords: придумай (например `sonicspot123`)
   - Alias: `sonicspot`
   - Validity: 25 лет
   - First/Last Name: SonicSpot
6. Выбери **release**, включи **V1 и V2** (V3/V4 опционально, в коде уже включены)
7. Finish → APK будет в `app/build/outputs/apk/release/app-release.apk` — **уже подписанный, готов к установке**

> Этот способ НЕ требует правки `build.gradle.kts` — Android Studio подпишет сама.

---

## Вариант 2 — Через keystore.properties (для командной строки)

1. Создай keystore один раз:
```bash
keytool -genkeypair -v -keystore release.keystore -alias sonicspot -keyalg RSA -keysize 2048 -validity 10000 -storepass sonicspot123 -keypass sonicspot123 -dname "CN=SonicSpot, OU=Mobile, O=SonicSpot, L=Helsinki, ST=Uusimaa, C=FI"
```
Помести `release.keystore` в корень проекта (рядом с `settings.gradle.kts`)

2. Скопируй `keystore.properties.example` → `keystore.properties` в корне:
```
storeFile=release.keystore
storePassword=sonicspot123
keyAlias=sonicspot
keyPassword=sonicspot123
```

3. Собери:
```bash
./gradlew assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk` — подписанный.

Если `keystore.properties` нет — `assembleRelease` соберет **unsigned**, но Android Studio всё равно сможет подписать через Вариант 1.

---

## Вариант 3 — Debug APK (быстро проверить)

В Android Studio: **Run → Run 'app'** — поставит debug версию с суффиксом `.debug` ( `com.sonicspot.player.debug` ), подпись debug-ключом автоматически.

Или:
```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

---

## Требования к окружению

- **JDK 17** (обязательно, AGP 8.5.2 + Kotlin 1.9.22). В Android Studio: File → Settings → Build Tools → Gradle → Gradle JDK → 17
- **Android SDK 35**, Build-Tools 35.0.0
- **Min SDK 26**, Target 35

---

## Что уже исправлено в коде (для производительности)

- **Очередь загрузки**: `PaginatedLazyRow` — только 3 элемента сразу в "Общие плейлисты", догрузка по скроллу (initialVisible=3, pageSize=3-4, delay 80ms)
- **Обложки**: все `getCoverUrl(id, size)` теперь с явным размером:
  - SongRow 44dp → 88px (было 300px → 3.4x waste)
  - AlbumCard 152dp → 304px, ArtistCard 120dp → 240px, Grid 168dp → 336px
  - BottomSheets 56dp → 112px, RemoveLike 88dp → 176px
  - FullPlayer large 0.85f → 600px RGB_565 no hardware, blur 100px, artist avatar 128px, topSongs 80px
  - PlayerManager notification 200px (было 500px), MusicRepository default 300px (было 500px)
  - `CoverArtImage` с `sizePx`, `cacheKey="$url-$sizePx"`, `RGB_565`, `allowHardware=false`

Сборка в контейнере **не нужна** — всё делается у тебя в Android Studio.
