package lingue.audio

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Plays pre-generated pronunciations.
 *
 * Audio is synthesised ahead of time by `scripts/generate-audio.sh` and stored as
 * one file per word, so nothing here depends on a speech engine being installed:
 * the app only opens a WAV. That also keeps playback instant, which matters when
 * a sound accompanies every card.
 *
 * Playback streams into a [SourceDataLine] rather than using a `Clip`. On this
 * machine's ALSA/PulseAudio stack the JDK's `DirectClip` stopped every clip of
 * about 1.5 s or longer after its first second, so "see, saw, seen" was heard as
 * "see" and long phrases lost their tail. Shorter words were unaffected, which is
 * why it went unnoticed until irregular verbs were added. Writing the bytes
 * ourselves and draining the line does not go through that code path.
 */
class Pronouncer(
    private val directory: Path = defaultDirectory(),
    private val openLine: (AudioFormat) -> SourceDataLine = { AudioSystem.getSourceDataLine(it) },
) : AutoCloseable {

    // Reading and decoding a file must not stall the frame that triggered it.
    private val player = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pronouncer").apply { isDaemon = true }
    }

    private val durations = ConcurrentHashMap<Long, Long>()

    /** The line now sounding, so a newer word can cut it off from any thread. */
    @Volatile
    private var current: SourceDataLine? = null

    /** Bumped by every [play]; a playback that sees a newer value gives way. */
    private val generation = AtomicLong()

    @Volatile
    private var silentAt: Long = 0

    fun fileFor(wordId: Long): Path = directory.resolve("$wordId.wav")

    fun has(wordId: Long): Boolean = Files.isReadable(fileFor(wordId))

    /**
     * How long the current clip still has to play.
     *
     * The caller needs this to avoid cutting a word off — see the auto-advance in
     * `TrainScreen`. It is set synchronously from the file header rather than from
     * the playback thread, so a caller reading it immediately after [play] gets a
     * real answer instead of a zero from a not-yet-started clip.
     */
    fun remainingMillis(): Long = (silentAt - System.currentTimeMillis()).coerceAtLeast(0)

    fun play(wordId: Long) {
        val file = fileFor(wordId)
        if (!Files.isReadable(file)) return

        silentAt = System.currentTimeMillis() + durationMillis(wordId)

        // Whatever is still sounding belongs to the previous card and would
        // otherwise overlap with this one. Stopping a line makes its blocked
        // write or drain return, so the player thread is free almost at once.
        val ticket = generation.incrementAndGet()
        current?.stop()

        player.execute {
            if (generation.get() != ticket) return@execute
            runCatching { stream(file, ticket) }
            // A missing sound device, a busy line or a corrupt file must never
            // interrupt a review session — silence is an acceptable outcome here.
        }
    }

    private fun stream(file: Path, ticket: Long) {
        val (format, data) = AudioSystem.getAudioInputStream(file.toFile()).use { it.format to it.readAllBytes() }
        val line = openLine(format)
        line.open(format)
        current = line
        try {
            line.start()
            var offset = 0
            while (offset < data.size && generation.get() == ticket) {
                val written = line.write(data, offset, minOf(CHUNK_BYTES, data.size - offset))
                if (written <= 0) break
                offset += written
            }
            if (generation.get() == ticket) line.drain()
        } finally {
            if (current === line) current = null
            line.close()
        }
    }

    /** Duration from the WAV header, cached: the file never changes under us. */
    fun durationMillis(wordId: Long): Long = durations.getOrPut(wordId) {
        runCatching {
            val format = AudioSystem.getAudioFileFormat(fileFor(wordId).toFile())
            val frames = format.frameLength.toLong()
            val rate = format.format.frameRate
            if (frames <= 0 || rate <= 0f) FALLBACK_MILLIS else (frames * 1000L / rate.toLong())
        }.getOrDefault(FALLBACK_MILLIS)
    }

    override fun close() {
        generation.incrementAndGet()
        current?.runCatching { stop() }
        player.shutdownNow()
    }

    companion object {
        /** Used when a header cannot be read; long enough not to clip a short word. */
        private const val FALLBACK_MILLIS = 1_500L

        /** About 90 ms of 22 kHz mono — small enough that a newer word cuts in quickly. */
        private const val CHUNK_BYTES = 4_096

        fun defaultDirectory(): Path =
            Path.of(System.getProperty("user.home"), ".local", "share", "lingue", "audio")
    }
}
