// Study logic: decks and cards, today's plan, review sessions and statistics.
// Ties the scheduler, the workload manager and Holiday Mode to a storage port
// (StudyStore). Plain Kotlin, so it is tested here and reusable by any client.
dependencies {
    "api"(project(":core:model"))
    "api"(project(":core:scheduler"))
    "api"(project(":core:holiday"))
    "api"(project(":core:workload"))
    "api"(libs.kotlinx.coroutines.core)
}

// The store contract lives in test fixtures so the Room store in :data runs the same checks.
apply(plugin = "java-test-fixtures")
dependencies {
    "testFixturesApi"(libs.kotlin.test.core)
    "testFixturesApi"(libs.kotlinx.coroutines.core)
}
