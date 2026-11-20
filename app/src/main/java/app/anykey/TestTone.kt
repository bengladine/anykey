package app.anykey

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * Plays a looping C-E-G-C arpeggio as a normal, capturable media sound.
 * Lets you check capture and transposition without depending on a streaming app.
 */
class TestTone {
    @Volatile private var playing = false
    private var thread: Thread? = null
    val isPlaying get() = playing

    fun start() {
        if (playing) return
        playing = true
        thread = Thread({ run() }, "AnyKey-tone").also { it.start() }
    }

    fun stop() {
        playing = false
        thread?.join(500)
        thread = null
    }

    private fun run() {
        val sr = 48000
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        val notes = intArrayOf(60, 64, 67, 72) // C4 E4 G4 C5
        val noteLen = sr * 4 / 10
        val buf = ShortArray(noteLen)
        var phase = 0.0
        var n = 0
        try {
            while (playing) {
                val freq = 440.0 * 2.0.pow((notes[n % notes.size] - 69) / 12.0)
                for (i in 0 until noteLen) {
                    val env = minOf(1.0, i / 480.0, (noteLen - i) / 2400.0)
                    phase += 2 * PI * freq / sr
                    val v = 0.3 * env * (sin(phase) + 0.3 * sin(2 * phase))
                    buf[i] = (v * 32767).toInt().toShort()
                }
                track.write(buf, 0, buf.size)
                n++
            }
        } finally {
            track.stop()
            track.release()
        }
    }
}
