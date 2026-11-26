package app.nihongoflow.core.study

/**
 * A small deck written for Nihongo Flow, so a new install has something to study before
 * Kaishi 1.5k is imported. Original content: no third-party material.
 */
object StarterDeck {

    const val NAME = "First steps"

    val source = ContentSource(
        kind = SourceKind.BUILT_IN,
        name = "Nihongo Flow starter deck",
        author = "Nihongo Flow",
        licence = "Written for Nihongo Flow; free to use and share",
    )

    private data class Word(val word: String, val reading: String, val meaning: String, val sentence: String, val sentenceMeaning: String)

    private val words = listOf(
        Word("水", "みず", "water", "水を一杯ください。", "Could I have a glass of water, please?"),
        Word("食べる", "たべる", "to eat", "朝ご飯を食べましたか。", "Did you have breakfast?"),
        Word("飲む", "のむ", "to drink", "毎朝コーヒーを飲みます。", "I drink coffee every morning."),
        Word("行く", "いく", "to go", "明日、駅に行きます。", "I'm going to the station tomorrow."),
        Word("来る", "くる", "to come", "友達が家に来ました。", "A friend came to my house."),
        Word("見る", "みる", "to see; to watch", "週末に映画を見ました。", "I watched a film at the weekend."),
        Word("今日", "きょう", "today", "今日はとても暑いですね。", "It's very hot today, isn't it?"),
        Word("明日", "あした", "tomorrow", "明日は雨が降るそうです。", "I hear it's going to rain tomorrow."),
        Word("友達", "ともだち", "friend", "友達と公園で話しました。", "I talked with a friend in the park."),
        Word("学校", "がっこう", "school", "学校まで歩いて十分です。", "It's a ten-minute walk to school."),
        Word("先生", "せんせい", "teacher", "先生の説明はわかりやすいです。", "The teacher's explanations are easy to follow."),
        Word("時間", "じかん", "time", "今日は時間がありません。", "I don't have time today."),
        Word("好き", "すき", "liked; favourite", "猫が好きです。", "I like cats."),
        Word("大きい", "おおきい", "big", "あの大きい建物は病院です。", "That big building is a hospital."),
        Word("小さい", "ちいさい", "small", "この靴は少し小さいです。", "These shoes are a little small."),
        Word("電車", "でんしゃ", "train", "電車で会社に行きます。", "I go to work by train."),
        Word("天気", "てんき", "weather", "いい天気ですね。", "Lovely weather, isn't it?"),
        Word("本", "ほん", "book", "図書館で本を借りました。", "I borrowed a book from the library."),
        Word("話す", "はなす", "to speak; to talk", "日本語を少し話せます。", "I can speak a little Japanese."),
        Word("分かる", "わかる", "to understand", "この言葉の意味が分かりません。", "I don't understand what this word means."),
    )

    private val clozes = listOf(
        "私は寿司を{{c1::食べる}}。" to "I eat sushi.",
        "毎日、日本語を{{c1::勉強します}}。" to "I study Japanese every day.",
        "駅{{c1::まで}}歩きます。" to "I walk to the station.",
    )

    /** Installs the deck unless it is already there. Returns the deck. */
    suspend fun install(service: StudyService): Deck {
        service.decks().firstOrNull { it.source == source }?.let { return it }
        val deck = service.createDeck(NAME, "Twenty everyday words and three short cloze sentences to try the app with.", source)
        for (w in words) {
            service.saveNote(
                deckId = deck.id,
                kind = NoteKind.VOCABULARY,
                fields = mapOf(
                    Fields.WORD to w.word,
                    Fields.READING to w.reading,
                    Fields.MEANING to w.meaning,
                    Fields.SENTENCE to w.sentence,
                    Fields.SENTENCE_MEANING to w.sentenceMeaning,
                ),
                tags = setOf("starter"),
                allowDuplicate = true,
                source = source,
            )
        }
        for ((text, meaning) in clozes) {
            service.saveNote(
                deckId = deck.id,
                kind = NoteKind.CLOZE,
                fields = mapOf(Fields.TEXT to text, Fields.EXTRA to meaning),
                tags = setOf("starter"),
                allowDuplicate = true,
                source = source,
            )
        }
        return deck
    }

    val noteCount: Int get() = words.size + clozes.size
}
