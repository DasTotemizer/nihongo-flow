# Roadmap

Phases follow the product spec (section 23). Each stage is a pull request, reviewed before
the next starts.

## Stage 1: foundation and no-guilt engine (merged)

- [x] Multi-module Gradle project, version catalog, CI with debug APK artifact
- [x] FSRS scheduler via java-fsrs, verified against reference cases
- [x] Parameter safeguards (bounds, minimum real history)
- [x] Workload manager: session limits, prioritisation, fairness, deferral accounting
- [x] Missed-day recovery with ease-in ramp and adaptive new cards
- [x] Holiday Mode rules: full, casual, indefinite pause, end and return
- [x] Android shell: theme (light and dark), five-tab navigation, Home driven by the engine
- [x] Guilt-free copy check

## Stage 2: Phase 1 core app and Phase 2 screens (this PR)

- [x] `core:study`: decks, notes, card generation, cloze parsing, Japanese text
      normalisation, duplicate detection, review sessions, statistics
- [x] Room database (spec section 21 tables), schema exported, no destructive fallback
- [x] Persistence test: decks, cards, history, settings and holidays survive a restart
- [x] Deck management: create, rename, delete, search
- [x] Card creation and editing for vocabulary, kanji, grammar, Q&A, both-ways and cloze
- [x] Review screen: reveal, four ratings with next-interval preview, swipe, keyboard
- [x] Home on real data: plan, recovery progress, holiday strip, session size choice
- [x] Holiday Mode screen: full, casual, pause; plan ahead, change, end, cancel
- [x] Settings: session length, review cap, new cards, pacing, study days, directions,
      memory target
- [x] Progress: milestones, actual results, FSRS estimates, the full picture for today
- [x] Built-in "First steps" starter deck written for this app
- [ ] Migration tests (there is only schema version 1 so far)
- [ ] Hilt: replaced for now by a small manual `AppContainer`; revisit if wiring grows

## Then

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
