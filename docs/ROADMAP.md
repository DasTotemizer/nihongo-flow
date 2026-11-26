# Roadmap

Phases follow the product spec (section 23). Each stage is a pull request, reviewed before
the next starts.

## Stage 1: foundation and no-guilt engine (done)

- [x] Multi-module Gradle project, version catalog, CI with debug APK artifact
- [x] FSRS scheduler via java-fsrs, verified against reference cases
- [x] Parameter safeguards (bounds, minimum real history)
- [x] Workload manager: session limits, prioritisation, fairness, deferral accounting
- [x] Missed-day recovery with ease-in ramp and adaptive new cards
- [x] Holiday Mode rules: full, casual, indefinite pause, end and return
- [x] Android shell: theme (light and dark), five-tab navigation, Home driven by the engine
- [x] Guilt-free copy check

## Phase 1: core app (this PR)

- [x] `core:study`: StudyStore port, StudyService (today's plan, sessions, answers, decks,
      cards, holidays, stats), ReviewSession (learning steps return within a sitting)
- [x] Store contract tests shared by the in-memory and Room stores
- [x] `data`: Room schema v1 (exported to `data/schemas`), memory state, history and
      bookkeeping in separate tables, Robolectric tests on real SQLite
- [x] Deck management and card creation (front, reading, back, notes; pause; delete)
- [x] Review screen: reveal, four ratings with next-interval preview, swipe (right Good,
      left Again), keyboard (space, 1 to 4), "Later" to skip without answering
- [x] Home on real data with session size (smaller, suggested, larger) and a starter deck
- [x] Holiday Mode screen: full, casual, indefinite pause; schedule, end now, cancel
- [x] Basic statistics: today, last 7 days, collection, 7-day forecast, recall rate
- [x] Simulated-phone walkthrough test with screenshots

Deviations from the original plan: plain constructor wiring instead of Hilt (the graph is
four objects; Hilt can come in when it pays for itself), and the schema is this app's own
design, since the spec's section 21 was not available in the repository. Migration tests
start with schema version 2, the first version that has something to migrate.

## Then

- Phase 2 UI: recovery screen, workload settings (session length, new cards per day,
  pacing, study days, day start), desired retention, undo last answer.
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
