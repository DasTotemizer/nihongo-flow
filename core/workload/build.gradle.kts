// Workload manager: decides what is presented in a session. Never changes memory state.
dependencies {
    "api"(project(":core:model"))
    "api"(project(":core:holiday"))
    "testImplementation"(project(":core:scheduler"))
}
