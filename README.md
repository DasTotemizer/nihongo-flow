# Nihongo Flow

*Japanese learning, at your own pace.*

An Android-first Japanese spaced repetition app: FSRS scheduling, a workload manager that
makes missed days painless, Holiday Mode, Anki import, the Kaishi 1.5k starter deck and an
optional WaniKani connection. Personal, non-commercial project.

Nihongo Flow is an unofficial, independent app. It is not made, endorsed or operated by
WaniKani, Tofugu, Anki or the Kaishi authors.

## Status

Built in stages. Stage 1 was the engine; stage 2 (this one) is the usable core app:

| Area | State |
| --- | --- |
| Gradle multi-module project, CI | Done |
| FSRS memory scheduler (java-fsrs, FSRS-6) | Done, verified against reference cases |
| Workload manager and missed-day recovery | Done, simulated over 1/3/7/14/30-day breaks |
| Holiday Mode rules (full, casual, indefinite pause) | Done |
| On-device database (Room) | Done, persistence tested |
| Decks, card creation and editing, cloze | Done |
| Review screen, Home, Holiday Mode, Settings, Progress | Done |
| Anki import | Next stage |

See [docs/ROADMAP.md](docs/ROADMAP.md) for every phase and what remains.

## Building

Requirements: JDK 17 or newer. The Android app also needs the Android SDK (API 35).

```
./gradlew test                 # core engine tests, runs anywhere with a JDK
./gradlew :app:assembleDebug   # Android debug APK, needs ANDROID_HOME or local.properties
```

Without an Android SDK the build includes only the pure-Kotlin core modules. CI (GitHub
Actions) builds both and uploads the debug APK as an artifact.

## Documentation

- [Architecture](docs/ARCHITECTURE.md): modules, boundaries, the path to iOS.
- [Scheduling](docs/SCHEDULING.md): FSRS, the workload manager and missed-day recovery.
- [Kaishi 1.5k](docs/KAISHI.md): licence findings and the install approach.
- [Roadmap](docs/ROADMAP.md): phases and status.
