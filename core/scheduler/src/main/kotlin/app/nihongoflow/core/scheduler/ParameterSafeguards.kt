package app.nihongoflow.core.scheduler

/**
 * Checks applied before any custom FSRS parameters are accepted, for example parameters
 * produced by a future optimiser run or carried over from an Anki import.
 *
 * The bounds are copied from the reference implementation py-fsrs
 * (`LOWER_BOUNDS_PARAMETERS` / `UPPER_BOUNDS_PARAMETERS` in fsrs/scheduler.py), and the
 * minimum history size matches its optimiser, which keeps the default parameters when
 * fewer than 512 reviews are available.
 */
object ParameterSafeguards {

    const val PARAMETER_COUNT = 21

    /** Fewer real reviews than this and optimisation keeps the default parameters. */
    const val MIN_REVIEWS_FOR_OPTIMISATION = 512

    val LOWER_BOUNDS: List<Double> = listOf(
        0.001, 0.001, 0.001, 0.001, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001,
        0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
    )

    val UPPER_BOUNDS: List<Double> = listOf(
        100.0, 100.0, 100.0, 100.0, 10.0, 4.0, 4.0, 0.75, 4.5, 0.8, 3.5,
        5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
    )

    /** Every problem with [parameters]; empty when they are safe to use. */
    fun problems(parameters: List<Double>): List<String> {
        if (parameters.size != PARAMETER_COUNT) {
            return listOf("Expected $PARAMETER_COUNT parameters, got ${parameters.size}.")
        }
        return parameters.mapIndexedNotNull { i, p ->
            when {
                !p.isFinite() -> "parameters[$i] = $p is not a finite number"
                p < LOWER_BOUNDS[i] || p > UPPER_BOUNDS[i] ->
                    "parameters[$i] = $p is out of bounds (${LOWER_BOUNDS[i]}, ${UPPER_BOUNDS[i]})"
                else -> null
            }
        }
    }

    fun requireValid(parameters: List<Double>) {
        val problems = problems(parameters)
        require(problems.isEmpty()) { "Invalid FSRS parameters:\n" + problems.joinToString("\n") }
    }

    /**
     * Whether enough real review history exists to run the optimiser. Only reviews the
     * learner actually did count; nothing is padded or synthesised.
     */
    fun hasEnoughHistoryForOptimisation(realReviewCount: Int): Boolean =
        realReviewCount >= MIN_REVIEWS_FOR_OPTIMISATION
}
