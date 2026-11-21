package lingue.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffTest {

    private fun kinds(expected: String, typed: String) = Diff.of(expected, typed).map { it.kind }

    private fun render(operations: List<DiffOp>, pick: (DiffOp) -> Char?) =
        operations.mapNotNull(pick).joinToString("")

    @Test
    fun `an exact answer is all matches`() {
        assertTrue(kinds("cozy", "cozy").all { it == DiffKind.MATCH })
    }

    @Test
    fun `a dropped letter is reported as missing`() {
        val operations = Diff.of("accommodation", "accomodation")

        assertEquals(1, operations.count { it.kind == DiffKind.MISSING })
        assertEquals(0, operations.count { it.kind == DiffKind.EXTRA })
        assertEquals('m', operations.first { it.kind == DiffKind.MISSING }.expected)
    }

    @Test
    fun `a doubled letter is reported as extra`() {
        val operations = Diff.of("cozy", "coozy")

        assertEquals(1, operations.count { it.kind == DiffKind.EXTRA })
        assertEquals('o', operations.first { it.kind == DiffKind.EXTRA }.typed)
    }

    @Test
    fun `a swapped letter is reported as wrong, keeping both sides`() {
        val operations = Diff.of("cozy", "cosy")
        val wrong = operations.single { it.kind == DiffKind.WRONG }

        assertEquals('z', wrong.expected)
        assertEquals('s', wrong.typed)
    }

    @Test
    fun `an empty answer leaves the whole word missing`() {
        assertEquals(listOf(DiffKind.MISSING, DiffKind.MISSING, DiffKind.MISSING), kinds("owe", ""))
    }

    @Test
    fun `each side reconstructs its own string`() {
        val operations = Diff.of("accommodation", "acomodation")

        assertEquals("accommodation", render(operations) { if (it.kind == DiffKind.EXTRA) null else it.expected })
        assertEquals("acomodation", render(operations) { if (it.kind == DiffKind.MISSING) null else it.typed })
    }

    @Test
    fun `the projections drop only the side that has no letter`() {
        val operations = Diff.of("cozy", "coozy")

        assertTrue(Diff.expectedSide(operations).none { it.kind == DiffKind.EXTRA })
        assertTrue(Diff.typedSide(operations).none { it.kind == DiffKind.MISSING })
        assertTrue(Diff.expectedSide(operations).all { it.expected != null })
        assertTrue(Diff.typedSide(operations).all { it.typed != null })
    }

    @Test
    fun `the number of edits agrees with the grading distance`() {
        // The diff and the rating must never disagree about how wrong an answer is.
        listOf("accommodation" to "acomodation", "fair" to "fear", "owe" to "own", "cozy" to "cozy")
            .forEach { (expected, typed) ->
                val edits = Diff.of(expected, typed).count { it.kind != DiffKind.MATCH }
                assertEquals(Grading.levenshtein(expected, typed), edits, "$expected vs $typed")
            }
    }
}
