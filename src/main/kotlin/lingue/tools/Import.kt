package lingue.tools

import lingue.data.Database
import lingue.data.WordRepository
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Bulk import from a tab-separated file: `term<TAB>translation[<TAB>example]`.
 *
 * Blank lines and lines starting with `#` are ignored. Words already present are
 * skipped rather than overwritten — re-running an import must never reset the
 * scheduling state of words already being learned.
 *
 * Run with: `./gradlew importWords -Pfile=data/words.tsv`
 */
fun main(args: Array<String>) {
    val source = args.firstOrNull()
    if (source == null) {
        System.err.println("usage: importWords -Pfile=<path to tsv>")
        exitProcess(2)
    }

    val path = Path.of(source)
    if (!Files.isReadable(path)) {
        System.err.println("cannot read $path")
        exitProcess(2)
    }

    Database().use { database ->
        val words = WordRepository(database.connection)

        var added = 0
        var skipped = 0
        var malformed = 0

        Files.readAllLines(path).forEachIndexed { index, line ->
            if (line.isBlank() || line.startsWith("#")) return@forEachIndexed

            val columns = line.split('\t').map(String::trim)
            if (columns.size < 2 || columns[0].isEmpty() || columns[1].isEmpty()) {
                System.err.println("line ${index + 1}: expected term<TAB>translation, got: $line")
                malformed++
                return@forEachIndexed
            }

            try {
                words.add(columns[0], columns[1], columns.getOrNull(2))
                added++
            } catch (duplicate: Exception) {
                // The unique index on `term` is the expected failure.
                skipped++
            }
        }

        println("added $added, skipped $skipped already present, $malformed malformed")
        println("collection now holds ${words.count()} words")
    }
}
