package lingue.core

enum class DiffKind {
    /** The letter is where it should be. */
    MATCH,

    /** A letter was typed in place of a different one. */
    WRONG,

    /** A letter of the expected spelling was left out. */
    MISSING,

    /** A letter was typed that does not belong. */
    EXTRA,
}

data class DiffOp(val kind: DiffKind, val expected: Char? = null, val typed: Char? = null)

/**
 * Letter-by-letter alignment of an answer against the expected spelling.
 *
 * Showing the correct word next to a wrong one leaves the reader to find the
 * difference, which is the very step that fixes spelling — so it is done here
 * instead. The alignment is the backtrace of the same edit-distance matrix used
 * for grading, so the two always agree on what counts as one mistake.
 */
object Diff {

    fun of(expected: String, typed: String): List<DiffOp> {
        if (expected.isEmpty()) return typed.map { DiffOp(DiffKind.EXTRA, typed = it) }
        if (typed.isEmpty()) return expected.map { DiffOp(DiffKind.MISSING, expected = it) }

        val costs = Array(expected.length + 1) { IntArray(typed.length + 1) }
        for (i in 0..expected.length) costs[i][0] = i
        for (j in 0..typed.length) costs[0][j] = j
        for (i in 1..expected.length) {
            for (j in 1..typed.length) {
                val substitution = costs[i - 1][j - 1] + if (expected[i - 1] == typed[j - 1]) 0 else 1
                costs[i][j] = minOf(substitution, costs[i - 1][j] + 1, costs[i][j - 1] + 1)
            }
        }

        val operations = ArrayDeque<DiffOp>()
        var i = expected.length
        var j = typed.length
        while (i > 0 || j > 0) {
            val same = i > 0 && j > 0 && expected[i - 1] == typed[j - 1]
            when {
                i > 0 && j > 0 && same && costs[i][j] == costs[i - 1][j - 1] -> {
                    operations.addFirst(DiffOp(DiffKind.MATCH, expected[i - 1], typed[j - 1]))
                    i--
                    j--
                }

                i > 0 && j > 0 && costs[i][j] == costs[i - 1][j - 1] + 1 -> {
                    operations.addFirst(DiffOp(DiffKind.WRONG, expected[i - 1], typed[j - 1]))
                    i--
                    j--
                }

                i > 0 && costs[i][j] == costs[i - 1][j] + 1 -> {
                    operations.addFirst(DiffOp(DiffKind.MISSING, expected = expected[i - 1]))
                    i--
                }

                else -> {
                    operations.addFirst(DiffOp(DiffKind.EXTRA, typed = typed[j - 1]))
                    j--
                }
            }
        }
        return operations.toList()
    }

    /** The expected spelling, marking the letters that were not produced. */
    fun expectedSide(operations: List<DiffOp>): List<DiffOp> =
        operations.filter { it.kind != DiffKind.EXTRA }

    /** What was actually typed, marking the letters that do not belong. */
    fun typedSide(operations: List<DiffOp>): List<DiffOp> =
        operations.filter { it.kind != DiffKind.MISSING }
}
