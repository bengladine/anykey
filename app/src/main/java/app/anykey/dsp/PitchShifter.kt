package app.anykey.dsp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Streaming phase-vocoder pitch shifter for a single channel.
 *
 * Feed it interleaved audio with [process]; it shifts pitch by `ratio` without
 * changing duration. Latency is [latency] samples (fftSize - hop), i.e.
 * ~32 ms at 48 kHz with the defaults.
 *
 * Good enough to judge whether system-wide transposing is usable; a production
 * build would swap this for a transient-aware library (Signalsmith Stretch,
 * Rubber Band) through the NDK.
 */
class PitchShifter(
    private val fftSize: Int = 2048,
    private val oversampling: Int = 4,
) {
    init {
        require(fftSize > 0 && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two" }
        require(fftSize % oversampling == 0)
    }

    private val half = fftSize / 2
    private val hop = fftSize / oversampling
    val latency = fftSize - hop
    private val expectedPhaseAdvance = 2.0 * PI * hop / fftSize
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2.0 * PI * it / fftSize) }
    private val outputGain = 4.0 / (3.0 * half * oversampling)

    private val inFifo = FloatArray(fftSize)
    private val outFifo = FloatArray(fftSize)
    private val accum = DoubleArray(fftSize)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val lastPhase = DoubleArray(half + 1)
    private val anaMag = DoubleArray(half + 1)
    private val anaPhase = DoubleArray(half + 1)
    private val anaFreq = DoubleArray(half + 1)
    private val synMag = DoubleArray(half + 1)
    private val synPhase = DoubleArray(half + 1)
    private val peaks = IntArray(half + 1)
    private val prevPeakPhase = DoubleArray(half + 1)
    private val prevPeakMag = DoubleArray(half + 1)
    private val prevPeakFrame = LongArray(half + 1) { -10 }
    private val synWinner = DoubleArray(half + 1)
    private var frameCount = 0L
    private val fft = Fft(fftSize)
    private var rover = latency

    /**
     * Process [frames] samples of channel [channel] in place inside an interleaved
     * buffer with [channels] channels.
     */
    fun process(buf: FloatArray, channel: Int, channels: Int, frames: Int, ratio: Double) {
        var idx = channel
        for (i in 0 until frames) {
            inFifo[rover] = buf[idx]
            buf[idx] = outFifo[rover - latency]
            rover++
            if (rover >= fftSize) {
                rover = latency
                processFrame(ratio)
            }
            idx += channels
        }
    }

    private fun processFrame(ratio: Double) {
        for (k in 0 until fftSize) {
            re[k] = inFifo[k] * window[k]
            im[k] = 0.0
        }
        fft.transform(re, im, inverse = false)

        // Analysis: estimate the true frequency (in bins) of each bin from phase drift.
        for (k in 0..half) {
            val phase = atan2(im[k], re[k])
            var delta = phase - lastPhase[k]
            lastPhase[k] = phase
            delta = wrap(delta - k * expectedPhaseAdvance)
            anaPhase[k] = phase
            anaMag[k] = 2.0 * hypot(re[k], im[k])
            anaFreq[k] = k + delta * oversampling / (2.0 * PI)
        }

        // Shift with identity phase locking (Laroche & Dolson): each spectral peak
        // moves with its whole lobe, and lobe bins keep their phase relative to the
        // peak. This avoids the level wobble and "phasiness" of naive bin shifting.
        synMag.fill(0.0)
        synPhase.fill(0.0)
        synWinner.fill(0.0)
        var maxMag = 0.0
        for (k in 0..half) if (anaMag[k] > maxMag) maxMag = anaMag[k]
        val peakFloor = maxOf(maxMag * 1e-4, 1e-9) // ignore peaks 80 dB below the loudest
        var peakCount = 0
        for (k in 1 until half) {
            if (anaMag[k] > anaMag[k - 1] && anaMag[k] >= anaMag[k + 1] && anaMag[k] > peakFloor) {
                peaks[peakCount++] = k
            }
        }
        for (i in 0 until peakCount) {
            val p = peaks[i]
            val lo = if (i == 0) 0 else (peaks[i - 1] + p) / 2 + 1
            val hi = if (i == peakCount - 1) half else (p + peaks[i + 1]) / 2
            val shiftedFreq = anaFreq[p] * ratio
            val tp = p + floor(anaFreq[p] * (ratio - 1.0) + 0.5).toInt()
            if (tp < 0 || tp > half) continue

            // Continue the phase of the nearest peak from the previous frame, if any.
            val advance = 2.0 * PI * shiftedFreq * hop / fftSize
            var peakPhase = anaPhase[p]
            for (d in intArrayOf(0, -1, 1, -2, 2)) {
                val t = tp + d
                if (t in 0..half && prevPeakFrame[t] == frameCount - 1) {
                    peakPhase = prevPeakPhase[t] + advance
                    break
                }
            }
            peakPhase = wrap(peakPhase)
            // If two peaks land on the same bin, the louder one owns its phase history.
            if (prevPeakFrame[tp] != frameCount || anaMag[p] > prevPeakMag[tp]) {
                prevPeakPhase[tp] = peakPhase
                prevPeakMag[tp] = anaMag[p]
                prevPeakFrame[tp] = frameCount
            }

            for (k in lo..hi) {
                val t = tp + (k - p)
                if (t in 0..half) {
                    synMag[t] += anaMag[k]
                    // Where shifted lobes overlap, the strongest contribution sets the phase.
                    if (anaMag[k] > synWinner[t]) {
                        synWinner[t] = anaMag[k]
                        synPhase[t] = peakPhase + anaPhase[k] - anaPhase[p]
                    }
                }
            }
        }
        frameCount++

        for (k in 0..half) {
            re[k] = synMag[k] * cos(synPhase[k])
            im[k] = synMag[k] * sin(synPhase[k])
        }
        for (k in half + 1 until fftSize) {
            re[k] = 0.0
            im[k] = 0.0
        }
        fft.transform(re, im, inverse = true)

        for (k in 0 until fftSize) accum[k] += window[k] * re[k] * outputGain
        for (k in 0 until hop) outFifo[k] = accum[k].toFloat()
        System.arraycopy(accum, hop, accum, 0, fftSize - hop)
        accum.fill(0.0, fftSize - hop, fftSize)
        System.arraycopy(inFifo, hop, inFifo, 0, latency)
    }

    private fun wrap(x: Double): Double = x - 2.0 * PI * floor((x + PI) / (2.0 * PI))
}

/** In-place iterative radix-2 complex FFT (unnormalised in both directions). */
class Fft(private val n: Int) {
    private val levels = Integer.numberOfTrailingZeros(n)
    private val cosTable = DoubleArray(n / 2) { cos(2.0 * PI * it / n) }
    private val sinTable = DoubleArray(n / 2) { sin(2.0 * PI * it / n) }
    private val reversed = IntArray(n) { Integer.reverse(it) ushr (32 - levels) }

    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        val sign = if (inverse) 1.0 else -1.0
        var size = 2
        while (size <= n) {
            val halfSize = size / 2
            val tableStep = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + halfSize) {
                    val l = j + halfSize
                    val c = cosTable[k]
                    val s = sign * sinTable[k]
                    val tr = re[l] * c - im[l] * s
                    val ti = re[l] * s + im[l] * c
                    re[l] = re[j] - tr
                    im[l] = im[j] - ti
                    re[j] += tr
                    im[j] += ti
                    k += tableStep
                }
                start += size
            }
            size *= 2
        }
    }
}
