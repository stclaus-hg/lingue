package lingue.audio

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.Control
import javax.sound.sampled.Line
import javax.sound.sampled.LineListener
import javax.sound.sampled.SourceDataLine

/**
 * A sound output that keeps what it is given instead of playing it.
 *
 * Writes take time in proportion to their length, [speedup] times faster than
 * real playback, so a clip is "still playing" for a while — which is what the
 * interruption test needs. Like a real line, a stopped line accepts nothing more
 * and returns from [write] and [drain] at once.
 */
class RecordingLine(private val format: AudioFormat, private val speedup: Int = 10) : SourceDataLine {

    private val received = ByteArrayOutputStream()
    private val closedSignal = CountDownLatch(1)

    @Volatile var stopped = false
        private set

    @Volatile private var open = false

    val bytes: ByteArray get() = synchronized(received) { received.toByteArray() }

    fun awaitClosed(seconds: Long = 10): Boolean = closedSignal.await(seconds, TimeUnit.SECONDS)

    override fun write(b: ByteArray, off: Int, len: Int): Int {
        if (stopped) return 0
        val realMillis = len * 1000L / (format.frameRate.toLong() * format.frameSize)
        Thread.sleep(realMillis / speedup)
        if (stopped) return 0
        synchronized(received) { received.write(b, off, len) }
        return len
    }

    override fun open(format: AudioFormat, bufferSize: Int) = open(format)
    override fun open(format: AudioFormat) { open = true }
    override fun open() { open = true }
    override fun close() { open = false; closedSignal.countDown() }
    override fun isOpen(): Boolean = open
    override fun start() { stopped = false }
    override fun stop() { stopped = true }
    override fun drain() {}
    override fun flush() {}
    override fun isRunning(): Boolean = open && !stopped
    override fun isActive(): Boolean = isRunning
    override fun getFormat(): AudioFormat = format
    override fun getBufferSize(): Int = format.frameSize * format.frameRate.toInt()
    override fun available(): Int = bufferSize
    override fun getFramePosition(): Int = (bytes.size / format.frameSize)
    override fun getLongFramePosition(): Long = framePosition.toLong()
    override fun getMicrosecondPosition(): Long = framePosition * 1_000_000L / format.frameRate.toLong()
    override fun getLevel(): Float = AudioSystem_NOT_SPECIFIED
    override fun getLineInfo(): Line.Info = Line.Info(SourceDataLine::class.java)
    override fun getControls(): Array<Control> = emptyArray()
    override fun isControlSupported(control: Control.Type): Boolean = false
    override fun getControl(control: Control.Type): Control = throw IllegalArgumentException("no controls")
    override fun addLineListener(listener: LineListener) {}
    override fun removeLineListener(listener: LineListener) {}

    private companion object {
        const val AudioSystem_NOT_SPECIFIED = -1f
    }
}
