package com.khumasi.game

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.khumasi.game.ai.AiHelper
import com.khumasi.game.ai.GeminiClient
import com.khumasi.game.ai.KeyVault
import com.khumasi.game.audio.SoundFx
import com.khumasi.game.core.ArabicNormalizer
import com.khumasi.game.core.Daily
import com.khumasi.game.core.FriendCode
import com.khumasi.game.core.LetterState
import com.khumasi.game.core.Reward
import com.khumasi.game.core.Rewards
import com.khumasi.game.core.GameState
import com.khumasi.game.core.GameStatus
import com.khumasi.game.core.GuessList
import com.khumasi.game.core.Progress
import com.khumasi.game.core.RejectReason
import com.khumasi.game.core.ShareText
import com.khumasi.game.core.Solution
import com.khumasi.game.core.SolutionPicker
import com.khumasi.game.core.Stats
import com.khumasi.game.core.SubmitResult
import com.khumasi.game.data.Store
import com.khumasi.game.data.WordRepository
import com.khumasi.game.render.BoardAnimator
import com.khumasi.game.render.GameRenderer
import com.khumasi.game.render.GameSurfaceView
import com.khumasi.game.render.Palette
import com.khumasi.game.render.Themes
import com.khumasi.game.ui.TextScale
import com.khumasi.game.ui.BoardView2D
import com.khumasi.game.ui.Fonts
import com.khumasi.game.ui.IconView
import com.khumasi.game.ui.KeyboardView
import com.khumasi.game.ui.Panels
import com.khumasi.game.ui.SheetHost
import com.khumasi.game.ui.UiColors
import com.khumasi.game.ui.dp
import com.khumasi.game.ui.dpi
import com.khumasi.game.ui.label
import com.khumasi.game.ui.opaque
import com.khumasi.game.ui.rounded
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : ComponentActivity(), KeyboardView.Listener, GameRenderer.Listener {

    private lateinit var store: Store
    private lateinit var repo: WordRepository
    private lateinit var fonts: Fonts
    private lateinit var panels: Panels

    private lateinit var root: FrameLayout
    private var surface: GameSurfaceView? = null
    private lateinit var renderer: GameRenderer
    private lateinit var board2d: BoardView2D
    private lateinit var header: LinearLayout
    private lateinit var keyboardPanel: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var resultBar: LinearLayout
    private lateinit var toast: TextView
    private lateinit var sheets: SheetHost
    private lateinit var skipLayer: View
    private lateinit var timerView: TextView
    private lateinit var modeView: TextView
    private lateinit var nextBtn: TextView
    private lateinit var sfx: SoundFx
    private lateinit var ai: AiHelper

    private lateinit var solutions: List<Solution>
    @Volatile private var guesses: GuessList? = null
    private lateinit var state: GameState
    private lateinit var progress: Progress

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var pendingResult: Runnable? = null
    private var pendingLevelUp = false
    private var pendingRewards: List<Reward> = emptyList()
    private var refreshingRewards = false
    private var inputLockedUntil = 0L
    private var resumedAt = 0L
    private var lastRegion = floatArrayOf(0f, 0f, 0f, 0f)
    private var reduceMotion = false
    private var lightMode = false
    private var insetTop = 0
    private var insetBottom = 0
    private var demo = false

    // Backup files go through the system file picker, so no storage permission is needed.
    private val saveBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.exportBackup().toByteArray(Charsets.UTF_8)) }
            getSharedPreferences("khumasi_meta", MODE_PRIVATE).edit().putLong("last_backup", System.currentTimeMillis()).apply()
            showToast("حُفظت النسخة الاحتياطية ✓")
        } catch (t: Throwable) {
            Log.e(TAG, "Backup failed", t)
            showToast("تعذّر حفظ الملف")
        }
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().take(2_000_000).toByteArray().toString(Charsets.UTF_8) }
        } catch (t: Throwable) { null }
        confirmRestore(text)
    }

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
        val createdAt = SystemClock.uptimeMillis()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        store = Store(this)
        repo = WordRepository(this)
        fonts = Fonts(assets)
        panels = Panels(this, fonts)
        demo = BuildConfig.DEBUG && intent?.extras?.keySet()?.any { it.startsWith("demo_") } == true
        reduceMotion = store.reduceMotion || (demo && intent.getBooleanExtra("demo_reduce", false))
        lightMode = store.lightMode || (demo && intent.getBooleanExtra("demo_light", false))
        TextScale.factor = TextScale.forSetting(store.textSize)
        sfx = SoundFx(this).apply { soundOn = store.sound; musicOn = store.music }
        ai = AiHelper(this, KeyVault(this))

        solutions = repo.loadSolutions()
        progress = store.loadProgress()
        panels.todayDaily = today()
        state = startingState()
        if (demo) applyDemo()

        buildUi()
        startLoadingGuesses()
        syncBoard(animate = false)
        updateKeyboard()
        updateRoundUi()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sheets.isShowing) sheets.close() else finish()
            }
        })

        Log.i(TAG, "UI ready in ${SystemClock.uptimeMillis() - createdAt} ms")
        when {
            demo -> scheduleDemoActions()
            !store.seenRules -> handler.postDelayed({ showTutorial(); store.seenRules = true }, 500)
            state.isOver -> handler.postDelayed({ showResult(levelUp = false) }, 350)
        }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /** The round to show at launch: the mode last on screen, if it is still valid today. */
    private fun startingState(): GameState {
        when (store.activeMode) {
            GameState.MODE_DAILY -> store.loadGame(GameState.MODE_DAILY)?.takeIf { it.ref == today().toString() }?.let { return it }
            GameState.MODE_FRIEND -> store.loadGame(GameState.MODE_FRIEND)?.let { return it }
        }
        return store.loadGame(GameState.MODE_CLASSIC)?.takeIf { it.mode == GameState.MODE_CLASSIC }
            ?: newRound().also { store.saveRound(it, progress) }
    }

    private fun buildUi() {
        // Same colors as the 3D sky and sea, shown for the moment before the first 3D frame.
        root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(opaque(Palette.SKY_ZENITH), opaque(Palette.SKY_HORIZON), opaque(Palette.SEA_SHALLOW), opaque(Palette.SEA_DEEP)),
            )
        }

        board2d = BoardView2D(this, fonts).apply { visibility = View.GONE; colorBlind = store.colorBlind }
        renderer = GameRenderer(fonts.heavy, this)
        renderer.reduceMotion = reduceMotion
        renderer.quality = if (lightMode) GameRenderer.Quality(shadows = false, glass = false, ambient = false)
        else GameRenderer.Quality(shadows = true, glass = true, ambient = true)
        applyLooks()
        val gl = GameSurfaceView(this, wantMsaa = !lightMode).also { it.attach(renderer) }
        surface = gl
        root.addView(gl, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(board2d, FrameLayout.LayoutParams(MATCH, MATCH))

        // Header: help (right), title, statistics, settings (left).
        header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x8810182A.toInt(), 0x0010182A))
            setPadding(dpi(8f), 0, dpi(8f), dpi(6f))
        }
        val help = IconView(this, IconView.Kind.HELP, "كيف تلعب").apply { setOnClickListener { showRules() } }
        val title = label("خماسي", 27f, fonts.heavy, Color.WHITE).apply {
            setShadowLayer(dp(6f), 0f, dp(1.5f), 0x99000000.toInt())
        }
        timerView = label("", 13f, fonts.bold, Color.WHITE).apply {
            setShadowLayer(dp(4f), 0f, dp(1f), 0x99000000.toInt())
            visibility = View.GONE
        }
        modeView = label("", 13f, fonts.bold, Color.WHITE).apply {
            setShadowLayer(dp(4f), 0f, dp(1f), 0x99000000.toInt())
            alpha = 0.92f
        }
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(title)
            addView(modeView)
            addView(timerView)
            contentDescription = "أنماط اللعب: تحدي اليوم، اللعب الحر، تحدي صديق"
            isClickable = true
            setOnClickListener { showModes() }
        }
        val islandBtn = IconView(this, IconView.Kind.ISLAND, "الجزيرة والمكافآت").apply { setOnClickListener { showRewards() } }
        val statsBtn = IconView(this, IconView.Kind.STATS, "الإحصاءات").apply { setOnClickListener { showStats() } }
        val hintBtn = IconView(this, IconView.Kind.HINT, "تلميح").apply { setOnClickListener { showHints() } }
        val settingsBtn = IconView(this, IconView.Kind.SETTINGS, "الإعدادات").apply { setOnClickListener { showSettings() } }
        header.addView(help, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
        header.addView(islandBtn, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
        header.addView(titleBox, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(hintBtn, LinearLayout.LayoutParams(dpi(46f), dpi(46f)))
        header.addView(statsBtn, LinearLayout.LayoutParams(dpi(46f), dpi(46f)))
        header.addView(settingsBtn, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
        root.addView(header, FrameLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // Keyboard panel at the bottom.
        keyboardPanel = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(UiColors.PANEL)
                val r = dp(24f)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
        }
        keyboard = KeyboardView(this, fonts).apply {
            listener = this@MainActivity
            haptics = store.haptics
            colorBlind = store.colorBlind
            onKeySound = { isDelete -> sfx.play(if (isDelete) SoundFx.Fx.DELETE else SoundFx.Fx.TAP, 0.45f) }
        }
        keyboardPanel.addView(keyboard, FrameLayout.LayoutParams(MATCH, MATCH))
        resultBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dpi(20f), 0, dpi(20f), 0)
            nextBtn = panels.button("كلمة جديدة", primary = true) { startNewRound() }
            addView(nextBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
            addView(panels.button("النتيجة", primary = false) { showResult(levelUp = false) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(10f) })
        }
        keyboardPanel.addView(resultBar, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(keyboardPanel, FrameLayout.LayoutParams(MATCH, dpi(240f), Gravity.BOTTOM))
        // The letter preview bubble is drawn above the keyboard's own bounds.
        keyboardPanel.clipChildren = false
        keyboardPanel.clipToPadding = false
        root.clipChildren = false

        toast = label("", 17f, fonts.bold, UiColors.TOAST_TEXT).apply {
            background = rounded(UiColors.TOAST, dp(14f))
            setPadding(dpi(18f), dpi(10f), dpi(18f), dpi(10f))
            alpha = 0f
            visibility = View.INVISIBLE
            elevation = dp(8f)
        }
        root.addView(toast, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        skipLayer = View(this).apply {
            visibility = View.GONE
            setOnClickListener { skipToResult() }
        }
        root.addView(skipLayer, FrameLayout.LayoutParams(MATCH, MATCH))

        sheets = SheetHost(this, fonts).apply { reduceMotion = this@MainActivity.reduceMotion }
        root.addView(sheets, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val gestures = insets.getInsets(WindowInsetsCompat.Type.mandatorySystemGestures())
            insetTop = max(cutout.top, bars.top)
            insetBottom = maxOf(cutout.bottom, bars.bottom, gestures.bottom)
            val side = max(cutout.left, cutout.right)
            header.setPadding(dpi(8f) + side, insetTop + dpi(4f), dpi(8f) + side, dpi(6f))
            (toast.layoutParams as FrameLayout.LayoutParams).topMargin = insetTop + dpi(62f)
            sheets.bottomInset = insetBottom
            // Keep text fields in sheets above the on-screen keyboard.
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            sheets.setPadding(0, 0, 0, max(0, ime - insetBottom))
            layoutKeyboard()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRegion() }
        hideSystemBars()
        applyLooks() // again now that the keyboard, flat board and timer exist
    }

    private fun layoutKeyboard() {
        val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val kh = keyboard.preferredHeight(w, (h * 0.36f).toInt())
        keyboard.setPadding(0, 0, 0, 0)
        keyboardPanel.setPadding(0, 0, 0, insetBottom)
        keyboardPanel.layoutParams = (keyboardPanel.layoutParams as FrameLayout.LayoutParams).apply { height = kh + insetBottom }
        keyboardPanel.requestLayout()
    }

    /** Tells the renderer where the board may draw: between the header and the keyboard. */
    private fun updateRegion() {
        if (root.width == 0) return
        val top = (header.bottom - dpi(4f)).toFloat()
        val bottom = (keyboardPanel.top - dpi(6f)).toFloat()
        val left = dp(10f)
        val right = root.width - dp(10f)
        val r = floatArrayOf(left, top, right, bottom)
        if (r.contentEquals(lastRegion)) return
        lastRegion = r
        surface?.send { setBoardRegion(left, top, right, bottom) }
        board2d.setRegion(left, top, right, bottom)
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun startLoadingGuesses() {
        loader.execute {
            val t0 = SystemClock.uptimeMillis()
            val list = try { repo.loadGuesses(5, solutions) } catch (t: Throwable) { Log.e(TAG, "Dictionary failed", t); null }
            Log.i(TAG, "Loaded ${list?.size} guesses in ${SystemClock.uptimeMillis() - t0} ms")
            guesses = list
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        surface?.onResume()
        resumedAt = SystemClock.uptimeMillis()
        sfx.onResume()
        handler.removeCallbacks(tickTimer)
        handler.post(tickTimer)
    }

    override fun onPause() {
        accumulatePlayTime()
        store.saveGame(state)
        sfx.onPause()
        handler.removeCallbacks(tickTimer)
        surface?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
        sfx.release()
        super.onDestroy()
    }

    private fun accumulatePlayTime() {
        val now = SystemClock.uptimeMillis()
        if (resumedAt > 0) state = state.addPlayTime(now - resumedAt)
        resumedAt = now
    }

    // ------------------------------------------------------------------ renderer callbacks (GL thread)

    override fun onRendererReady() {
        runOnUiThread { updateRegion() }
    }

    override fun onRendererFailed(error: Throwable) {
        runOnUiThread {
            Log.e(TAG, "Falling back to the flat board", error)
            surface?.visibility = View.GONE
            board2d.visibility = View.VISIBLE
            board2d.show(state)
            showToast("يعمل العرض المسطّح لأن الجهاز لم يدعم العرض ثلاثي الأبعاد")
        }
    }

    // ------------------------------------------------------------------ keyboard

    override fun onLetter(ch: Char) {
        if (!canType()) return
        val col = state.input.length
        val next = state.type(ch)
        if (next === state) return
        state = next
        val row = state.currentRow
        surface?.send { board.typeLetter(row, col, ch, SystemClock.uptimeMillis()) }
        board2d.show(state)
        store.saveGame(state)
    }

    override fun onDelete() {
        if (!canType() || state.input.isEmpty()) return
        val col = state.input.length - 1
        state = state.deleteLetter()
        val row = state.currentRow
        surface?.send { board.deleteLetter(row, col, SystemClock.uptimeMillis()) }
        board2d.show(state)
        store.saveGame(state)
    }

    override fun onEnter() {
        if (!canType()) return
        val list = guesses
        if (list == null && state.input.length == state.length && state.input != state.answer) {
            showToast("لحظة… يجري تجهيز القاموس")
            return
        }
        val levelBefore = progress.islandLevel
        when (val result = state.submit { word -> list?.contains(word) == true }) {
            is SubmitResult.Rejected -> reject(result.reason, result.detail)
            is SubmitResult.Accepted -> {
                accumulatePlayTime()
                state = result.state
                val row = state.currentRow - 1
                val word = state.guesses.last()
                val reveal = BoardAnimator.revealDuration(state.length, reduceMotion)
                val now = SystemClock.uptimeMillis()
                inputLockedUntil = now + reveal
                val over = state.isOver
                val won = state.status == GameStatus.WON
                surface?.send {
                    val t = SystemClock.uptimeMillis()
                    val end = board.reveal(row, word, result.evaluation, t)
                    board.activeRow = if (over) -1 else row + 1
                    if (won) {
                        board.bounce(row, end + 60)
                        celebrate(end + 60)
                    }
                }
                playRevealSounds(result.evaluation, reveal, won, state.status == GameStatus.LOST)
                board2d.show(state)
                handler.postDelayed({ updateKeyboard() }, reveal)
                if (state.isOver) onRoundOver(levelBefore, reveal) else store.saveGame(state)
            }
        }
    }

    private fun canType(): Boolean =
        !state.isOver && !sheets.isShowing && SystemClock.uptimeMillis() >= inputLockedUntil

    private fun reject(reason: RejectReason, detail: String? = null) {
        val msg = when (reason) {
            RejectReason.TOO_SHORT -> "أكمل خمسة أحرف"
            RejectReason.NOT_IN_LIST -> "ليست في قائمة الكلمات"
            RejectReason.ALREADY_TRIED -> "جرّبت هذه الكلمة من قبل"
            RejectReason.HARD_MODE -> detail ?: "الوضع الصعب: استخدم الحروف المكتشفة"
            RejectReason.GAME_OVER -> return
        }
        showToast(msg)
        sfx.play(SoundFx.Fx.INVALID, 0.5f)
        val row = state.currentRow
        surface?.send { board.shake(row, SystemClock.uptimeMillis()) }
        if (store.haptics) {
            keyboard.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
            )
        }
    }

    // ------------------------------------------------------------------ round flow

    private fun onRoundOver(levelBefore: Int, revealMs: Long) {
        progress = progress.recordRound(state)
        store.saveRound(state, progress)
        val levelUp = progress.islandLevel > levelBefore
        pendingRewards = Rewards.newlyUnlocked(levelBefore, progress.islandLevel)
        val won = state.status == GameStatus.WON
        if (won && store.haptics) {
            handler.postDelayed({
                keyboard.performHapticFeedback(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
                )
            }, revealMs)
        }
        if (levelUp) {
            val level = progress.islandLevel
            surface?.send { setIslandLevel(level) }
        }
        pendingLevelUp = levelUp
        val celebration = if (won && !reduceMotion) 1100L else 500L
        val r = Runnable { skipToResult() }
        pendingResult = r
        skipLayer.visibility = View.VISIBLE
        handler.postDelayed(r, revealMs + celebration)
    }

    /** Win/lose effects can be skipped with a tap. */
    private fun skipToResult() {
        pendingResult?.let { handler.removeCallbacks(it) }
        pendingResult = null
        skipLayer.visibility = View.GONE
        updateKeyboard()
        updateRoundUi()
        showResult(pendingLevelUp)
        pendingLevelUp = false
        pendingRewards = emptyList()
    }

    private fun startNewRound() {
        if (state.mode != GameState.MODE_CLASSIC) { switchTo(GameState.MODE_CLASSIC); return }
        sheets.close()
        surface?.send { setWorldView(false) }
        state = newRound()
        store.saveRound(state, progress)
        syncBoard(animate = true)
        updateKeyboard()
        updateRoundUi()
    }

    // ------------------------------------------------------------------ play modes

    private fun today(): Int {
        val now = System.currentTimeMillis()
        return Daily.number(Daily.localEpochDay(now, java.util.TimeZone.getDefault().getOffset(now)))
    }

    private fun nextDailyIn(): String {
        val now = System.currentTimeMillis()
        val min = (Daily.msUntilNext(now, java.util.TimeZone.getDefault().getOffset(now)) / 60_000).toInt()
        val h = min / 60; val m = min % 60
        return if (h > 0) "${com.khumasi.game.ui.ar(h)} س ${com.khumasi.game.ui.ar(m)} د" else "${com.khumasi.game.ui.ar(maxOf(m, 1))} د"
    }

    private fun dailyRound(day: Int): GameState =
        store.loadGame(GameState.MODE_DAILY)?.takeIf { it.ref == day.toString() }
            ?: GameState(
                answer = Daily.word(day, solutions).word, mode = GameState.MODE_DAILY, ref = day.toString(),
                startedAtEpochMs = System.currentTimeMillis(), hardMode = store.hardMode,
            )

    /** Puts the current round away (it stays saved) and shows the round for [mode]. */
    private fun switchTo(mode: String, friendRound: GameState? = null) {
        val next = when (mode) {
            GameState.MODE_DAILY -> {
                val day = today()
                if (progress.dailyDone(day) && store.loadGame(GameState.MODE_DAILY)?.ref != day.toString()) {
                    showToast("أنهيت تحدي اليوم. التالي بعد ${nextDailyIn()}"); return
                }
                dailyRound(day)
            }
            GameState.MODE_FRIEND -> friendRound ?: store.loadGame(GameState.MODE_FRIEND) ?: return
            else -> store.loadGame(GameState.MODE_CLASSIC)?.takeIf { !it.isOver } ?: newRound()
        }
        pendingResult?.let { handler.removeCallbacks(it) }
        pendingResult = null
        skipLayer.visibility = View.GONE
        accumulatePlayTime()
        store.saveGame(state)
        sheets.close()
        surface?.send { setWorldView(false) }
        state = next
        store.saveRound(state, progress)
        syncBoard(animate = true)
        updateKeyboard()
        updateRoundUi()
        if (state.isOver) handler.postDelayed({ showResult(levelUp = false) }, 450)
        else if (mode == GameState.MODE_DAILY) showToast("تحدي اليوم ${com.khumasi.game.ui.ar(today())} — بالتوفيق!")
        else if (mode == GameState.MODE_FRIEND && friendRound != null) showToast("تحدي صديق — خمّن كلمته!")
    }

    private fun updateModeLabel() {
        if (!::modeView.isInitialized) return
        val dailyOpen = !progress.dailyDone(today())
        modeView.text = when (state.mode) {
            GameState.MODE_DAILY -> "📅 تحدي اليوم ${com.khumasi.game.ui.ar(state.ref.toIntOrNull() ?: 0)} ▾"
            GameState.MODE_FRIEND -> "🤝 تحدي صديق ▾"
            else -> if (dailyOpen) "اللعب الحر · تحدي اليوم متاح ▾" else "اللعب الحر ▾"
        }
        nextBtn.text = if (state.mode == GameState.MODE_CLASSIC) "كلمة جديدة" else "اللعب الحر"
    }

    private fun showModes() {
        val day = today()
        val info = Panels.ModesInfo(
            current = state.mode,
            dailyNumber = day,
            dailyDone = progress.dailyDone(day),
            dailyStreak = progress.dailyStreak(day),
            nextDailyIn = nextDailyIn(),
            hasFriendRound = store.loadGame(GameState.MODE_FRIEND)?.let { !it.isOver } == true,
        )
        sheets.show("أنماط اللعب", panels.modes(info,
            onDaily = { if (state.mode == GameState.MODE_DAILY) { sheets.close(); if (state.isOver) handler.postDelayed({ showResult(false) }, 300) } else switchTo(GameState.MODE_DAILY) },
            onClassic = { switchTo(GameState.MODE_CLASSIC) },
            onFriendCreate = { showFriendCreate() },
            onFriendEnter = { showFriendEnter("") },
            onFriendResume = { switchTo(GameState.MODE_FRIEND) },
        ), maxHeightFraction = 0.8f)
    }

    private fun showFriendCreate() {
        sheets.show("تحدي صديق", panels.friendCreate(
            randomWord = { solutions[kotlin.random.Random.nextInt(solutions.size)].display },
            onCreate = { raw ->
                val word = ArabicNormalizer.normalize(raw.trim())
                val list = guesses
                when {
                    word.length != 5 || !word.all { ArabicNormalizer.isGameLetter(it) } -> "اكتب كلمة عربية من خمسة أحرف"
                    list == null -> "لحظة… يجري تجهيز القاموس"
                    word !in list && solutions.none { it.word == word } -> "هذه الكلمة ليست في قائمة الكلمات"
                    else -> { shareChallenge(FriendCode.encode(word)); null }
                }
            },
        ), maxHeightFraction = 0.8f)
    }

    private fun shareChallenge(code: String) {
        val text = "🎯 تحدّيتك في خماسي!\nخمّن كلمتي من خمسة أحرف في ثماني محاولات.\n\nالرمز: $code\n\n" +
            "للعب: افتح خماسي ← اضغط على العنوان ← تحدي صديق ← أدخل رمزًا، أو شارك هذه الرسالة إلى تطبيق خماسي."
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
            }, "إرسال التحدي"))
            sheets.close()
        } catch (t: Throwable) {
            showToast("لا يوجد تطبيق للمشاركة")
        }
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(CLIPBOARD_SERVICE) as? android.content.ClipboardManager ?: return null
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }

    private fun showFriendEnter(prefill: String) {
        sheets.show("أدخل رمز التحدي", panels.friendEnter(prefill,
            onPaste = { clipboardText()?.also { if (FriendCode.decode(it) == null) showToast("لا يوجد رمز صالح في الحافظة") } },
            onStart = { text -> startFriend(text) },
        ), maxHeightFraction = 0.8f)
    }

    /** Starts a friend challenge from text containing a code; returns an error message or null. */
    private fun startFriend(text: String): String? {
        val word = FriendCode.decode(text) ?: return "الرمز غير صحيح. تأكد أنه منسوخ كاملًا."
        val code = Regex("KH[A-Z2-9-]+", RegexOption.IGNORE_CASE).find(text)?.value?.uppercase() ?: word
        val existing = store.loadGame(GameState.MODE_FRIEND)?.takeIf { it.ref == code }
        switchTo(GameState.MODE_FRIEND, existing ?: GameState(
            answer = word, mode = GameState.MODE_FRIEND, ref = code,
            startedAtEpochMs = System.currentTimeMillis(), hardMode = store.hardMode,
        ))
        return null
    }

    /** A challenge shared to the app from a chat, or opened from a khumasi:// link. */
    private fun handleIncoming(i: Intent?) {
        val text = when (i?.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.dataString
            else -> null
        } ?: return
        i.action = null // handle once, not again after rotation or recreate
        if (FriendCode.decode(text) == null) { handler.postDelayed({ showToast("لم أجد رمز تحدٍّ في النص") }, 400); return }
        handler.postDelayed({ showFriendEnter(text) }, 500)
    }

    // ------------------------------------------------------------------ backup

    private fun exportBackup() {
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        try { saveBackup.launch("khumasi-backup-$date.txt") } catch (t: Throwable) { showToast("لا يتوفر مدير ملفات") }
    }

    private fun importBackup() {
        try { openBackup.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) } catch (t: Throwable) { showToast("لا يتوفر مدير ملفات") }
    }

    private fun confirmRestore(text: String?) {
        val b = text?.let { com.khumasi.game.core.Backup.decode(it) }
        val p = (b?.values?.get("progress") as? String)?.let { com.khumasi.game.core.SaveCodec.decodeProgress(it) }
        if (b == null || p == null) { showToast("هذا الملف ليس نسخة احتياطية من خماسي"); return }
        val date = if (b.createdMs > 0) java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale("ar")).format(java.util.Date(b.createdMs)) else "—"
        val msg = "نسخة بتاريخ $date: ${com.khumasi.game.ui.ar(p.totalWins)} فوزًا، جزيرة بالمستوى ${com.khumasi.game.ui.ar(p.islandLevel)}.\n" +
            "سيحل محل تقدمك الحالي على هذا الهاتف (${com.khumasi.game.ui.ar(progress.totalWins)} فوزًا). هل تريد المتابعة؟"
        sheets.show("استرجاع نسخة", panels.confirm(msg, "استرجاع", onYes = {
            if (store.importBackup(text)) {
                sheets.close()
                handler.postDelayed({ recreate() }, 200)
            } else showToast("تعذّر استرجاع النسخة")
        }, onNo = { sheets.close() }))
    }

    private fun newRound(): GameState {
        val reported = store.reportedWords
        val pool = solutions.filter { it.word !in reported }.ifEmpty { solutions }
        val pick = SolutionPicker.pick(pool, progress)
        progress = progress.rememberAnswer(pick.word, pool.size)
        return GameState(answer = pick.word, startedAtEpochMs = System.currentTimeMillis(), hardMode = store.hardMode)
    }

    private fun syncBoard(animate: Boolean) {
        val s = state
        val level = progress.islandLevel
        surface?.send {
            board.setAll(s.guesses, s.evaluations, s.input, if (s.isOver) -1 else s.currentRow)
            setIslandLevel(level)
            if (animate) markBusy(SystemClock.uptimeMillis() + 300)
        }
        board2d.show(s)
    }

    private fun updateKeyboard() = keyboard.setStates(state.keyboard)

    private fun updateRoundUi() {
        val over = state.isOver && pendingResult == null
        keyboard.visibility = if (over) View.INVISIBLE else View.VISIBLE
        resultBar.visibility = if (over) View.VISIBLE else View.GONE
        updateModeLabel()
    }

    // ------------------------------------------------------------------ sheets

    private fun solutionFor(word: String): Solution? = solutions.firstOrNull { it.word == word }

    private fun showResult(levelUp: Boolean) {
        if (!state.isOver) return
        // The board slides away so the island shows above the result.
        surface?.send { setWorldView(true) }
        if (levelUp) {
            surface?.send { popIsland(SystemClock.uptimeMillis() + 650) }
            handler.postDelayed({ sfx.play(SoundFx.Fx.LEVEL_UP, 0.6f) }, 650)
        }
        val content = panels.result(
            state, solutionFor(state.answer), progress, levelUp,
            onNext = { startNewRound() },
            onShare = { share() },
            onReport = { showReport() },
            newRewards = pendingRewards,
            onIsland = { showRewards() },
            onExplain = { showAiReply("شرح الكلمة", "") { ai.explain(solutionFor(state.answer), state.answer, it) } },
            onAnalyze = { showAiReply("تحليل جولتك", "الألوان محسوبة بدقة داخل اللعبة؛ Gemini يكتب الملاحظات فقط.") { ai.analyze(state, solutionFor(state.answer), it) } },
            nextLabel = if (state.mode == GameState.MODE_CLASSIC) "كلمة جديدة" else "اللعب الحر",
            banner = when (state.mode) {
                GameState.MODE_DAILY -> {
                    val st = progress.dailyStreak(today())
                    "📅 تحدي اليوم ${com.khumasi.game.ui.ar(state.ref.toIntOrNull() ?: 0)}" +
                        (if (st > 0) " · 🔥 سلسلة ${com.khumasi.game.ui.ar(st)}" else "") + " · التالي بعد ${nextDailyIn()}"
                }
                GameState.MODE_FRIEND -> "🤝 تحدي صديق — شارك نتيجتك معه"
                else -> null
            },
            stats = if (state.mode == GameState.MODE_DAILY) progress.daily.copy(currentStreak = progress.dailyStreak(today())) else progress.unassisted,
        )
        sheets.show(panels.resultTitle(state), content, dismissible = true, maxHeightFraction = 0.64f) {
            // Closing the result brings the board back so the player can look at their guesses.
            if (state.isOver) surface?.send { setWorldView(false) }
            updateRoundUi()
        }
    }

    private fun showRules() = sheets.show("كيف تلعب", panels.rules())

    private fun showStats() = sheets.show("الإحصاءات", panels.stats(progress))

    private fun showSettings() {
        val s = Panels.SettingsState(
            store.sound, store.music, store.haptics, store.colorBlind, store.textSize, store.showTimer,
            store.reduceMotion, store.lightMode, store.hardMode,
        )
        var needsRebuild = false
        val view = panels.settings(s, "\u2066" + BuildConfig.VERSION_NAME + "\u2069", onTutorial = { showTutorial() }) { st, change ->
            when (change) {
                Panels.Change.AUDIO -> {
                    store.sound = st.sound; store.music = st.music; store.haptics = st.haptics
                    sfx.soundOn = st.sound; sfx.musicOn = st.music
                    keyboard.haptics = st.haptics
                }
                Panels.Change.LOOK -> {
                    store.colorBlind = st.colorBlind; store.showTimer = st.showTimer
                    applyLooks()
                }
                Panels.Change.TEXT -> { store.textSize = st.textSize; needsRebuild = true }
                Panels.Change.MOTION -> {
                    store.reduceMotion = st.reduceMotion
                    reduceMotion = st.reduceMotion
                    renderer.reduceMotion = st.reduceMotion
                    sheets.reduceMotion = st.reduceMotion
                }
                // Anti-aliasing is chosen when the surface is created, so the screen is rebuilt.
                Panels.Change.LIGHT_MODE -> { store.lightMode = st.lightMode; needsRebuild = true }
                Panels.Change.GAMEPLAY -> {
                    store.hardMode = st.hardMode
                    showToast(if (st.hardMode) "الوضع الصعب يبدأ من الجولة التالية" else "يعود الوضع العادي من الجولة التالية")
                }
            }
            surface?.invalidateScene()
        }
        (view as LinearLayout).addView(panels.aiSettings(Panels.AiSettings(
            masked = ai.vault.masked(),
            model = ai.model,
            usedToday = ai.usedToday,
            limit = AiHelper.DAILY_LIMIT,
            onSave = { ai.vault.save(it) },
            onTest = { report ->
                ai.test { r ->
                    report(when (r) {
                        is GeminiClient.Result.Ok -> "يعمل ✓ (النموذج: ${r.model})"
                        is GeminiClient.Result.Failed -> "لم ينجح: ${r.message}"
                    })
                }
            },
            onClear = { ai.vault.clear() },
            onModel = { ai.model = it },
        )))
        val last = getSharedPreferences("khumasi_meta", MODE_PRIVATE).getLong("last_backup", 0L).takeIf { it > 0 }?.let {
            java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, java.util.Locale("ar")).format(java.util.Date(it))
        }
        view.addView(panels.backupSection(last, onExport = { exportBackup() }, onImport = { importBackup() }))
        sheets.show("الإعدادات", view) { if (needsRebuild) handler.postDelayed({ recreate() }, 150) }
    }

    /** Applies theme, color-blind and timer choices to the renderer and the 2D views. */
    private fun applyLooks() {
        val level = progress.islandLevel
        renderer.tileTheme = Themes.tile(Rewards.resolve(Reward.Kind.TILES, store.tileTheme, level).id)
        renderer.skyTheme = Themes.sky(Rewards.resolve(Reward.Kind.SKY, store.skyTheme, level).id)
        renderer.colorBlind = store.colorBlind
        if (::keyboard.isInitialized) keyboard.colorBlind = store.colorBlind
        if (::board2d.isInitialized) { board2d.colorBlind = store.colorBlind; board2d.show(state) }
        if (::timerView.isInitialized) timerView.visibility = if (store.showTimer) View.VISIBLE else View.GONE
        surface?.invalidateScene()
    }

    private fun showRewards() {
        surface?.send { setWorldView(true) }
        val view = panels.rewards(progress, store.tileTheme, store.skyTheme) { r ->
            if (r.kind == Reward.Kind.TILES) store.tileTheme = r.id else store.skyTheme = r.id
            applyLooks()
            sfx.play(SoundFx.Fx.CORRECT, 0.5f)
            refreshingRewards = true
            showRewards() // redraw the list with the new choice; the island stays in view
            refreshingRewards = false
        }
        sheets.show("الجزيرة والمكافآت", view, maxHeightFraction = 0.6f) {
            if (!refreshingRewards) surface?.send { setWorldView(false) }
        }
    }

    private fun showTutorial() = sheets.show("أهلًا بك في خماسي", panels.tutorial(reduceMotion) { sheets.close() }, maxHeightFraction = 0.75f)

    private val tickTimer = object : Runnable {
        override fun run() {
            if (store.showTimer) {
                val ms = state.playedMs + if (state.isOver) 0 else SystemClock.uptimeMillis() - resumedAt
                val sec = (ms / 1000).toInt()
                timerView.text = "${com.khumasi.game.ui.ar(sec / 60)}:${com.khumasi.game.ui.ar(sec % 60).padStart(2, '٠')}"
            }
            handler.postDelayed(this, 1000)
        }
    }

    // ------------------------------------------------------------------ hints and AI

    private fun showHints() {
        if (state.isOver) { showToast("التلميحات متاحة أثناء الجولة"); return }
        val sol = solutionFor(state.answer)
        val hints = (1..state.hintsUsed).map { ai.revealedHint(it, state, sol) }
        val (view, _) = panels.hintSheet(hints, state.hintsUsed < AiHelper.MAX_HINTS, state.usedHelp) { requestHint() }
        sheets.show("تلميح", view, maxHeightFraction = 0.6f)
    }

    private fun requestHint() {
        val level = state.hintsUsed + 1
        if (level > AiHelper.MAX_HINTS || state.isOver) return
        val reply = panels.replyView(if (ai.vault.hasKey) "Gemini يكتب تلميحًا لا يكشف الكلمة…" else "")
        sheets.show("التلميح ${com.khumasi.game.ui.ar(level)}", reply.root, maxHeightFraction = 0.6f)
        val answer = state.answer
        ai.hint(level, state, solutionFor(answer)) { r ->
            if (state.answer != answer || state.isOver) return@hint
            state = state.withHint()
            store.saveGame(state)
            reply.body.text = r.text
            reply.note.text = when {
                r.fromAi -> "✨ من Gemini"
                r.note != null -> "تلميح من اللعبة (${r.note})"
                else -> "تلميح من اللعبة"
            }
            sfx.play(SoundFx.Fx.PRESENT, 0.4f)
        }
    }

    private fun showAiReply(title: String, intro: String, request: ((AiHelper.Reply) -> Unit) -> Unit) {
        val reply = panels.replyView(intro)
        sheets.show(title, reply.root, maxHeightFraction = 0.64f) { if (state.isOver) surface?.send { setWorldView(false) }; updateRoundUi() }
        request { r ->
            reply.body.text = r.text
            reply.note.text = if (r.fromAi) "✨ من Gemini" else r.note.orEmpty()
        }
    }

    /** One sound per tile as it turns over, then the win or lose phrase. */
    private fun playRevealSounds(evaluation: List<LetterState>, revealMs: Long, won: Boolean, lost: Boolean) {
        val stagger = if (reduceMotion) 0L else BoardAnimator.FLIP_STAGGER_MS
        val firstAt = if (reduceMotion) 0L else BoardAnimator.FLIP_MS / 2
        for ((i, st) in evaluation.withIndex()) {
            val fx = when (st) {
                LetterState.CORRECT -> SoundFx.Fx.CORRECT
                LetterState.PRESENT -> SoundFx.Fx.PRESENT
                else -> SoundFx.Fx.ABSENT
            }
            handler.postDelayed({ sfx.play(fx, if (reduceMotion) 0.35f else 0.55f) }, firstAt + i * stagger)
        }
        if (won) handler.postDelayed({ sfx.play(SoundFx.Fx.WIN, 0.7f) }, revealMs + 120)
        if (lost) handler.postDelayed({ sfx.play(SoundFx.Fx.LOSE, 0.6f) }, revealMs + 120)
    }

    private fun showReport() {
        val display = solutionFor(state.answer)?.display ?: state.answer
        sheets.show("الإبلاغ عن كلمة", panels.report(display) { reason ->
            store.addReport(state.answer, reason)
            sheets.close()
            showToast("شكرًا لك، حُفظ البلاغ ولن تظهر الكلمة حلًّا مجددًا")
        })
    }

    private fun share() {
        val text = ShareText.build(state) + "\n‏خماسي — عالم الكلمات"
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            startActivity(Intent.createChooser(send, "مشاركة النتيجة"))
        } catch (t: Throwable) {
            showToast("لا يوجد تطبيق للمشاركة")
        }
    }

    // ------------------------------------------------------------------ messages

    private val hideToast = Runnable {
        toast.animate().alpha(0f).setDuration(220).withEndAction { toast.visibility = View.INVISIBLE }.start()
    }

    private fun showToast(msg: String) {
        handler.removeCallbacks(hideToast)
        toast.text = msg
        toast.visibility = View.VISIBLE
        toast.animate().cancel()
        toast.alpha = 1f
        if (!reduceMotion) {
            toast.translationY = -dp(8f)
            toast.animate().translationY(0f).setDuration(160).start()
        }
        handler.postDelayed(hideToast, 1700)
    }

    // ------------------------------------------------------------------ debug-only demo states (screenshots in CI)

    private fun applyDemo() {
        val i = intent
        store.seenRules = true
        if (i.hasExtra("demo_colorblind")) store.colorBlind = i.getBooleanExtra("demo_colorblind", false)
        i.getStringExtra("demo_sky")?.let { store.skyTheme = it }
        i.getStringExtra("demo_tiles")?.let { store.tileTheme = it }
        if (i.hasExtra("demo_hard")) store.hardMode = i.getBooleanExtra("demo_hard", false)
        if (i.hasExtra("demo_timer")) store.showTimer = i.getBooleanExtra("demo_timer", false)
        val wins = i.getIntExtra("demo_wins", -1)
        if (wins >= 0) progress = progress.copy(unassisted = Stats(played = wins + wins / 4, wins = wins, currentStreak = minOf(wins, 3), bestStreak = minOf(wins, 6)))
        val answer = i.getStringExtra("demo_answer")?.let { ArabicNormalizer.normalize(it) }
        if (answer != null && answer.length == 5) {
            var s = GameState(answer = answer, startedAtEpochMs = System.currentTimeMillis(), playedMs = 83_000, hardMode = store.hardMode)
            val words = i.getStringExtra("demo_guesses").orEmpty().split(',').map { ArabicNormalizer.normalize(it) }.filter { it.length == 5 }
            for (w in words) {
                s = w.fold(s) { acc, c -> acc.type(c) }
                s = (s.submit { true } as? SubmitResult.Accepted)?.state ?: s
            }
            i.getStringExtra("demo_input")?.let { inp -> s = ArabicNormalizer.normalize(inp).fold(s) { acc, c -> acc.type(c) } }
            if (s.isOver) progress = progress.recordRound(s)
            state = s
        }
        store.saveRound(state, progress)
    }

    private fun scheduleDemoActions() {
        val i = intent
        if (i.getBooleanExtra("demo_submit", false)) {
            val submit = object : Runnable {
                override fun run() {
                    if (guesses == null) { handler.postDelayed(this, 200); return }
                    onEnter()
                    Log.i("KhumasiDemo", "submitted")
                }
            }
            handler.postDelayed(submit, 5000)
        }
        if (i.getBooleanExtra("demo_rules", false)) handler.postDelayed({ showRules() }, 600)
        if (i.getBooleanExtra("demo_stats", false)) handler.postDelayed({ showStats() }, 600)
        if (i.getBooleanExtra("demo_settings", false)) handler.postDelayed({ showSettings() }, 600)
        if (i.getBooleanExtra("demo_rewards", false)) handler.postDelayed({ showRewards() }, 600)
        if (i.getBooleanExtra("demo_hint", false)) handler.postDelayed({ requestHint() }, 900)
        if (i.getBooleanExtra("demo_scroll_ai", false)) handler.postDelayed({
            (sheets.findViewWithTag<View>("sheet_scroll") as? android.widget.ScrollView)?.fullScroll(View.FOCUS_DOWN)
        }, 1500)
        if (i.getBooleanExtra("demo_modes", false)) handler.postDelayed({ showModes() }, 800)
        if (i.getBooleanExtra("demo_daily", false)) handler.postDelayed({ switchTo(GameState.MODE_DAILY) }, 800)
        if (i.getBooleanExtra("demo_friend_create", false)) handler.postDelayed({ showFriendCreate() }, 800)
        i.getStringExtra("demo_friend_word")?.let { w -> handler.postDelayed({ showFriendEnter("تحدّيتك! الرمز: " + FriendCode.encode(ArabicNormalizer.normalize(w), 9)) }, 800) }
        if (i.getBooleanExtra("demo_friend_start", false)) handler.postDelayed({ startFriend(FriendCode.encode("مكتبة", 9)) }, 800)
        if (i.getBooleanExtra("demo_tutorial", false)) handler.postDelayed({ showTutorial() }, 600)
        if (i.getBooleanExtra("demo_result", false) && state.isOver) handler.postDelayed({ showResult(levelUp = false) }, 900)
        if (state.isOver && !i.getBooleanExtra("demo_result", false)) updateRoundUi()
        if (i.getBooleanExtra("demo_keys", false)) handler.postDelayed({ keyboard.logKeyPositions() }, 2500)
    }

    companion object {
        private const val TAG = "Khumasi"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
