package app.anykey

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import kotlin.math.log10

class MainActivity : Activity() {

    private val reqPermissions = 1
    private val reqProjection = 2

    private lateinit var startButton: Button
    private lateinit var outputSpinner: Spinner
    private lateinit var semitoneLabel: TextView
    private lateinit var centsLabel: TextView
    private lateinit var inputMeter: ProgressBar
    private lateinit var outputMeter: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private var savedMediaVolume = -1
    private val testTone = TestTone()
    private lateinit var toneButton: Button
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startButton = findViewById(R.id.start)
        outputSpinner = findViewById(R.id.output)
        semitoneLabel = findViewById(R.id.semitoneLabel)
        centsLabel = findViewById(R.id.centsLabel)
        inputMeter = findViewById(R.id.inputMeter)
        outputMeter = findViewById(R.id.outputMeter)
        statusText = findViewById(R.id.status)
        volumeText = findViewById(R.id.volumes)

        outputSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, OutputStream.entries.map { it.label }
        )

        val semitones = findViewById<SeekBar>(R.id.semitones)
        semitones.progress = CaptureService.semitones + 12
        semitones.setOnSeekBarChangeListener(onChange { CaptureService.semitones = it - 12 })
        findViewById<Button>(R.id.down).setOnClickListener { semitones.progress -= 1 }
        findViewById<Button>(R.id.up).setOnClickListener { semitones.progress += 1 }

        val cents = findViewById<SeekBar>(R.id.cents)
        cents.progress = CaptureService.cents + 50
        cents.setOnSeekBarChangeListener(onChange { CaptureService.cents = it - 50 })
        findViewById<Button>(R.id.resetCents).setOnClickListener { cents.progress = 50 }

        startButton.setOnClickListener {
            if (CaptureService.running) {
                stopService(Intent(this, CaptureService::class.java))
            } else {
                requestPermissionsThenCapture()
            }
        }

        toneButton = findViewById(R.id.testTone)
        toneButton.setOnClickListener { if (testTone.isPlaying) testTone.stop() else testTone.start() }

        findViewById<Button>(R.id.muteMedia).setOnClickListener {
            if (savedMediaVolume < 0) savedMediaVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
        }
        findViewById<Button>(R.id.restoreMedia).setOnClickListener {
            val v = if (savedMediaVolume >= 0) savedMediaVolume
            else audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
            savedMediaVolume = -1
        }
        findViewById<Button>(R.id.outputVolume).setOnClickListener {
            val stream = OutputStream.entries[outputSpinner.selectedItemPosition].volumeStream
            audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(tick)
    }

    override fun onDestroy() {
        testTone.stop()
        super.onDestroy()
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    private fun refresh() {
        val st = CaptureService.semitones
        semitoneLabel.text = when {
            st > 0 -> "+$st semitones"
            st < 0 -> "$st semitones"
            else -> "Original key"
        }
        centsLabel.text = "Fine tune: %+d cents".format(CaptureService.cents)
        startButton.text = if (CaptureService.running) "Stop" else "Start transposing"
        toneButton.text = if (testTone.isPlaying) "Stop test tone" else "Play test tone"
        outputSpinner.isEnabled = !CaptureService.running
        inputMeter.progress = meter(CaptureService.inputLevel)
        outputMeter.progress = meter(CaptureService.outputLevel)
        statusText.text = CaptureService.status
        val out = OutputStream.entries[outputSpinner.selectedItemPosition]
        volumeText.text = "Media volume: %d/%d · %s volume: %d/%d".format(
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            out.label,
            audioManager.getStreamVolume(out.volumeStream),
            audioManager.getStreamMaxVolume(out.volumeStream),
        )
    }

    /** Map RMS to 0..100 over a -60..0 dBFS range. */
    private fun meter(rms: Float): Int {
        if (rms <= 1e-6f) return 0
        val db = 20 * log10(rms.toDouble())
        return ((db + 60) / 60 * 100).toInt().coerceIn(0, 100)
    }

    private fun requestPermissionsThenCapture() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

        if (needed.isEmpty()) requestCapture() else requestPermissions(needed.toTypedArray(), reqPermissions)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != reqPermissions) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            requestCapture()
        } else {
            CaptureService.status = "Microphone/audio permission is required to capture other apps' audio"
        }
    }

    private fun requestCapture() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            // Whole-screen projection: single-app sharing would limit audio capture to that app.
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, reqProjection)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqProjection) return
        if (resultCode != RESULT_OK || data == null) {
            CaptureService.status = "Screen-capture permission was declined"
            return
        }
        val service = Intent(this, CaptureService::class.java)
            .putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(CaptureService.EXTRA_DATA, data)
            .putExtra(CaptureService.EXTRA_OUTPUT, outputSpinner.selectedItemPosition)
        startForegroundService(service)
    }

    private fun onChange(block: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) = block(progress)
        override fun onStartTrackingTouch(seekBar: SeekBar) {}
        override fun onStopTrackingTouch(seekBar: SeekBar) {}
    }
}
