# Anki import

Nihongo Flow imports the learner's own Anki files. Nothing is downloaded or redistributed.

## Formats

| File | Inside | Read as |
| --- | --- | --- |
| `.apkg` (Anki 2.1.50+) | `collection.anki21b`, zstd-compressed, schema 18; protobuf media list; zstd media | yes |
| `.apkg` (legacy compatible) | `collection.anki21`, schema 11, JSON note types; JSON media list | yes |
| `.apkg` (Anki 2.0) | `collection.anki2` | yes |
| `.colpkg` | a whole collection in any of the above | yes |
| `.csv`, `.txt` | delimited text, including Anki's export headers (`#separator:`, `#html:`, `#columns:`, `#tags column:`) | yes |

Current Anki declares its own `unicase` collation, which Android's SQLite doesn't have and
won't read past. The importer works on a private copy of the collection and swaps that
declaration for the built-in `NOCASE` of the same length, so the file stays valid. It only
ever scans whole tables, so the collation order never matters. The copy is opened read-only.

The test packages in `core/anki/src/test/resources/anki` are made by the real Anki library
(`tools/anki-fixtures/make_fixtures.py`) and are read both on the JVM and through Android's
SQLite under Robolectric.

## Mapping

Each Anki note type becomes one Nihongo Flow kind (vocabulary, kanji, grammar, Q&A, both
directions, cloze), guessed from field names and editable in the preview, field by field.
HTML is reduced to plain text, `<img>` becomes `[image:…]`, `[sound:…]` is kept, and media
files are copied into the app. Anki furigana (`食[た]べる`) gives the kana reading and the
written form. Cards Anki had are the cards made here (a recognition-only deck stays
recognition-only), and each Anki card template maps to the matching Nihongo Flow card so
its history follows it.

## Progress

- **Start fresh**: notes and cards only; every card is new.
- **Keep my progress**: every real answer in Anki's review log is replayed, oldest first,
  through the app's own FSRS scheduler, and stored as review history labelled
  `ANKI_IMPORT` with the state before and after. The card's memory is the result of that
  replay. Nothing is estimated from Anki's intervals or ease, and no review is invented.
  Anki's manual "Forget" resets the card to new. A card Anki had scheduled but whose
  history is missing from the file starts as new, and the import says how many.

Suspended cards stay suspended. A large backlog after import is handled by the workload
manager like any other backlog.

## Duplicates and re-import

Each imported note keeps its Anki guid. Importing the same deck again updates those notes in
place, keeps their cards and history, and adds no history twice. Notes that match something
already in the collection (same word and reading, same kanji, same front) are skipped unless
the learner chooses to import them anyway.
