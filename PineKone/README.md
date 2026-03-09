# PineKone Android MVP

Starter Android project for the PineKone relay MVP. It is intentionally minimal but already wired with the screens and dependencies described in the design brief.

## Requirements

- Android SDK 34+
- JDK 17
- Gradle (or run `gradle wrapper` once you have Gradle installed to generate `./gradlew`)

Set the recommended environment variables:

```bash
export ANDROID_SDK_ROOT=$HOME/Android/Sdk
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
```

## Project layout

- `app/src/main/java/com/pinekone/app` — Kotlin activities, fragments, and adapters.
- `app/src/main/res` — XML resources (layouts, themes, icons, strings).
- `app/build.gradle` — module configuration with dependencies for Kotlin serialization, ZXing, and LazySodium.

## Useful Gradle tasks

```bash
./gradlew assembleDebug
./gradlew installDebug
```

Add the provided VS Code `tasks.json` if you want one-click build and install commands.

## Python sandbox

An isolated Python virtual environment was created in `.venv/`. Activate it before running helper scripts:

```bash
source .venv/bin/activate
```

Package installation requires network access. When available, install the recommended tooling:

```bash
pip install qrcode[pil] cbor2 msgpack
```
