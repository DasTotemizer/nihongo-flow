# Architecture

## Stack

- **Kotlin + Jetpack Compose** for the Android app. No cross-platform UI framework: the
  learning engine is the part worth sharing, and it is plain Kotlin already.
- **Gradle multi-module** build with a version catalog (`gradle/libs.versions.toml`).
- **Core modules are pure Kotlin** (JVM today, no Android imports, `kotlinx-datetime` for
  time) so they can move to Kotlin Multiplatform for an iOS client without rewriting.
- **Room** for storage (`app/.../data`), behind the repository interfaces in `core:study`.
- **Manual dependency wiring** (`AppContainer`) instead of Hilt for now: one database,
  one store, one service. Hilt can come in if the graph grows.
- Planned for the next stages: WorkManager
  for background sync, DataStore for preferences, Android Keystore (via an encrypted
  store) for the WaniKani token.

## Modules

| Module | Responsibility | Depends on |
| --- | --- | --- |
| `core:model` | Domain types: memory state, ratings, review records, study days | kotlinx-datetime |
| `core:scheduler` | **Memory scheduler.** FSRS through the official java-fsrs library | model, java-fsrs |
| `core:holiday` | Holiday Mode and pause rules | model |
| `core:workload` | **Workload manager.** What is presented in a session; missed-day recovery | model, holiday |
| `core:study` | Decks, notes, cards, cloze, Japanese text, sessions, stats; repository interfaces | all of the above |
| `app` | Android UI (Compose), Room database, wiring | all core modules |

Modules still to come, matching the spec's list: `core:anki` (package import), `core:wanikani` (API client and sync),
`core:ai` (optional example generation), and backup and restore (Room stays in `app` for now,
behind the core interfaces). Japanese text handling and cloze parsing live in `core:study` for now.

## The key boundary: scheduler vs workload manager

The spec requires two independent systems, and the module graph enforces it:

- `core:scheduler` owns `MemoryState` changes. Only a real answer (`review(...)`) produces
  a new state. It knows nothing about sessions, backlogs or holidays.
- `core:workload` does **not** depend on `core:scheduler`. It sees memory only through the
  read-only `RetrievabilityEstimator` interface, so it cannot change stability,
  difficulty, due dates or history even by accident. Its output is a `SessionPlan`: which
  cards to show, which are deferred and why, and the true due counts.
- Presentation bookkeeping (`DeferralState`, `RecoveryState`) is stored separately from
  memory state, in separate tables (`fsrs_card_state` vs `workload_deferral` and
  `workload_recovery_state`). Review history keeps the state before and after each answer.

## Decisions

1. **FSRS library: java-fsrs 1.0.0** (open-spaced-repetition, MIT). It is the official
   JVM port of py-fsrs, implements FSRS-6 and is on Maven Central. Our wrapper only
   converts types. It has no optimiser; see [SCHEDULING.md](SCHEDULING.md).
2. **Android SDK in CI, not required locally.** `settings.gradle.kts` includes `:app` only
   when an SDK is present, so the engine builds and tests on any JDK machine.
3. **minSdk 26**, because java-fsrs uses `java.time`.
4. **Time is UTC instants plus a study-day clock** (`StudyDayClock`, default day start
   4 AM local) so late-night sessions count toward the previous day.

## Path to iOS

The core modules use only Kotlin stdlib and kotlinx-datetime, so switching them to
`kotlin("multiplatform")` with JVM and iOS targets is a build change. The one JVM-only
piece is the java-fsrs adapter: on iOS, `MemoryScheduler` would be implemented over
swift-fsrs or fsrs-rs (both maintained by the same project) and verified with the same
reference cases.
