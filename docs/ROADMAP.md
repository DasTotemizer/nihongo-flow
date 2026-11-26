# Roadmap

Phases follow the product spec (section 23). Each stage is a pull request, reviewed before
the next starts.

## Stage 1: foundation and no-guilt engine (this PR)

- [x] Multi-module Gradle project, version catalog, CI with debug APK artifact
- [x] FSRS scheduler via java-fsrs, verified against reference cases
- [x] Parameter safeguards (bounds, minimum real history)
- [x] Workload manager: session limits, prioritisation, fairness, deferral accounting
- [x] Missed-day recovery with ease-in ramp and adaptive new cards
- [x] Holiday Mode rules: full, casual, indefinite pause, end and return
- [x] Android shell: theme (light and dark), five-tab navigation, Home driven by the engine
- [x] Guilt-free copy check

## Phase 1: core app (next)

Room database (schema from spec section 21, with migration tests), repositories, Hilt,
deck management, card creation, review screen (reveal, four ratings with next-interval
preview, swipe and keyboard), Home and Holiday screens on real data, basic statistics.

## Then

- Phase 2 UI: recovery and holiday screens, session-size control, workload settings.
- Phase 3: Anki import (.apkg, .colpkg, .csv, .txt, media), preview, duplicates,
  fresh-start and preserve-progress modes.
- Phase 4: Deck Library and Kaishi guided install.
- Phase 5: WaniKani (API v2, token in Android Keystore, sync, dashboard, duplicates).
- Phase 6: Japanese features (JLPT, kanji, grammar, audio/TTS, cloze, AI examples).
- Phase 7: notifications, backup/restore, accessibility, performance, integrity checks.

## Open decisions

- FSRS parameter optimisation: fsrs-rs through JNI on the device, or an optional server.
- AI examples: which provider, and whether through a small personal backend or a
  user-supplied key (never embedded in the app).
- Cloud sync: whether to build an optional backend at all, or start with file-based
  backup and restore only.
