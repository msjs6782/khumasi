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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.khumasi.game.core.ArabicNormalizer
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

    private lateinit var solutions: List<Solution>
    @Volatile private var guesses: GuessList? = null
    private lateinit var state: GameState
    private lateinit var progress: Progress

    private val handler = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var pendingResult: Runnable? = null
    private var pendingLevelUp = false
    private var inputLockedUntil = 0L
    private var resumedAt = 0L
    private var lastRegion = floatArrayOf(0f, 0f, 0f, 0f)
    private var reduceMotion = false
    private var lightMode = false
    private var insetTop = 0
    private var insetBottom = 0
    private var demo = false

    // ------------------------------------------------------------------ setup

    override fun onCreate(savedInstanceState: Bundle?) {
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

        solutions = repo.loadSolutions()
        progress = store.loadProgress()
        state = store.loadGame() ?: newRound().also { store.saveRound(it, progress) }
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

        when {
            demo -> scheduleDemoActions()
            !store.seenRules -> handler.postDelayed({ showRules(); store.seenRules = true }, 500)
            state.isOver -> handler.postDelayed({ showResult(levelUp = false) }, 350)
        }
    }

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(opaque(Palette.SLAB)) }

        board2d = BoardView2D(this, fonts).apply { visibility = View.GONE }
        renderer = GameRenderer(fonts.heavy, this)
        renderer.reduceMotion = reduceMotion
        renderer.quality = if (lightMode) GameRenderer.Quality(shadows = false, glass = false, ambient = false)
        else GameRenderer.Quality(shadows = true, glass = true, ambient = true)
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
        val statsBtn = IconView(this, IconView.Kind.STATS, "الإحصاءات").apply { setOnClickListener { showStats() } }
        val settingsBtn = IconView(this, IconView.Kind.SETTINGS, "الإعدادات").apply { setOnClickListener { showSettings() } }
        header.addView(help, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
        header.addView(View(this), LinearLayout.LayoutParams(dpi(48f), 1))
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(statsBtn, LinearLayout.LayoutParams(dpi(48f), dpi(48f)))
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
        }
        keyboardPanel.addView(keyboard, FrameLayout.LayoutParams(MATCH, MATCH))
        resultBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dpi(20f), 0, dpi(20f), 0)
            addView(panels.button("كلمة جديدة", primary = true) { startNewRound() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f))
            addView(panels.button("النتيجة", primary = false) { showResult(levelUp = false) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(10f) })
        }
        keyboardPanel.addView(resultBar, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(keyboardPanel, FrameLayout.LayoutParams(MATCH, dpi(240f), Gravity.BOTTOM))

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
            layoutKeyboard()
            insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateRegion() }
        hideSystemBars()
    }

    private fun layoutKeyboard() {
        val w = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val h = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val kh = keyboard.preferredHeight(w, (h * 0.34f).toInt())
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
    }

    override fun onPause() {
        accumulatePlayTime()
        store.saveGame(state)
        surface?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
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
            is SubmitResult.Rejected -> reject(result.reason)
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
                    if (won) board.bounce(row, end + 60)
                }
                board2d.show(state)
                handler.postDelayed({ updateKeyboard() }, reveal)
                if (state.isOver) onRoundOver(levelBefore, reveal) else store.saveGame(state)
            }
        }
    }

    private fun canType(): Boolean =
        !state.isOver && !sheets.isShowing && SystemClock.uptimeMillis() >= inputLockedUntil

    private fun reject(reason: RejectReason) {
        val msg = when (reason) {
            RejectReason.TOO_SHORT -> "أكمل خمسة أحرف"
            RejectReason.NOT_IN_LIST -> "ليست في قائمة الكلمات"
            RejectReason.ALREADY_TRIED -> "جرّبت هذه الكلمة من قبل"
            RejectReason.GAME_OVER -> return
        }
        showToast(msg)
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
    }

    private fun startNewRound() {
        sheets.close()
        surface?.send { setWorldView(false) }
        state = newRound()
        store.saveRound(state, progress)
        syncBoard(animate = true)
        updateKeyboard()
        updateRoundUi()
    }

    private fun newRound(): GameState {
        val reported = store.reportedWords
        val pool = solutions.filter { it.word !in reported }.ifEmpty { solutions }
        val pick = SolutionPicker.pick(pool, progress)
        progress = progress.rememberAnswer(pick.word, pool.size)
        return GameState(answer = pick.word, startedAtEpochMs = System.currentTimeMillis())
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
    }

    // ------------------------------------------------------------------ sheets

    private fun solutionFor(word: String): Solution? = solutions.firstOrNull { it.word == word }

    private fun showResult(levelUp: Boolean) {
        if (!state.isOver) return
        // The board slides away so the island shows above the result.
        surface?.send { setWorldView(true) }
        val content = panels.result(
            state, solutionFor(state.answer), progress, levelUp,
            onNext = { startNewRound() },
            onShare = { share() },
            onReport = { showReport() },
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
        val s = Panels.SettingsState(store.reduceMotion, store.lightMode, store.haptics)
        val view = panels.settings(s, "\u2066" + BuildConfig.VERSION_NAME + "\u2069") { st, lightChanged ->
            store.reduceMotion = st.reduceMotion
            store.haptics = st.haptics
            store.lightMode = st.lightMode
            reduceMotion = st.reduceMotion
            renderer.reduceMotion = st.reduceMotion
            sheets.reduceMotion = st.reduceMotion
            keyboard.haptics = st.haptics
            surface?.invalidateScene()
            if (lightChanged) {
                // Anti-aliasing is chosen when the surface is created, so rebuild the screen.
                handler.postDelayed({ recreate() }, 250)
            }
        }
        sheets.show("الإعدادات", view)
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
        val wins = i.getIntExtra("demo_wins", -1)
        if (wins >= 0) progress = progress.copy(unassisted = Stats(played = wins + wins / 4, wins = wins, currentStreak = minOf(wins, 3), bestStreak = minOf(wins, 6)))
        val answer = i.getStringExtra("demo_answer")?.let { ArabicNormalizer.normalize(it) }
        if (answer != null && answer.length == 5) {
            var s = GameState(answer = answer, startedAtEpochMs = System.currentTimeMillis(), playedMs = 83_000)
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
            handler.postDelayed(submit, 3500)
        }
        if (i.getBooleanExtra("demo_rules", false)) handler.postDelayed({ showRules() }, 600)
        if (i.getBooleanExtra("demo_stats", false)) handler.postDelayed({ showStats() }, 600)
        if (i.getBooleanExtra("demo_settings", false)) handler.postDelayed({ showSettings() }, 600)
        if (i.getBooleanExtra("demo_result", false) && state.isOver) handler.postDelayed({ showResult(levelUp = false) }, 900)
        if (state.isOver && !i.getBooleanExtra("demo_result", false)) updateRoundUi()
        if (i.getBooleanExtra("demo_keys", false)) handler.postDelayed({ keyboard.logKeyPositions() }, 2500)
    }

    companion object {
        private const val TAG = "Khumasi"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    }
}
