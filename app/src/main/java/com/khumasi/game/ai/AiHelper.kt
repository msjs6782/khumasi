package com.khumasi.game.ai

import android.content.Context
import com.khumasi.game.core.Evaluator
import com.khumasi.game.core.FallbackHints
import com.khumasi.game.core.GameState
import com.khumasi.game.core.HintGuard
import com.khumasi.game.core.LetterState
import com.khumasi.game.core.Solution
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the game asks Gemini for: graded hints, word explanations and round analysis.
 * Gemini never decides colors, wins or which words are accepted — that stays in game logic.
 * Answers are cached, a daily request cap protects the key, and hints fall back to offline
 * ones when the AI is unavailable or its hint fails the safety check.
 */
class AiHelper(context: Context, val vault: KeyVault) {
    private val client = GeminiClient()
    private val cache = context.getSharedPreferences("khumasi_ai_cache", Context.MODE_PRIVATE)

    var model: String
        get() = cache.getString("model", GeminiClient.DEFAULT_MODEL) ?: GeminiClient.DEFAULT_MODEL
        set(v) = cache.edit().putString("model", v.trim().ifEmpty { GeminiClient.DEFAULT_MODEL }).apply()

    private fun models() = listOf(model) + GeminiClient.FALLBACK_MODELS

    val usedToday: Int get() = if (cache.getString("day", "") == today()) cache.getInt("count", 0) else 0

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun countRequest() {
        val n = usedToday + 1
        cache.edit().putString("day", today()).putInt("count", n).apply()
    }

    class Reply(val text: String, val fromAi: Boolean, val note: String? = null)

    private fun ask(cacheKey: String, prompt: String, maxTokens: Int, onFail: (String) -> Reply, check: (String) -> String?, done: (Reply) -> Unit) {
        cache.getString(cacheKey, null)?.let { done(Reply(it, true)); return }
        val key = vault.load()
        if (key == null) { done(onFail("أضف مفتاح Gemini من الإعدادات لتفعيل الذكاء الاصطناعي")); return }
        if (usedToday >= DAILY_LIMIT) { done(onFail("وصلت إلى حد اليوم (${DAILY_LIMIT} طلبًا) لحماية مفتاحك")); return }
        countRequest()
        client.generate(key, models(), prompt, maxTokens) { r ->
            when (r) {
                is GeminiClient.Result.Ok -> {
                    val cleaned = check(r.text)
                    if (cleaned == null) done(onFail("رُفض رد الذكاء الاصطناعي لأنه قد يكشف الكلمة"))
                    else { cache.edit().putString(cacheKey, cleaned).apply(); done(Reply(cleaned, true)) }
                }
                is GeminiClient.Result.Failed -> done(onFail(r.message))
            }
        }
    }

    /** Graded hint (1 gentle … 3 strong) that never names the word. */
    fun hint(level: Int, state: GameState, solution: Solution?, done: (Reply) -> Unit) {
        val answer = state.answer
        val known = describeKnowledge(state)
        val strength = when (level) {
            1 -> "تلميح خفيف جدًا: المجال العام أو الإحساس المرتبط بالكلمة، دون تعريف مباشر."
            2 -> "تلميح متوسط: صف استخدامًا أو موقفًا تظهر فيه الكلمة، دون ذكر مرادف مباشر."
            else -> "تلميح قوي: تعريف قريب أو لغز قصير، دون ذكر الكلمة أو أي مشتق منها أو حروفها."
        }
        val prompt = """
أنت تساعد لاعبًا في لعبة تخمين كلمة عربية من خمسة أحرف.
الكلمة المخفية: «${solution?.display ?: answer}» (المعنى: ${solution?.meaning ?: "غير متوفر"}).
ما اكتشفه اللاعب حتى الآن: $known
المطلوب: $strength
القواعد الصارمة:
- لا تكتب الكلمة المخفية ولا جذرها ولا أي كلمة مشتقة منها، ولا تتهجّ حروفها.
- لا تذكر عدد الحروف ولا مواضعها ولا ألوانها.
- لا تناقض المعلومات المكتشفة.
- جملة أو جملتان بالعربية الفصحى، بلا مقدمات ولا تنسيق.
""".trimIndent()
        val fallback = { reason: String -> Reply(FallbackHints.hint(level, solution, answer), false, reason) }
        ask("hint_${answer}_$level", prompt, 500, fallback, { text ->
            val t = HintGuard.tidy(text)
            if (t.isBlank() || HintGuard.revealsAnswer(t, answer) || (solution != null && HintGuard.revealsAnswer(t, solution.display))) null else t
        }, done)
    }

    /** Text of a hint already revealed this round (from the cache, or the offline one). */
    fun revealedHint(level: Int, state: GameState, solution: Solution?): String =
        cache.getString("hint_${state.answer}_$level", null) ?: FallbackHints.hint(level, solution, state.answer)

    /** Meaning with a short example, after the round is over. */
    fun explain(solution: Solution?, answer: String, done: (Reply) -> Unit) {
        val word = solution?.display ?: answer
        val prompt = """
اشرح الكلمة العربية «$word» للاعب بعد انتهاء جولة في لعبة كلمات.
اكتب بالعربية الفصحى وبإيجاز:
1) معناها في سطر واحد.
2) جذرها اللغوي إن كان معروفًا.
3) مثال قصير على استخدامها في جملة.
بلا مقدمات وبلا تنسيق Markdown.
""".trimIndent()
        ask("explain_$answer", prompt, 600, { reason ->
            Reply(solution?.meaning?.let { "المعنى: $it" } ?: "لا يتوفر شرح دون اتصال.", false, reason)
        }, { HintGuard.tidy(it, 600).ifBlank { null } }, done)
    }

    /** How the colors could have been used better; colors come from the game, not from the AI. */
    fun analyze(state: GameState, solution: Solution?, done: (Reply) -> Unit) {
        val rows = state.guesses.mapIndexed { i, g ->
            val e = Evaluator.evaluate(g, state.answer)
            "${i + 1}) $g: " + g.indices.joinToString("، ") { k -> "${g[k]}=${name(e[k])}" }
        }.joinToString("\n")
        val prompt = """
حلّل جولة لعبة تخمين كلمة عربية من خمسة أحرف (أخضر: الحرف في مكانه، كهرماني: في الكلمة بموضع آخر، رمادي: ليس فيها).
الكلمة الصحيحة: «${solution?.display ?: state.answer}». النتيجة: ${if (state.status.name == "WON") "فوز" else "خسارة"} في ${state.guesses.size} محاولات.
التخمينات بالترتيب مع ألوانها (محسوبة بدقة):
$rows
اكتب بالعربية الفصحى، في أربع نقاط قصيرة كحد أقصى:
- ما الذي أحسن فيه اللاعب.
- أين كان يمكن استغلال الألوان أفضل (مثلًا إعادة حرف رمادي أو تجاهل موضع كهرماني).
- نصيحة واحدة عملية للجولة القادمة.
بلا مقدمات وبلا تنسيق Markdown.
""".trimIndent()
        ask("analyze_${state.answer}_${state.guesses.joinToString("")}", prompt, 900, { reason ->
            Reply("لا يتوفر التحليل الآن.", false, reason)
        }, { HintGuard.tidy(it, 900).ifBlank { null } }, done)
    }

    /** A tiny request to confirm the key works. */
    fun test(done: (GeminiClient.Result) -> Unit) {
        val key = vault.load() ?: run { done(GeminiClient.Result.Failed("لا يوجد مفتاح محفوظ")); return }
        client.generate(key, models(), "اكتب كلمة «جاهز» فقط.", 50, done)
    }

    private fun name(s: LetterState) = when (s) {
        LetterState.CORRECT -> "أخضر"; LetterState.PRESENT -> "كهرماني"; else -> "رمادي"
    }

    private fun describeKnowledge(state: GameState): String {
        if (state.guesses.isEmpty()) return "لم يخمّن شيئًا بعد."
        val kb = state.keyboard
        val green = kb.filterValues { it == LetterState.CORRECT }.keys.joinToString(" ")
        val amber = kb.filterValues { it == LetterState.PRESENT }.keys.joinToString(" ")
        val gray = kb.filterValues { it == LetterState.ABSENT }.keys.joinToString(" ")
        return "حروف مؤكدة: [$green]، حروف موجودة بموضع آخر: [$amber]، حروف مستبعدة: [$gray]."
    }

    companion object {
        const val DAILY_LIMIT = 60
        const val MAX_HINTS = 3
    }
}
