package app.anykey

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import app.anykey.dsp.PitchShifter
import android.media.AudioDeviceInfo
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.tanh
import kotlin.math.sqrt

/**
 * Captures what other apps are playing (AudioPlaybackCapture), pitch-shifts it,
 * and plays the result back on a stream the user picks.
 */
class CaptureService : Service() {

    companion object {
        const val ACTION_STOP = "app.anykey.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_OUTPUT = "output"

        private const val TAG = "AnyKey"
        private const val CHANNEL_ID = "capture"
        private const val SAMPLE_RATE = 48000
        private const val BLOCK_FRAMES = 512

        // Shared with the UI. Plain volatiles keep this spike dependency-free.
        @Volatile var semitones = 0
        @Volatile var cents = 0
        @Volatile var running = false
        @Volatile var inputLevel = 0f
        @Volatile var outputLevel = 0f
        @Volatile var status = "Stopped"
    }

    private var projection: MediaProjection? = null
    private var worker: Thread? = null
    @Volatile private var stopRequested = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        // Android 14+: must be in the foreground (mediaProjection type) before using the token.
        startForeground(1, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
        }
        val output = OutputStream.entries.getOrElse(intent.getIntExtra(EXTRA_OUTPUT, 0)) { OutputStream.MEDIA }
        if (data == null) {
            status = "Error: no screen-capture permission token"
            stopSelf()
            return START_NOT_STICKY
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            status = "Error: ${e.message}"
            stopSelf()
            return START_NOT_STICKY
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                status = "Capture was stopped by the system"
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))

        stopRequested = false
        running = true
        status = "Starting…"
        worker = Thread({ audioLoop(mp, output) }, "AnyKey-audio").also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before starting.
    private fun audioLoop(mp: MediaProjection, output: OutputStream) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var record: AudioRecord? = null
        var track: AudioTrack? = null
        try {
            val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build() // our own output is ALLOW_CAPTURE_BY_NONE, so no feedback loop

            // 16-bit capture: the most widely supported format for playback capture.
            val inFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val minIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord.Builder()
                .setAudioFormat(inFormat)
                .setBufferSizeInBytes(maxOf(minIn, BLOCK_FRAMES * 2 * 2 * 4))
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()

            val attrs = AudioAttributes.Builder()
                .setUsage(output.usage)
                .setContentType(output.contentType)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
                .build()
            val outFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build()
            val minOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(outFormat)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minOut, BLOCK_FRAMES * 2 * 4 * 2))
                .build()

            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                status = "Error: capture did not start (state ${record.recordingState})"
                return
            }
            track.play()

            val left = PitchShifter()
            val right = PitchShifter()
            val latencyMs = left.latency * 1000 / SAMPLE_RATE
            val header = "Output: ${output.label} · DSP latency ≈ $latencyMs ms"
            status = "$header\nWaiting for audio…"
            val pcm = ShortArray(BLOCK_FRAMES * 2)
            val buf = FloatArray(BLOCK_FRAMES * 2)
            var reads = 0L
            var silentReads = 0L
            var lastReport = 0L
            val am = getSystemService(AudioManager::class.java)
            var gain = 1f
            var targetGain = 1f
            var compensationText = ""

            while (!stopRequested) {
                val n = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    status = "Error: capture read failed ($n)"
                    break
                }
                if (n == 0) continue
                var peak = 0
                for (i in 0 until n) {
                    val v = pcm[i].toInt()
                    if (v > peak) peak = v else if (-v > peak) peak = -v
                    buf[i] = v / 32768f
                }
                reads++
                if (peak == 0) silentReads++ else silentReads = 0
                if (reads - lastReport >= 20) { // ~5 times per second
                    lastReport = reads
                    val players = am.activePlaybackConfigurations.size
                    if (output != OutputStream.MEDIA) {
                        // Capture is taken after the media volume. Undo that attenuation so the
                        // original can sit at a very low volume while the transposed copy is loud.
                        val boostDb = mediaAttenuationDb(am, track.routedDevice?.type)
                        targetGain = 10f.pow(boostDb / 20f)
                        compensationText = "\nMedia volume compensation: +%.0f dB".format(boostDb)
                    }
                    status = "$header$compensationText\nActive players on phone: $players\n" + if (silentReads >= 20) {
                        "Receiving pure silence — the playing app probably blocks capture"
                    } else {
                        "Receiving audio ✓"
                    }
                }
                val frames = n / 2
                inputLevel = rms(buf, n)
                if (gain != targetGain || gain != 1f) {
                    for (f in 0 until frames) {
                        gain += (targetGain - gain) * 0.002f
                        buf[2 * f] *= gain
                        buf[2 * f + 1] *= gain
                    }
                }
                val ratio = 2.0.pow((semitones + cents / 100.0) / 12.0)
                left.process(buf, 0, 2, frames, ratio)
                right.process(buf, 1, 2, frames, ratio)
                for (i in 0 until n) buf[i] = softClip(buf[i])
                outputLevel = rms(buf, n)
                track.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
            }
        } catch (e: Exception) {
            Log.e(TAG, "audio loop failed", e)
            status = "Error: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { record?.stop() }
            record?.release()
            runCatching { track?.stop() }
            track?.release()
            inputLevel = 0f
            outputLevel = 0f
            if (!stopRequested) Handler(Looper.getMainLooper()).post { stopSelf() }
        }
    }

    /** dB the system applies at the current media volume, as a positive boost (capped at 45 dB). */
    private fun mediaAttenuationDb(am: AudioManager, deviceType: Int?): Float {
        val index = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (index <= 0) return 0f // fully muted: nothing left to recover
        val db = runCatching {
            am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, deviceType ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }.recoverCatching {
            am.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        }.getOrDefault(0f)
        return (-db).coerceIn(0f, 45f)
    }

    /** Transparent below 0.8, smoothly limits above so boosted audio never hard-clips. */
    private fun softClip(x: Float): Float {
        val a = abs(x)
        if (a <= 0.8f) return x
        val y = 0.8f + 0.2f * tanh((a - 0.8f) / 0.2f)
        return if (x < 0) -y else y
    }

    private fun rms(buf: FloatArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) sum += buf[i] * buf[i]
        return sqrt(sum / n).toFloat()
    }

    override fun onDestroy() {
        stopRequested = true
        worker?.join(1000)
        worker = null
        projection?.stop()
        projection = null
        running = false
        if (!status.startsWith("Error") && !status.startsWith("Capture was")) status = "Stopped"
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Transposing", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("AnyKey is transposing")
            .setContentText("Tap to change key")
            .setContentIntent(open)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_media_pause), "Stop", stop
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}

/** Where the transposed audio is played. Non-media streams have their own volume slider. */
enum class OutputStream(val label: String, val usage: Int, val contentType: Int, val volumeStream: Int) {
    MEDIA("Media", AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC, AudioManager.STREAM_MUSIC),
    ALARM("Alarm", AudioAttributes.USAGE_ALARM, AudioAttributes.CONTENT_TYPE_MUSIC, AudioManager.STREAM_ALARM),
    ACCESSIBILITY(
        "Accessibility", AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY,
        AudioAttributes.CONTENT_TYPE_MUSIC, AudioManager.STREAM_ACCESSIBILITY
    ),
    CALL(
        "Voice call", AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.CONTENT_TYPE_SPEECH, AudioManager.STREAM_VOICE_CALL
    ),
}
