package app.nihongoflow.core.study

/**
 * A tiny starter deck of everyday words, written for Nihongo Flow, so a new learner can try
 * the app straight away. It is not Kaishi and not taken from any other deck.
 */
object SampleDeck {
    const val NAME = "Starter words"
    const val DESCRIPTION = "24 everyday words to try Nihongo Flow. Delete it any time."

    val cards: List<CardContent> = listOf(
        CardContent("こんにちは", "hello; good afternoon"),
        CardContent("ありがとう", "thank you"),
        CardContent("はい", "yes"),
        CardContent("いいえ", "no"),
        CardContent("水", "water", reading = "みず"),
        CardContent("食べる", "to eat", reading = "たべる"),
        CardContent("飲む", "to drink", reading = "のむ"),
        CardContent("行く", "to go", reading = "いく"),
        CardContent("見る", "to see; to watch", reading = "みる"),
        CardContent("大きい", "big", reading = "おおきい"),
        CardContent("小さい", "small", reading = "ちいさい"),
        CardContent("今日", "today", reading = "きょう"),
        CardContent("明日", "tomorrow", reading = "あした"),
        CardContent("人", "person", reading = "ひと"),
        CardContent("日本", "Japan", reading = "にほん"),
        CardContent("日本語", "the Japanese language", reading = "にほんご"),
        CardContent("先生", "teacher", reading = "せんせい"),
        CardContent("学生", "student", reading = "がくせい"),
        CardContent("友達", "friend", reading = "ともだち"),
        CardContent("本", "book", reading = "ほん"),
        CardContent("猫", "cat", reading = "ねこ"),
        CardContent("犬", "dog", reading = "いぬ"),
        CardContent("好き", "liked; to like", reading = "すき", notes = "猫が好きです。 I like cats."),
        CardContent("時間", "time; hours", reading = "じかん"),
    )
}
