// Study orchestration: decks, notes and cards, and the service that joins the memory
// scheduler, the workload manager and Holiday Mode over repository interfaces.
dependencies {
    "api"(project(":core:model"))
    "api"(project(":core:scheduler"))
    "api"(project(":core:holiday"))
    "api"(project(":core:workload"))
}

dependencies {
    "testImplementation"(libs.kotlinx.coroutines.test)
}
