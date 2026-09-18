# Git — что коммитить, что нет

## Добавлены файлы

- `.gitignore` — полный игнор для Android/Kotlin проекта
- `.gitattributes` — нормализация окончаний строк LF, бинарники помечены как binary
- `keystore.properties.example` — шаблон для подписи (его КОММИТИМ)

## Что НЕ коммитить (уже в .gitignore)

### 🔴 Секреты - критично!
```
*.jks
*.keystore
keystore.properties          # твои реальные пароли!
release.keystore
*.p12
*.key
```
**Почему:** если запушишь keystore + пароли — любой сможет подписывать APK от твоего имени в Google Play.

### 🟡 Локальное окружение
```
local.properties            # путь к Android SDK у каждого свой
.gradle/                    # кэш Gradle, тяжелый, у каждого свой
build/                      # сгенерированные классы, пересоздается
app/build/
.gradle/
```
**Почему:** у каждого разработчика свой путь `sdk.dir`, кэш занимает гигабайты.

### 🟡 Сборки
```
*.apk
*.aab
*.ap_
*.dex
*.class
app/release/
app/debug/
```
**Почему:** APK собирается из кода, нет смысла хранить бинарники в гите. Исключение — если хочешь хранить релизы в GitHub Releases (не в репозитории).

### 🟡 IDE
```
.idea/
*.iml
.vscode/
```
**Почему:** настройки IDE у каждого свои, конфликтуют.

### 🟢 Что КОММИТИТЬ обязательно
```
gradle/wrapper/gradle-wrapper.properties  # версия Gradle
gradlew, gradlew.bat                      # скрипты сборки
build.gradle.kts                          # зависимости
settings.gradle.kts
app/src/                                  # весь код
keystore.properties.example               # шаблон, без паролей
README_BUILD.md, README_GIT.md
proguard-rules.pro
```

## Первый коммит

```bash
git init
git add .
git status # проверь что нет keystore.properties и *.jks
git commit -m "Initial: Spotidrom mobile Kotlin 2.2 + Media3 1.7 fix freeze"
git branch -M main
git remote add origin https://github.com/твой_юзер/navidroid.git
git push -u origin main
```

## Проверка перед пушем

```bash
# Убедись что секреты не попали:
git ls-files | grep -E "keystore|\.jks|\.keystore|keystore.properties$"
# Должно показать только keystore.properties.example, НЕ keystore.properties

# Посмотри что будет закоммичено:
git status
```

## Если случайно закоммитил секреты

```bash
# Удалить из истории (осторожно!):
git rm --cached keystore.properties release.keystore
git commit -m "Remove secrets"
# + смени пароли и перевыпусти keystore, т.к. в истории они остались
# Для полной очистки истории:
git filter-branch --force --index-filter "git rm --cached --ignore-unmatch keystore.properties release.keystore" --prune-empty --tag-name-filter cat -- --all
```

## Подпись APK и git

- `keystore.properties.example` — в репозитории, с фейковыми данными
- `keystore.properties` — локально у каждого, в .gitignore
- `release.keystore` — локально, в .gitignore, бэкапь отдельно в 1Password/Bitwarden

Сборка:
```bash
cp keystore.properties.example keystore.properties
# отредактируй keystore.properties своими паролями
./gradlew assembleRelease # подпишет если keystore.properties есть
```

## .gitattributes зачем?

- Нормализует окончания строк в LF (важно для `gradlew` на Linux/Mac)
- Помечает картинки/keystore как binary чтобы git не пытался делать diff
- `gradlew` остается executable
