package com.khumasi.game.ai

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Minimal Gemini REST client. Calls run off the main thread; results come back on it. */
class GeminiClient {

    sealed interface Result {
        data class Ok(val text: String, val model: String, val truncated: Boolean = false) : Result
        data class Failed(val message: String, val badKey: Boolean = false) : Result
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun generate(key: String, models: List<String>, prompt: String, maxTokens: Int = 700, done: (Result) -> Unit) {
        worker.execute {
            var last: Result = Result.Failed("لم يُجرَّب أي نموذج")
            for (model in models.distinct().filter { it.isNotBlank() }) {
                last = call(key, model.trim(), prompt, maxTokens)
                // Try the next model only when this one doesn't exist or isn't available to the key.
                if (last is Result.Ok || (last as Result.Failed).badKey || !last.message.startsWith(MODEL_MISSING)) break
            }
            val r = last
            main.post { done(r) }
        }
    }

    /**
     * Gemini 3 models count their internal thinking toward maxOutputTokens, so the answer gets
     * a generous budget plus low thinking; if a model rejects the thinking setting, retry without.
     */
    private fun call(key: String, model: String, prompt: String, maxTokens: Int): Result {
        val first = request(key, model, prompt, maxTokens, lowThinking = model.startsWith("gemini-3"))
        return if (first is Result.Failed && first.message.startsWith(THINKING_REJECTED)) request(key, model, prompt, maxTokens, false) else first
    }

    private fun request(key: String, model: String, prompt: String, maxTokens: Int, lowThinking: Boolean): Result = try {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 25_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", key)
        }
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("generationConfig", JSONObject().put("temperature", 0.7).put("maxOutputTokens", maxTokens + THINKING_HEADROOM).apply {
                if (lowThinking) put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
            })
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        conn.disconnect()
        when {
            code in 200..299 -> {
                val json = JSONObject(text)
                val cand = json.optJSONArray("candidates")?.optJSONObject(0)
                val parts = cand?.optJSONObject("content")?.optJSONArray("parts")
                val cut = cand?.optString("finishReason") == "MAX_TOKENS"
                val out = StringBuilder()
                if (parts != null) for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (!p.optBoolean("thought", false)) out.append(p.optString("text"))
                }
                if (out.isBlank()) Result.Failed("لم يرجع النموذج نصًا") else Result.Ok(out.toString().trim(), model, cut)
            }
            code == 400 && lowThinking && text.contains("hinking") -> Result.Failed(THINKING_REJECTED)
            code == 400 && text.contains("API_KEY_INVALID") -> Result.Failed("المفتاح غير صحيح", badKey = true)
            code == 401 || code == 403 -> Result.Failed("المفتاح مرفوض أو لا يملك صلاحية Gemini", badKey = true)
            code == 404 -> Result.Failed("$MODEL_MISSING ($model)")
            code == 429 -> Result.Failed("تجاوزت حد الاستخدام المسموح لمفتاحك مؤقتًا")
            else -> Result.Failed("خطأ من الخدمة ($code)")
        }
    } catch (t: java.net.UnknownHostException) {
        Result.Failed("لا يوجد اتصال بالإنترنت")
    } catch (t: java.net.SocketTimeoutException) {
        Result.Failed("انتهت مهلة الاتصال")
    } catch (t: Throwable) {
        Result.Failed("تعذّر الاتصال: ${t.javaClass.simpleName}")
    }

    companion object {
        const val MODEL_MISSING = "النموذج غير متاح"
        private const val THINKING_REJECTED = "thinking-config-rejected"
        private const val THINKING_HEADROOM = 3000
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        val FALLBACK_MODELS = listOf("gemini-3.6-flash", "gemini-3.5-flash-lite", "gemini-2.5-flash")
    }
}
