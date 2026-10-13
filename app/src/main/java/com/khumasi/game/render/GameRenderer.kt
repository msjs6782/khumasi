package com.khumasi.game.render

import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import com.khumasi.game.core.ArabicNormalizer
import com.khumasi.game.core.LetterState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the island world and the 3D letter board. Every public method except the volatile
 * fields must run on the GL thread (use GLSurfaceView.queueEvent).
 */
class GameRenderer(
    private val typeface: Typeface,
    private val listener: Listener,
) : GLSurfaceView.Renderer {

    interface Listener {
        fun onRendererReady()
        fun onRendererFailed(error: Throwable)
    }

    data class Quality(val shadows: Boolean, val glass: Boolean, val ambient: Boolean)

    internal val board = BoardAnimator(ROWS, COLS)

    @Volatile var reduceMotion = false
        set(value) { field = value; board.reduceMotion = value }
    @Volatile var quality = Quality(shadows = true, glass = true, ambient = true)
    @Volatile var failed = false
        private set

    /** Latest time an animation is running (uptime ms), readable from the UI thread. */
    @Volatile var busyUntil = 0L
        private set

    private var islandLevel = 0
    private var islandDirty = true

    // World view: the board slides away and the camera moves in on the island (round results).
    private var worldFrom = 0f
    private var worldTo = 0f
    private var worldStart = 0L
    private var worldT = 0f
    private var boardBaseShiftY = 0f
    private val region = RectF(0f, 0f, 1f, 1f)
    private var width = 1
    private var height = 1
    private val startTime = SystemClock.uptimeMillis()

    private var firstFrameLogged = false
    private var surfaceCreatedAt = 0L

    // GL resources
    private var ready = false
    private lateinit var tileMesh: Mesh
    private lateinit var slabMesh: Mesh
    private lateinit var quadMesh: Mesh
    private lateinit var seaMesh: Mesh
    private var island: IslandModel? = null
    private lateinit var progTile: ShaderProgram
    private lateinit var progSlab: ShaderProgram
    private lateinit var progDepth: ShaderProgram
    private lateinit var progSky: ShaderProgram
    private lateinit var progSea: ShaderProgram
    private lateinit var progIsland: ShaderProgram
    private lateinit var progBlur: ShaderProgram
    private lateinit var atlas: LetterAtlas
    private var shadow: ShadowTarget? = null
    private lateinit var noShadow: ShadowTarget
    private var bgLow: ColorTarget? = null
    private var blurA: ColorTarget? = null
    private var blurB: ColorTarget? = null

    // Board camera
    private val boardModel = FloatArray(16)
    private val boardView = FloatArray(16)
    private val boardProj = FloatArray(16)
    private val boardVP = FloatArray(16)
    private val boardCam = FloatArray(3)
    private val boardLightVP = FloatArray(16)
    private val boardLight = normalize(floatArrayOf(-0.45f, 0.62f, 0.9f))

    // Background camera
    private val bgView = FloatArray(16)
    private val bgProj = FloatArray(16)
    private val bgVP = FloatArray(16)
    private val bgCam = FloatArray(3)
    private val islandLightVP = FloatArray(16)
    private val islandLight = normalize(floatArrayOf(0.55f, 0.85f, 0.5f))
    private var horizonNdcY = 0.8f
    private val sunNdc = FloatArray(2)

    // Scratch
    private val tileModels = Array(ROWS * COLS) { FloatArray(16) }
    private val tilePoses = Array(ROWS * COLS) { BoardAnimator.Pose() }
    private val tmpA = FloatArray(16)
    private val tmpB = FloatArray(16)
    private val cell = FloatArray(4)
    private val vec = FloatArray(4)
    private val out4 = FloatArray(4)

    // ------------------------------------------------------------------ commands (GL thread)

    fun setIslandLevel(level: Int) {
        if (level != islandLevel) { islandLevel = level; islandDirty = true }
    }

    /** Area (in surface pixels) where the board must fit: between the header and the keyboard. */
    fun setBoardRegion(left: Float, top: Float, right: Float, bottom: Float) {
        region.set(left, top, right, bottom)
        if (ready) layoutCameras()
    }

    fun markBusy(until: Long) { busyUntil = max(busyUntil, until) }

    /** Slides the board away to show the island (true) or brings it back (false). */
    fun setWorldView(on: Boolean, animate: Boolean = true) {
        val now = SystemClock.uptimeMillis()
        val target = if (on) 1f else 0f
        if (!animate || reduceMotion) {
            worldFrom = target; worldTo = target; worldStart = 0L; worldT = target
        } else {
            worldFrom = worldT; worldTo = target; worldStart = now
            markBusy(now + WORLD_MS)
        }
        if (ready) layoutCameras()
    }

    private fun updateWorld(now: Long): Boolean {
        if (worldStart == 0L) return false
        val p = ((now - worldStart).toFloat() / WORLD_MS).coerceIn(0f, 1f)
        val e = BoardAnimator.easeInOutCubic(p)
        worldT = worldFrom + (worldTo - worldFrom) * e
        if (p >= 1f) worldStart = 0L
        return true
    }

    private fun syncBusy() { busyUntil = max(busyUntil, board.busyUntil) }

    fun syncFromBoard() = syncBusy()

    // ------------------------------------------------------------------ lifecycle

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            ready = false
            island = null
            islandDirty = true
            val t0 = SystemClock.uptimeMillis()
            createResources()
            ready = true
            firstFrameLogged = false
            surfaceCreatedAt = SystemClock.uptimeMillis()
            Log.i("KhumasiGL", "GL ready: setup ${surfaceCreatedAt - t0} ms, " +
                "${GLES30.glGetString(GLES30.GL_RENDERER)}, ${GLES30.glGetString(GLES30.GL_VERSION)}")
            listener.onRendererReady()
        } catch (t: Throwable) {
            Log.e("KhumasiGL", "Renderer setup failed", t)
            failed = true
            listener.onRendererFailed(t)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = max(1, w); height = max(1, h)
        GLES30.glViewport(0, 0, width, height)
        if (region.width() <= 1f) region.set(0f, height * 0.1f, width.toFloat(), height * 0.7f)
        if (!ready) return
        try {
            recreateGlassTargets()
            layoutCameras()
        } catch (t: Throwable) {
            Log.e("KhumasiGL", "Resize failed", t)
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) {
            GLES30.glClearColor(0.12f, 0.17f, 0.27f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val now = SystemClock.uptimeMillis()
        val q = quality
        val ambient = q.ambient && !reduceMotion
        val time = if (ambient) (now - startTime) / 1000f else 12f
        if (islandDirty) rebuildIsland()
        if (updateWorld(now)) layoutCameras()
        syncBusy()

        computeTiles(now)
        val shadowTarget = if (q.shadows) shadow else null

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)

        val islandModel = islandMatrix(time, ambient)

        // 1. island shadows
        if (shadowTarget != null) {
            shadowTarget.bind()
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            beginDepthPass()
            drawIslandDepth(islandModel, time)
            endDepthPass()
        }

        // 2. blurred copy of the world for the frosted glass board
        val glass = q.glass && bgLow != null
        if (glass) {
            bgLow!!.bind()
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            drawWorld(islandModel, time, withShadows = false)
            blur()
        }

        // 3. the world on screen
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(0.97f, 0.85f, 0.68f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        drawWorld(islandModel, time, withShadows = shadowTarget != null)

        if (worldT >= 0.999f) return

        // 4. board shadows
        if (shadowTarget != null) {
            shadowTarget.bind()
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
            beginDepthPass()
            progDepth.use()
            progDepth.setMat4("uLightViewProj", boardLightVP)
            for (i in tileModels.indices) {
                if (!tilePoses[i].visible) continue
                progDepth.setMat4("uModel", tileModels[i])
                tileMesh.draw()
            }
            endDepthPass()
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, width, height)
        }

        // 5. board
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        drawSlab(now, glass, shadowTarget != null)
        drawTiles(shadowTarget != null)
        if (!firstFrameLogged) {
            firstFrameLogged = true
            Log.i("KhumasiGL", "first frame ${SystemClock.uptimeMillis() - surfaceCreatedAt} ms after setup")
        }
    }

    // ------------------------------------------------------------------ setup

    private fun createResources() {
        progTile = ShaderProgram("tile", Shaders.TILE_VS, Shaders.TILE_FS)
        progSlab = ShaderProgram("slab", Shaders.TILE_VS, Shaders.SLAB_FS)
        progDepth = ShaderProgram("depth", Shaders.DEPTH_VS, Shaders.DEPTH_FS)
        progSky = ShaderProgram("sky", Shaders.SKY_VS, Shaders.SKY_FS)
        progSea = ShaderProgram("sea", Shaders.SEA_VS, Shaders.SEA_FS)
        progIsland = ShaderProgram("island", Shaders.ISLAND_VS, Shaders.ISLAND_FS)
        progBlur = ShaderProgram("blur", Shaders.BLUR_VS, Shaders.BLUR_FS)

        tileMesh = MeshBuilder().apply { roundedBox(TILE, TILE, TILE_DEPTH, TILE_BEVEL, 4) }.build()
        slabMesh = MeshBuilder().apply { roundedBox(SLAB_W, SLAB_H, SLAB_DEPTH, 0.18f, 3) }.build()
        quadMesh = MeshBuilder().apply { quadXY(1f, 1f) }.build()
        seaMesh = MeshBuilder().apply { disc(170f, 26, 64) }.build()
        atlas = LetterAtlas(typeface, ArabicNormalizer.LETTERS)
        noShadow = ShadowTarget(1)
        shadow = try { ShadowTarget(SHADOW_SIZE) } catch (t: Throwable) { Log.w("KhumasiGL", "No shadows", t); null }
        GlUtil.checkError("createResources")
    }

    private fun recreateGlassTargets() {
        bgLow?.release(); blurA?.release(); blurB?.release()
        val w = max(32, width / 5)
        val h = max(32, height / 5)
        bgLow = ColorTarget(w, h, withDepth = true)
        blurA = ColorTarget(w, h, withDepth = false)
        blurB = ColorTarget(w, h, withDepth = false)
    }

    private fun rebuildIsland() {
        island?.release()
        island = IslandBuilder.build(islandLevel)
        islandDirty = false
    }

    // ------------------------------------------------------------------ cameras

    private fun layoutCameras() {
        layoutBoardCamera()
        layoutBackgroundCamera()
    }

    private fun layoutBoardCamera() {
        val aspect = width.toFloat() / height
        Matrix.setIdentityM(boardModel, 0)
        Matrix.rotateM(boardModel, 0, -TILT_DEG, 1f, 0f, 0f)

        val base = FloatArray(16)
        Matrix.perspectiveM(base, 0, BOARD_FOV, aspect, 1f, 300f)
        val rcx = (region.centerX() / width) * 2f - 1f
        val rcy = 1f - (region.centerY() / height) * 2f
        val rhx = region.width() / width
        val rhy = region.height() / height

        val corners = boardCorners()
        val bounds = FloatArray(4)
        fun measure(dist: Float) {
            Matrix.setLookAtM(tmpA, 0, 0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)
            Matrix.multiplyMM(tmpB, 0, base, 0, tmpA, 0)
            Matrix.multiplyMM(tmpA, 0, tmpB, 0, boardModel, 0)
            bounds[0] = Float.MAX_VALUE; bounds[1] = -Float.MAX_VALUE; bounds[2] = Float.MAX_VALUE; bounds[3] = -Float.MAX_VALUE
            for (c in corners) {
                vec[0] = c[0]; vec[1] = c[1]; vec[2] = c[2]; vec[3] = 1f
                Matrix.multiplyMV(out4, 0, tmpA, 0, vec, 0)
                val x = out4[0] / out4[3]; val y = out4[1] / out4[3]
                bounds[0] = minOf(bounds[0], x); bounds[1] = maxOf(bounds[1], x)
                bounds[2] = minOf(bounds[2], y); bounds[3] = maxOf(bounds[3], y)
            }
        }
        var lo = 4f; var hi = 400f
        repeat(48) {
            val mid = (lo + hi) / 2
            measure(mid)
            val fits = (bounds[1] - bounds[0]) <= 2f * rhx * FIT && (bounds[3] - bounds[2]) <= 2f * rhy * FIT
            if (fits) hi = mid else lo = mid
        }
        val dist = hi
        measure(dist)
        val sx = rcx - (bounds[0] + bounds[1]) / 2f
        val sy = rcy - (bounds[2] + bounds[3]) / 2f
        boardBaseShiftY = sy
        val drop = worldT * (bounds[3] - bounds[2] + 1.2f) // off the bottom of the screen
        val shift = FloatArray(16).also { Matrix.setIdentityM(it, 0); it[12] = sx; it[13] = sy - drop }
        Matrix.multiplyMM(boardProj, 0, shift, 0, base, 0)
        Matrix.setLookAtM(boardView, 0, 0f, 0f, dist, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(boardVP, 0, boardProj, 0, boardView, 0)
        boardCam[0] = 0f; boardCam[1] = 0f; boardCam[2] = dist

        val lv = FloatArray(16)
        val lp = FloatArray(16)
        Matrix.setLookAtM(lv, 0, boardLight[0] * 20f, boardLight[1] * 20f, boardLight[2] * 20f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.orthoM(lp, 0, -6.6f, 6.6f, -6.6f, 6.6f, 8f, 34f)
        Matrix.multiplyMM(boardLightVP, 0, lp, 0, lv, 0)
    }

    private fun boardCorners(): List<FloatArray> {
        val x = SLAB_W / 2; val y = SLAB_H / 2
        val top = TILE_Z + TILE_DEPTH / 2 + 0.05f
        return listOf(
            floatArrayOf(-x, -y, 0f), floatArrayOf(x, -y, 0f), floatArrayOf(-x, y, 0f), floatArrayOf(x, y, 0f),
            floatArrayOf(-x, -y, top), floatArrayOf(x, -y, top), floatArrayOf(-x, y, top), floatArrayOf(x, y, top),
            floatArrayOf(-x, -y, -SLAB_DEPTH), floatArrayOf(x, -y, -SLAB_DEPTH),
        )
    }

    private fun layoutBackgroundCamera() {
        val aspect = width.toFloat() / height
        val fov = 44f
        val tanV = tan(Math.toRadians(fov / 2.0)).toFloat()
        val tanH = tanV * aspect
        // Island spans about the screen width; it sits behind the upper half of the board.
        val w = worldT
        val playDist = (IslandBuilder.RADIUS * 1.02f) / max(0.12f, tanH)
        val worldDist = (IslandBuilder.RADIUS * 1.18f) / max(0.12f, tanH)
        val dist = playDist + (worldDist - playDist) * w
        val el = Math.toRadians(8.5 + 13.0 * w).toFloat()
        val tx = 0f; val ty = 0.9f - 0.3f * w; val tz = 0f
        bgCam[0] = tx; bgCam[1] = ty + dist * sin(el); bgCam[2] = tz + dist * cos(el)
        Matrix.setLookAtM(bgView, 0, bgCam[0], bgCam[1], bgCam[2], tx, ty, tz, 0f, 1f, 0f)
        val base = FloatArray(16)
        Matrix.perspectiveM(base, 0, fov, aspect, 0.5f, 400f)
        val shift = FloatArray(16).also { Matrix.setIdentityM(it, 0); it[13] = ISLAND_NDC_Y + (WORLD_NDC_Y - ISLAND_NDC_Y) * w }
        Matrix.multiplyMM(bgProj, 0, shift, 0, base, 0)
        Matrix.multiplyMM(bgVP, 0, bgProj, 0, bgView, 0)

        // Horizon: a far point straight ahead at eye height.
        horizonNdcY = projectY(bgVP, bgCam[0], bgCam[1], bgCam[2] - 2000f)
        // Sun glow, behind and to the side of the island.
        val sun = normalize(floatArrayOf(-0.5f, 0.16f, -1f))
        vec[0] = bgCam[0] + sun[0] * 300f; vec[1] = bgCam[1] + sun[1] * 300f; vec[2] = bgCam[2] + sun[2] * 300f; vec[3] = 1f
        Matrix.multiplyMV(out4, 0, bgVP, 0, vec, 0)
        sunNdc[0] = out4[0] / out4[3]; sunNdc[1] = out4[1] / out4[3]

        val lv = FloatArray(16)
        val lp = FloatArray(16)
        Matrix.setLookAtM(lv, 0, islandLight[0] * 30f, islandLight[1] * 30f, islandLight[2] * 30f, 0f, 0.5f, 0f, 0f, 1f, 0f)
        Matrix.orthoM(lp, 0, -9f, 9f, -9f, 9f, 5f, 60f)
        Matrix.multiplyMM(islandLightVP, 0, lp, 0, lv, 0)
    }

    private fun projectY(vp: FloatArray, x: Float, y: Float, z: Float): Float {
        vec[0] = x; vec[1] = y; vec[2] = z; vec[3] = 1f
        Matrix.multiplyMV(out4, 0, vp, 0, vec, 0)
        return out4[1] / out4[3]
    }

    // ------------------------------------------------------------------ board drawing

    private fun computeTiles(now: Long) {
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            val i = r * COLS + c
            val pose = tilePoses[i]
            board.pose(r, c, now, pose)
            if (!pose.visible) continue
            val m = tileModels[i]
            val x = ((COLS - 1) / 2f - c) * PITCH + pose.dx
            val y = ((ROWS - 1) / 2f - r) * PITCH + pose.dy
            System.arraycopy(boardModel, 0, m, 0, 16)
            Matrix.translateM(m, 0, x, y, TILE_Z + pose.dz)
            if (pose.flipDeg != 0f) Matrix.rotateM(m, 0, pose.flipDeg, 1f, 0f, 0f)
            if (pose.scale != 1f) Matrix.scaleM(m, 0, pose.scale, pose.scale, pose.scale)
        }
    }

    private fun bindShadowUniforms(p: ShaderProgram, on: Boolean, lightVP: FloatArray) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (on) shadow!!.texture else noShadow.texture)
        p.set1i("uShadowMap", 1)
        p.set1f("uShadowOn", if (on) 1f else 0f)
        val size = if (on) shadow!!.size else 1
        p.set2f("uShadowTexel", 1f / size, 1f / size)
        p.set1f("uShadowBias", 0.0012f)
        p.setMat4("uLightViewProj", lightVP)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun setSceneLight(p: ShaderProgram, light: FloatArray, cam: FloatArray) {
        p.set3f("uLightDir", light)
        p.set3f("uSunColor", Palette.rgb(Palette.SUN))
        p.set3f("uCamPos", cam)
        p.set3f("uSkyColor", Palette.rgb(0xBFDFFF))
        p.set3f("uGroundColor", Palette.rgb(Palette.GROUND))
    }

    private fun drawSlab(now: Long, glass: Boolean, shadows: Boolean) {
        val p = progSlab
        p.use()
        p.setMat4("uModel", boardModel)
        p.setMat4("uViewProj", boardVP)
        setSceneLight(p, boardLight, boardCam)
        bindShadowUniforms(p, shadows, boardLightVP)
        p.set3f("uTint", Palette.rgb(Palette.SLAB))
        p.set3f("uSocketColor", Palette.rgb(Palette.SOCKET))
        p.set3f("uActiveColor", Palette.rgb(Palette.ACTIVE))
        p.set1f("uPitch", PITCH)
        p.set2f("uCellHalf", SOCKET_HALF, SOCKET_HALF)
        p.set1f("uCellRadius", 0.2f)
        p.set1f("uCols", COLS.toFloat())
        p.set1f("uRows", ROWS.toFloat())
        p.set1f("uActiveRow", board.activeRow.toFloat())
        val pulse = if (reduceMotion || !quality.ambient) 0.5f else 0.5f + 0.5f * sin((now - startTime) / 420f)
        p.set1f("uPulse", pulse)
        p.set1f("uUseBlur", if (glass) 1f else 0f)
        p.set2f("uScreenSize", width.toFloat(), height.toFloat())
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (glass) blurA!!.texture else atlas.textureId)
        p.set1i("uBlurTex", 0)
        Matrix.setIdentityM(tmpA, 0)
        Matrix.translateM(tmpA, 0, 0f, 0f, -SLAB_DEPTH / 2f)
        Matrix.multiplyMM(tmpB, 0, boardModel, 0, tmpA, 0)
        p.setMat4("uModel", tmpB)
        slabMesh.draw()
    }

    private fun drawTiles(shadows: Boolean) {
        val p = progTile
        p.use()
        p.setMat4("uViewProj", boardVP)
        setSceneLight(p, boardLight, boardCam)
        bindShadowUniforms(p, shadows, boardLightVP)
        p.set3f("uHalf", TILE / 2, TILE / 2, TILE_DEPTH / 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas.textureId)
        p.set1i("uAtlas", 0)
        val ivory = Palette.rgb(Palette.TILE_IVORY)
        val ink = Palette.rgb(Palette.TILE_INK)
        val white = Palette.rgb(Palette.RESULT_INK)
        for (i in tileModels.indices) {
            val pose = tilePoses[i]
            if (!pose.visible) continue
            p.setMat4("uModel", tileModels[i])
            atlas.cell(pose.letter, cell)
            p.set3f("uFrontColor", ivory)
            p.set3f("uFrontInk", ink)
            p.set4f("uFrontCell", cell[0], cell[1], cell[2], cell[3])
            val back = Palette.rgb(Palette.forState(if (pose.result == LetterState.UNKNOWN) LetterState.ABSENT else pose.result))
            p.set3f("uBackColor", back)
            p.set3f("uBackInk", white)
            p.set4f("uBackCell", cell[0], cell[1], cell[2], cell[3])
            p.set1f("uGlow", pose.glow)
            tileMesh.draw()
        }
    }

    // ------------------------------------------------------------------ world drawing

    private fun islandMatrix(time: Float, ambient: Boolean): FloatArray {
        val m = FloatArray(16)
        Matrix.setIdentityM(m, 0)
        val angle = if (ambient) 25f + time * 3.2f else 25f
        Matrix.rotateM(m, 0, angle, 0f, 1f, 0f)
        return m
    }

    private fun dynamicParts(island: IslandModel, base: FloatArray, time: Float, draw: (Mesh, FloatArray) -> Unit) {
        island.sails?.let { mesh ->
            val m = FloatArray(16)
            System.arraycopy(base, 0, m, 0, 16)
            Matrix.translateM(m, 0, island.sailsPivot[0], island.sailsPivot[1], island.sailsPivot[2])
            Matrix.rotateM(m, 0, 15f, 0f, 1f, 0f)
            Matrix.rotateM(m, 0, time * 38f, 0f, 0f, 1f)
            draw(mesh, m)
        }
        island.boat?.let { mesh ->
            val m = FloatArray(16)
            System.arraycopy(base, 0, m, 0, 16)
            Matrix.translateM(m, 0, island.boatPos[0], 0.02f + 0.05f * sin(time * 1.3f), island.boatPos[2])
            Matrix.rotateM(m, 0, -35f, 0f, 1f, 0f)
            Matrix.rotateM(m, 0, 4f * sin(time * 0.9f), 1f, 0f, 0f)
            draw(mesh, m)
        }
        island.balloon?.let { mesh ->
            val m = FloatArray(16)
            System.arraycopy(base, 0, m, 0, 16)
            Matrix.translateM(m, 0, island.balloonPos[0], island.balloonPos[1] + 0.25f * sin(time * 0.6f), island.balloonPos[2])
            draw(mesh, m)
        }
    }

    private fun beginDepthPass() {
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(2.0f, 4.0f)
        GLES30.glCullFace(GLES30.GL_FRONT)
    }

    private fun endDepthPass() {
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glCullFace(GLES30.GL_BACK)
    }

    private fun drawIslandDepth(model: FloatArray, time: Float) {
        val isl = island ?: return
        GLES30.glDisable(GLES30.GL_CULL_FACE) // leaves and sails are thin
        progDepth.use()
        progDepth.setMat4("uLightViewProj", islandLightVP)
        progDepth.setMat4("uModel", model)
        isl.ground.draw()
        dynamicParts(isl, model, time) { mesh, m -> progDepth.setMat4("uModel", m); mesh.draw() }
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun drawWorld(islandModel: FloatArray, time: Float, withShadows: Boolean) {
        val aspect = width.toFloat() / height
        // Sky
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        progSky.use()
        progSky.set3f("uZenith", Palette.rgb(Palette.SKY_ZENITH))
        progSky.set3f("uHorizon", Palette.rgb(Palette.SKY_HORIZON))
        progSky.set3f("uSunColor", Palette.rgb(Palette.SUN))
        progSky.set2f("uSunNdc", sunNdc[0], sunNdc[1])
        progSky.set1f("uHorizonY", horizonNdcY)
        progSky.set1f("uAspect", aspect)
        quadMesh.draw()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)

        // Sea
        progSea.use()
        progSea.setMat4("uViewProj", bgVP)
        bindShadowUniforms(progSea, withShadows, islandLightVP)
        progSea.set1f("uTime", time)
        progSea.set3f("uDeep", Palette.rgb(Palette.SEA_DEEP))
        progSea.set3f("uShallow", Palette.rgb(Palette.SEA_SHALLOW))
        progSea.set3f("uFoam", Palette.rgb(Palette.FOAM))
        progSea.set3f("uHorizon", Palette.rgb(Palette.SKY_HORIZON))
        progSea.set3f("uZenith", Palette.rgb(Palette.SKY_ZENITH))
        progSea.set3f("uSunColor", Palette.rgb(Palette.SUN))
        progSea.set3f("uLightDir", normalize(floatArrayOf(-0.5f, 0.16f, -1f)))
        progSea.set3f("uCamPos", bgCam)
        progSea.set1f("uIslandRadius", IslandBuilder.RADIUS)
        progSea.set1f("uFogStart", 45f)
        progSea.set1f("uFogEnd", 165f)
        seaMesh.draw()

        // Island
        val isl = island ?: return
        progIsland.use()
        progIsland.setMat4("uViewProj", bgVP)
        progIsland.set1f("uTime", time)
        progIsland.set1f("uWind", if (quality.ambient && !reduceMotion) 1f else 0f)
        setSceneLight(progIsland, islandLight, bgCam)
        bindShadowUniforms(progIsland, withShadows, islandLightVP)
        progIsland.set3f("uHorizon", Palette.rgb(Palette.SKY_HORIZON))
        progIsland.set1f("uFogStart", 60f)
        progIsland.set1f("uFogEnd", 220f)
        progIsland.set1f("uEmissive", 0f)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        progIsland.setMat4("uModel", islandModel)
        isl.ground.draw()
        dynamicParts(isl, islandModel, time) { mesh, m -> progIsland.setMat4("uModel", m); mesh.draw() }
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun blur() {
        val low = bgLow ?: return
        val a = blurA ?: return
        val b = blurB ?: return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        progBlur.use()
        progBlur.set1i("uTex", 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        // two separable passes, twice, for a soft frosted look
        var src = low.texture
        repeat(2) {
            b.bind()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, src)
            progBlur.set2f("uStep", 1.6f / b.width, 0f)
            quadMesh.draw()
            a.bind()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, b.texture)
            progBlur.set2f("uStep", 0f, 1.6f / a.height)
            quadMesh.draw()
            src = a.texture
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    companion object {
        const val ROWS = 8
        const val COLS = 5
        const val TILE = 1.0f
        const val GAP = 0.16f
        const val PITCH = TILE + GAP
        const val TILE_DEPTH = 0.34f
        const val TILE_BEVEL = 0.15f
        const val TILE_Z = TILE_DEPTH / 2f + 0.03f
        const val SOCKET_HALF = 0.535f
        const val MARGIN = 0.36f
        val SLAB_W = COLS * PITCH - GAP + 2 * MARGIN
        val SLAB_H = ROWS * PITCH - GAP + 2 * MARGIN
        const val SLAB_DEPTH = 0.42f
        const val TILT_DEG = 11f
        const val BOARD_FOV = 26f
        const val FIT = 0.97f
        const val ISLAND_NDC_Y = 0.5f
        const val WORLD_NDC_Y = 0.42f
        const val WORLD_MS = 900L
        const val SHADOW_SIZE = 1024

        fun normalize(v: FloatArray): FloatArray {
            val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            return floatArrayOf(v[0] / l, v[1] / l, v[2] / l)
        }
    }
}
