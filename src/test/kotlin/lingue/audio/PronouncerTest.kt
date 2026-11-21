package lingue.audio

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PronouncerTest {

    private lateinit var directory: Path
    private lateinit var pronouncer: Pronouncer

    private val format = AudioFormat(22_050f, 16, 1, true, false)

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("lingue-audio-test")
        pronouncer = Pronouncer(directory)
    }

    @AfterTest
    fun tearDown() = pronouncer.close()

    /** Silence of a known length — the header is what is being tested, not the sound. */
    private fun writeSilence(wordId: Long, seconds: Double) {
        val frames = (format.frameRate * seconds).toInt()
        val bytes = ByteArray(frames * format.frameSize)
        AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()).use { stream ->
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, directory.resolve("$wordId.wav").toFile())
        }
    }

    @Test
    fun `duration is read from the file header`() {
        writeSilence(wordId = 7, seconds = 1.5)

        val millis = pronouncer.durationMillis(7)

        assertTrue(abs(millis - 1_500) <= 20, "expected about 1500 ms, got $millis")
    }

    @Test
    fun `a word without audio reports nothing and playing it is harmless`() {
        assertFalse(pronouncer.has(42))

        pronouncer.play(42)

        assertEquals(0, pronouncer.remainingMillis(), "a missing file must not hold up the next card")
    }

    @Test
    fun `playing reserves the length of the clip`() {
        writeSilence(wordId = 7, seconds = 1.5)

        pronouncer.play(7)

        val remaining = pronouncer.remainingMillis()
        assertTrue(
            remaining in 1_000..1_500,
            "the caller must see the reservation immediately, not after playback starts; got $remaining",
        )
    }

    @Test
    fun `a corrupt file falls back rather than throwing`() {
        Files.writeString(directory.resolve("9.wav"), "this is not audio")

        assertTrue(pronouncer.has(9))
        assertTrue(pronouncer.durationMillis(9) > 0)
        pronouncer.play(9)
    }

}

/**
 * What actually reaches the sound output. Uses a recording line in place of the
 * sound card: the regression being guarded against lived in the platform's
 * playback path, so what matters is that every byte of the file is handed over.
 */
class PronouncerPlaybackTest {

    private lateinit var directory: Path
    private val lines = java.util.concurrent.CopyOnWriteArrayList<RecordingLine>()
    private lateinit var pronouncer: Pronouncer

    private val format = AudioFormat(22_050f, 16, 1, true, false)

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("lingue-playback-test")
        pronouncer = Pronouncer(directory, openLine = { format -> RecordingLine(format).also(lines::add) })
    }

    @AfterTest
    fun tearDown() = pronouncer.close()

    /** A non-silent pattern, so a truncated copy cannot pass for a complete one. */
    private fun writeTone(wordId: Long, seconds: Double): ByteArray {
        val frames = (format.frameRate * seconds).toInt()
        val bytes = ByteArray(frames * format.frameSize) { index -> (index % 251).toByte() }
        AudioInputStream(ByteArrayInputStream(bytes), format, frames.toLong()).use { stream ->
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, directory.resolve("$wordId.wav").toFile())
        }
        return bytes
    }

    private fun awaitLine(index: Int): RecordingLine {
        val deadline = System.currentTimeMillis() + 5_000
        while (lines.size <= index) {
            check(System.currentTimeMillis() < deadline) { "playback never opened line #$index" }
            Thread.sleep(5)
        }
        return lines[index]
    }

    @Test
    fun `a clip longer than a second and a half is played to the end`() {
        // The old Clip-based player stopped every clip of 1.5 s or more after its
        // first second on this machine's sound stack — "see, saw, seen" came out
        // as "see". Three seconds is well past that boundary.
        val tone = writeTone(wordId = 113, seconds = 3.0)

        pronouncer.play(113)

        val line = awaitLine(0)
        assertTrue(line.awaitClosed(), "playback never finished")
        assertContentEquals(tone, line.bytes, "every byte of the file must reach the output")
    }

    @Test
    fun `a new word cuts off the one still playing`() {
        val first = writeTone(wordId = 1, seconds = 3.0)
        val second = writeTone(wordId = 2, seconds = 0.5)

        pronouncer.play(1)
        val firstLine = awaitLine(0)
        while (firstLine.bytes.isEmpty()) Thread.sleep(5)
        pronouncer.play(2)

        val secondLine = awaitLine(1)
        assertTrue(secondLine.awaitClosed(), "the second word never finished")
        assertTrue(firstLine.awaitClosed(), "the interrupted line must be released")
        assertTrue(firstLine.bytes.size < first.size, "the first word must not keep playing over the second")
        assertContentEquals(second, secondLine.bytes)
    }
}
