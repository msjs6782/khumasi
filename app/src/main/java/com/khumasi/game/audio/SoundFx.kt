package com.khumasi.game.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Short, soft game sounds and a calm music loop, all synthesized in code (no audio files, no
 * licensing). They are rendered once in the background into small WAV files in the cache.
 */
class SoundFx(private val context: Context) {

    enum class Fx { TAP, DELETE, CORRECT, PRESENT, ABSENT, INVALID, WIN, LOSE, LEVEL_UP }

    var soundOn = true
    var musicOn = false
        set(value) { field = value; updateMusic() }

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private val pool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
    private val ids = HashMap<Fx, Int>()
    private val worker = Executors.newSingleThreadExecutor()
    private var music: MediaPlayer? = null
    private var musicFile: File? = null
    private var resumed = false

    init {
        worker.execute {
            try {
                val dir = File(context.cacheDir, "sfx-v$VERSION").apply { mkdirs() }
                for (fx in Fx.values()) {
                    val f = File(dir, "${fx.name.lowercase()}.wav")
                    if (!f.exists()) writeWav(f, render(fx))
                    val id = pool.load(f.path, 1)
                    synchronized(ids) { ids[fx] = id }
                }
                val m = File(dir, "music.wav")
                if (!m.exists()) writeWav(m, renderMusic())
                musicFile = m
                android.os.Handler(android.os.Looper.getMainLooper()).post { updateMusic() }
            } catch (t: Throwable) {
                Log.w("KhumasiAudio", "Audio setup failed", t)
            }
        }
    }

    fun play(fx: Fx, volume: Float = 0.7f) {
        if (!soundOn) return
        val id = synchronized(ids) { ids[fx] } ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    fun onResume() { resumed = true; updateMusic() }

    fun onPause() { resumed = false; updateMusic() }

    fun release() {
        music?.release(); music = null
        pool.release()
        worker.shutdownNow()
    }

    private fun updateMusic() {
        val file = musicFile
        if (!musicOn || !resumed || file == null) {
            music?.let { if (it.isPlaying) it.pause() }
            return
        }
        try {
            val m = music ?: MediaPlayer().also {
                it.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                it.setDataSource(file.path)
                it.isLooping = true
                it.prepare()
                it.setVolume(0.32f, 0.32f)
                music = it
            }
            if (!m.isPlaying) m.start()
        } catch (t: Throwable) {
            Log.w("KhumasiAudio", "Music failed", t)
        }
    }

    // ------------------------------------------------------------------ synthesis

    private class Partial(val freq: Double, val amp: Double)

    /** Adds a decaying tone (sum of partials) into [out] starting at [startSec]. */
    private fun addTone(out: FloatArray, startSec: Double, partials: List<Partial>, lengthSec: Double,
                        attackSec: Double, decay: Double, noise: Double = 0.0, noiseDecay: Double = 60.0, seed: Int = 1) {
        val rnd = Random(seed)
        val start = (startSec * RATE).toInt()
        val n = (lengthSec * RATE).toInt()
        for (k in 0 until n) {
            val i = start + k
            if (i >= out.size) break
            val t = k.toDouble() / RATE
            val env = min(1.0, t / attackSec) * exp(-t * decay)
            var v = 0.0
            for (p in partials) v += p.amp * sin(2 * PI * p.freq * t)
            if (noise > 0) v += noise * (rnd.nextDouble() * 2 - 1) * exp(-t * noiseDecay)
            out[i] += (v * env).toFloat()
        }
    }

    private fun bell(f: Double) = listOf(Partial(f, 1.0), Partial(f * 2, 0.28), Partial(f * 3, 0.08), Partial(f * 4.2, 0.04))

    private fun render(fx: Fx): FloatArray {
        val len = when (fx) {
            Fx.WIN -> 1.3; Fx.LOSE -> 1.2; Fx.LEVEL_UP -> 1.2; else -> 0.45
        }
        val out = FloatArray((len * RATE).toInt())
        when (fx) {
            Fx.TAP -> addTone(out, 0.0, listOf(Partial(1650.0, 0.18), Partial(3300.0, 0.05)), 0.05, 0.001, 110.0, noise = 0.35, noiseDecay = 350.0)
            Fx.DELETE -> addTone(out, 0.0, listOf(Partial(820.0, 0.22)), 0.06, 0.001, 90.0, noise = 0.25, noiseDecay = 300.0, seed = 2)
            Fx.CORRECT -> addTone(out, 0.0, bell(784.0), 0.42, 0.003, 11.0)
            Fx.PRESENT -> addTone(out, 0.0, bell(622.25), 0.4, 0.003, 12.0)
            Fx.ABSENT -> addTone(out, 0.0, listOf(Partial(196.0, 0.7), Partial(392.0, 0.18)), 0.25, 0.002, 22.0, noise = 0.25, noiseDecay = 45.0, seed = 3)
            Fx.INVALID -> addTone(out, 0.0, listOf(Partial(155.0, 0.45), Partial(161.0, 0.45)), 0.3, 0.01, 9.0)
            Fx.WIN -> for ((i, f) in listOf(523.25, 659.25, 783.99, 1046.5).withIndex()) addTone(out, i * 0.11, bell(f), 0.8, 0.004, 5.5)
            Fx.LOSE -> for ((i, f) in listOf(392.0, 329.63, 261.63).withIndex()) addTone(out, i * 0.2, bell(f), 0.7, 0.01, 4.5)
            Fx.LEVEL_UP -> for ((i, f) in listOf(783.99, 987.77, 1174.66, 1567.98, 1975.53).withIndex()) addTone(out, i * 0.075, bell(f), 0.6, 0.002, 6.5)
        }
        normalize(out, 0.55f)
        return out
    }

    /** A 32-second seamless loop: soft pad chords with gentle pentatonic plucks. */
    private fun renderMusic(): FloatArray {
        val seconds = 32.0
        val n = (seconds * RATE).toInt()
        val out = FloatArray(n)
        val chords = listOf(
            doubleArrayOf(130.81, 196.0, 329.63, 493.88),
            doubleArrayOf(110.0, 164.81, 261.63, 392.0),
            doubleArrayOf(87.31, 130.81, 220.0, 329.63),
            doubleArrayOf(98.0, 146.83, 246.94, 293.66),
        )
        val seg = seconds / chords.size
        val fade = 1.6
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val k = (t / seg).toInt() % chords.size
            val local = t - k * seg
            val w = min(1.0, local / fade)
            val wPrev = 1.0 - w
            var v = 0.0
            for (f in chords[k]) v += sin(2 * PI * f * t + 0.3 * sin(2 * PI * 0.2 * t)) * 0.06 * w
            if (wPrev > 0) {
                val prev = chords[(k + chords.size - 1) % chords.size]
                for (f in prev) v += sin(2 * PI * f * t) * 0.06 * wPrev
            }
            out[i] = v.toFloat()
        }
        // plucks, wrapped around the loop so it stays seamless
        val notes = doubleArrayOf(523.25, 587.33, 659.25, 783.99, 880.0)
        val rnd = Random(11)
        var t = 0.4
        while (t < seconds) {
            val f = notes[rnd.nextInt(notes.size)] / (if (rnd.nextInt(3) == 0) 2 else 1)
            val start = (t * RATE).toInt()
            val len = (2.4 * RATE).toInt()
            for (k in 0 until len) {
                val tt = k.toDouble() / RATE
                val env = min(1.0, tt / 0.004) * exp(-tt * 2.6)
                val v = (sin(2 * PI * f * tt) + 0.25 * sin(4 * PI * f * tt)) * env * 0.09
                val idx = (start + k) % n
                out[idx] += v.toFloat()
            }
            t += 0.5 + rnd.nextInt(3) * 0.5
        }
        normalize(out, 0.5f)
        return out
    }

    private fun normalize(a: FloatArray, peak: Float) {
        var m = 0f
        for (v in a) m = maxOf(m, kotlin.math.abs(v))
        if (m <= 0f) return
        val g = peak / m
        for (i in a.indices) a[i] *= g
    }

    private fun writeWav(file: File, samples: FloatArray) {
        val dataBytes = samples.size * 2
        val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()); buf.putInt(36 + dataBytes); buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()); buf.putInt(16); buf.putShort(1); buf.putShort(1)
        buf.putInt(RATE); buf.putInt(RATE * 2); buf.putShort(2); buf.putShort(16)
        buf.put("data".toByteArray()); buf.putInt(dataBytes)
        for (v in samples) buf.putShort((v.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { it.write(buf.array()) }
        tmp.renameTo(file)
    }

    companion object {
        private const val RATE = 44100
        private const val VERSION = 1
    }
}
