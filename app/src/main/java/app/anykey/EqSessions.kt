package app.anykey

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.util.Log

/**
 * The "system-wide EQ" route: music apps announce their audio sessions so equalizer
 * apps can attach effects to them. We use that to turn the *original* down with a
 * built-in effect, hoping playback capture still receives it at full level.
 */
object EqSessions {
    private const val TAG = "AnyKey"
    private const val PRIORITY = 1000 // outrank ordinary EQ apps on the same session

    private val sessions = LinkedHashMap<Int, String>()        // session id -> package
    private val effects = HashMap<Int, AudioEffect>()
    private val results = HashMap<Int, String>()

    @Volatile var hiding = false
        private set

    @Synchronized fun opened(session: Int, pkg: String) {
        if (session <= 0) return
        sessions[session] = pkg
        if (hiding) attach(session)
    }

    @Synchronized fun closed(session: Int) {
        sessions.remove(session)
        detach(session)
        results.remove(session)
    }

    @Synchronized fun setHiding(on: Boolean) {
        hiding = on
        for (id in sessions.keys.toList()) if (on) attach(id) else detach(id)
        if (!on) results.clear()
    }

    @Synchronized fun describe(): String {
        if (sessions.isEmpty()) {
            return "No music-app sessions detected yet. Start playback in the app (pausing/playing again helps)."
        }
        return sessions.entries.joinToString("\n") { (id, pkg) ->
            "• $pkg (session $id)" + (results[id]?.let { " — $it" } ?: "")
        }
    }

    private fun attach(session: Int) {
        if (effects.containsKey(session)) return
        // First choice: DynamicsProcessing input gain, which can go far below an EQ's ±15 dB.
        try {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                false, 0, false, 0, false, 0, false
            ).setInputGainAllChannelsTo(-60f).build()
            val dp = DynamicsProcessing(PRIORITY, session, config)
            dp.setInputGainAllChannelsTo(-60f)
            dp.setEnabled(true)
            effects[session] = dp
            results[session] = "hidden via DynamicsProcessing (%.0f dB, control=%b)".format(
                dp.getInputGainByChannelIndex(0), dp.hasControl()
            )
            return
        } catch (e: Exception) {
            Log.w(TAG, "DynamicsProcessing failed on $session", e)
            results[session] = "DynamicsProcessing failed: ${e.message}"
        }
        // Fallback: an equalizer with every band at its minimum (usually −15 dB).
        try {
            val eq = Equalizer(PRIORITY, session)
            val min = eq.bandLevelRange[0]
            for (b in 0 until eq.numberOfBands) eq.setBandLevel(b.toShort(), min)
            eq.setEnabled(true)
            effects[session] = eq
            results[session] = "hidden via Equalizer (%d dB, control=%b)".format(min / 100, eq.hasControl())
        } catch (e: Exception) {
            Log.w(TAG, "Equalizer failed on $session", e)
            results[session] = (results[session] ?: "") + "; Equalizer failed: ${e.message}"
        }
    }

    private fun detach(session: Int) {
        effects.remove(session)?.let {
            runCatching { it.setEnabled(false) }
            it.release()
        }
    }
}

/** Receives the session announcements music apps send to equalizers. */
class EqSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "unknown app"
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> EqSessions.opened(session, pkg)
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> EqSessions.closed(session)
        }
    }
}
