// Anki import: reads .apkg and .colpkg packages (old and new formats) and text exports,
// and turns them into Nihongo Flow notes, cards and, if asked, real review history.
dependencies {
    "api"(project(":core:study"))
    "implementation"(libs.kotlinx.serialization.json)
    // The jar carries desktop natives for tests. The Android app swaps in the AAR build.
    "implementation"(libs.zstd.jni)
}

dependencies {
    "testImplementation"(libs.kotlinx.coroutines.test)
    "testImplementation"(libs.sqlite.jdbc)
}
