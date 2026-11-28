"""Builds the Anki test packages used by core:anki tests, with the real Anki library.

    pip install anki==26.9.4   # any recent version
    python3 tools/anki-fixtures/make_fixtures.py core/anki/src/test/resources/anki

Every package is made from the same small collection, so tests can compare formats:

- legacy.apkg   old format (collection.anki2 / anki21, JSON media list), with scheduling
- modern.apkg   current format (zstd collection.anki21b, protobuf media list), with scheduling
- shared.apkg   current format without scheduling, like a deck shared online
- backup.colpkg a whole-collection backup in the current format

All card text here was written for these tests.
"""
import os
import shutil
import sys
import tempfile
import time

from anki.collection import Collection, ExportAnkiPackageOptions

out = sys.argv[1]
os.makedirs(out, exist_ok=True)
work = tempfile.mkdtemp()
col = Collection(os.path.join(work, "collection.anki2"))
mm = col.models

# A vocabulary note type like many Japanese decks use, with one recognition template.
vt = mm.new("Japanese Vocab")
for f in ["Word", "Word Reading", "Word Meaning", "Word Audio", "Sentence", "Sentence Meaning", "Notes"]:
    mm.add_field(vt, mm.new_field(f))
t = mm.new_template("Recognition")
t["qfmt"] = "<div class=jp>{{Word}}</div>"
t["afmt"] = "{{FrontSide}}<hr id=answer>{{furigana:Word Reading}}<br>{{Word Meaning}}{{Word Audio}}"
mm.add_template(vt, t)
mm.add(vt)
vt = mm.by_name("Japanese Vocab")

vocab_deck = col.decks.id("Japanese::Vocab")
misc_deck = col.decks.id("Misc")


def add(model, deck, fields, tags=""):
    n = col.new_note(model)
    for k, v in fields.items():
        n[k] = v
    n.tags = tags.split()
    col.add_note(n, deck)
    return n


audio = os.path.join(work, "taberu.mp3")
with open(audio, "wb") as fh:
    fh.write(b"ID3fake-audio")
col.media.add_file(audio)

n1 = add(vt, vocab_deck, {
    "Word": "食べる", "Word Reading": "食[た]べる", "Word Meaning": "to eat",
    "Word Audio": "[sound:taberu.mp3]", "Sentence": "ご<b>飯</b>を食べる。", "Sentence Meaning": "I eat rice.",
    "Notes": "Ichidan verb&nbsp;&amp; common",
}, "verb jlpt::n5")
n2 = add(vt, vocab_deck, {"Word": "飲む", "Word Reading": "飲[の]む", "Word Meaning": "to drink"}, "verb")
n3 = add(vt, vocab_deck, {"Word": "本", "Word Reading": "ほん", "Word Meaning": "book<br>volume"})
add(mm.by_name("Basic"), misc_deck, {"Front": "Capital of Japan?", "Back": "<div>Tokyo</div>"})
n5 = add(mm.by_name("Basic (and reversed card)"), misc_deck, {"Front": "犬", "Back": "dog"})
add(mm.by_name("Cloze"), vocab_deck, {"Text": "私は{{c1::寿司}}を{{c2::食べる::verb}}。", "Back Extra": "I eat sushi."})

day = 86400 * 1000
start = (int(time.time()) // 86400 - 30) * day


def revlog(rid, cid, ease, ivl, last, factor, taken, typ):
    col.db.execute(
        "insert into revlog (id,cid,usn,ease,ivl,lastIvl,factor,time,type) values (?,?,?,?,?,?,?,?,?)",
        rid, cid, -1, ease, ivl, last, factor, taken, typ)


# 食べる: five real reviews over twelve days, including one lapse.
c1 = n1.cards()[0]
for i, (offset, ease, ivl, last, factor, typ) in enumerate([
    (0, 3, -600, 0, 0, 0), (1, 3, 1, -600, 2500, 1), (4, 1, -600, 1, 2500, 1),
    (5, 3, 3, -600, 2300, 2), (12, 4, 15, 3, 2300, 1),
]):
    revlog(start + offset * day + i, c1.id, ease, ivl, last, factor, 4000 + i, typ)
col.db.execute("update cards set type=2, queue=2, ivl=15, due=?, reps=5, lapses=1 where id=?", col.sched.today + 1, c1.id)

# 飲む: one review, then reset with Anki's "Forget", so it should start again as new.
c2 = n2.cards()[0]
revlog(start + 7, c2.id, 3, 1, 0, 2500, 3000, 0)
revlog(start + 2 * day + 7, c2.id, 0, 0, 1, 0, 0, 4)

# 本: Anki has it in review, but its history is missing.
c3 = n3.cards()[0]
col.db.execute("update cards set type=2, queue=2, ivl=10, due=? where id=?", col.sched.today + 3, c3.id)

# The reversed card of 犬 is suspended.
col.db.execute("update cards set queue=-1 where id=?", n5.cards()[1].id)


def opts(legacy, scheduling=True):
    return ExportAnkiPackageOptions(
        with_scheduling=scheduling, with_deck_configs=False, with_media=True, legacy=legacy)


col.export_anki_package(out_path=os.path.join(out, "legacy.apkg"), options=opts(True), limit=None)
col.export_anki_package(out_path=os.path.join(out, "modern.apkg"), options=opts(False), limit=None)
col.export_anki_package(out_path=os.path.join(out, "shared.apkg"), options=opts(False, scheduling=False), limit=None)
col.export_collection_package(os.path.join(out, "backup.colpkg"), include_media=True, legacy=False)
col.close()
shutil.rmtree(work)
