<img src="docs/brand/logo.jpg" width="160" alt="BK-PHENIX">

# BK-PHENIX

[![CI](https://github.com/BobbyUDLab/BK-PHENIX/actions/workflows/ci.yml/badge.svg)](https://github.com/BobbyUDLab/BK-PHENIX/actions/workflows/ci.yml)

Android-приложение для надёжного определения координат при глушении и подмене ГНСС.
Все источники (ГНСС, сотовые вышки, Wi-Fi, BLE, датчики, рельеф) сверяются между собой,
ложные данные отсекаются, а погрешность показывается явно. Работает без Google Play Services.

Подробнее: [концепция](concept.md), [название и логотип](docs/brand/README.md).

## Структура

| Модуль | Что внутри |
| --- | --- |
| `:core` | Чистый Kotlin/JVM: модель наблюдений и источников, компаратор, контроль целостности (в т. ч. чётность GPS LNAV), якорь пользователя, `PhenixCore`. JUnit 5. |
| `:app` | Android, Jetpack Compose + Material3, minSdk 26, пакет `ru.bkphenix`. Экран «Координата». |

## Требования

- JDK 17 (подойдёт `jbr` из Android Studio).
- Android SDK: `platforms;android-35`, `build-tools;35.0.0`. Путь берётся из `ANDROID_HOME` или `local.properties`.

## Сборка

```bash
./gradlew test             # юнит-тесты
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
```

На Windows: `.\gradlew.bat test assembleDebug`.

CI собирает debug APK на каждый PR и пуш в `main`; APK лежит в артефакте `bk-phenix-debug-apk`.

## Правила разработки

- Ветки `feature/*`, `fix/*`; изменения в `main` только через PR с зелёным CI.
- Коммиты в стиле [Conventional Commits](https://www.conventionalcommits.org/ru/) на русском: `feat(core): …`, `fix(app): …`, `docs: …`.
