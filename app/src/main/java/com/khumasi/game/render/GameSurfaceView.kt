package com.khumasi.game.render

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.view.Choreographer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * OpenGL ES 3 surface. Frames are requested only when needed: every vsync while something
 * animates, every other vsync for the gentle ambient motion, and not at all when idle with
 * reduced motion. This keeps battery use low on a mostly static word game.
 */
@SuppressLint("ViewConstructor")
class GameSurfaceView(context: Context, wantMsaa: Boolean) : GLSurfaceView(context), Choreographer.FrameCallback {

    private val chooser = ConfigChooser(wantMsaa)
    private var renderer: GameRenderer? = null
    private var running = false
    private var frameToggle = false
    @Volatile private var dirty = true

    val msaaSamples: Int get() = chooser.samples

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(chooser)
        preserveEGLContextOnPause = true
    }

    fun attach(r: GameRenderer) {
        renderer = r
        setRenderer(r)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Runs [block] on the GL thread and makes sure a frame follows. */
    fun send(block: GameRenderer.() -> Unit) {
        val r = renderer ?: return
        queueEvent { r.block(); r.syncFromBoard() }
        dirty = true
    }

    fun invalidateScene() { dirty = true }

    override fun onResume() {
        super.onResume()
        running = true
        dirty = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        running = false
        Choreographer.getInstance().removeFrameCallback(this)
        super.onPause()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val r = renderer
        if (r != null && !r.failed) {
            val now = SystemClock.uptimeMillis()
            val animating = now <= r.busyUntil + 32
            val ambient = r.quality.ambient && !r.reduceMotion
            frameToggle = !frameToggle
            if (dirty || animating || (ambient && frameToggle)) {
                dirty = false
                requestRender()
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private class ConfigChooser(private val wantMsaa: Boolean) : EGLConfigChooser {
        var samples = 0

        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val sampleOptions = if (wantMsaa) intArrayOf(4, 0) else intArrayOf(0)
            for (s in sampleOptions) for (depth in intArrayOf(24, 16)) {
                val attribs = intArrayOf(
                    EGL10.EGL_RED_SIZE, 8,
                    EGL10.EGL_GREEN_SIZE, 8,
                    EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, depth,
                    EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                    EGL10.EGL_SAMPLE_BUFFERS, if (s > 0) 1 else 0,
                    EGL10.EGL_SAMPLES, s,
                    EGL10.EGL_NONE,
                )
                val configs = arrayOfNulls<EGLConfig>(1)
                val count = IntArray(1)
                if (egl.eglChooseConfig(display, attribs, configs, 1, count) && count[0] > 0 && configs[0] != null) {
                    samples = s
                    return configs[0]!!
                }
            }
            throw IllegalArgumentException("No OpenGL ES 3 configuration available")
        }
    }

    companion object {
        private const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
