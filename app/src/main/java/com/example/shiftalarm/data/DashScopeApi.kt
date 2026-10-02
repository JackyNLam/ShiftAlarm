package com.example.shiftalarm.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal DashScope client (OpenAI-compatible chat-completions endpoint), used to
 * extract a schedule from an image. Synchronous — call from Dispatchers.IO.
 *
 * Uses only HttpURLConnection + org.json, so no new dependencies are needed.
 */
class DashScopeApi {

    /**
     * Sends the image (base64 data URL) plus [prompt] to the vision model and returns
     * the model's text answer (expected to be a JSON schedule in import/export format).
     * [onDebug] receives human-readable progress lines for the on-screen debug log
     * (called from whichever thread the network work runs on — must be thread-safe).
     */
    fun extractContent(
        apiKey: String,
        model: String,
        prompt: String,
        imageFile: File,
        onDebug: (String) -> Unit = {}
    ): String {
        val imageBase64 = Base64.encodeToString(imageFile.readBytes(), Base64.NO_WRAP)
        onDebug("圖片 ${imageFile.name} 已讀取，Base64 ${imageBase64.length / 1024} KB")

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0.1) // deterministic extraction
            put("max_tokens", 2048)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$imageBase64")
                            })
                        })
                    })
                })
            })
        }.toString()

        // The platform OkHttp can drop the response mid-stream ("unexpected end of
        // stream") when a connection is aborted — retry once on a fresh connection.
        var lastIoError: IOException? = null
        repeat(MAX_ATTEMPTS) { attempt ->
            onDebug("POST $ENDPOINT — model: $model，嘗試 ${attempt + 1}/$MAX_ATTEMPTS（連線 30s / 讀取 120s）")
            try {
                return postJson(apiKey, body, onDebug)
            } catch (e: IOException) {
                lastIoError = e
                onDebug("⚠️ 網路錯誤（${if (attempt + 1 < MAX_ATTEMPTS) "將重試" else "放棄"}）: ${e.message}")
            }
        }
        throw lastIoError ?: IllegalStateException("network error")
    }

    private fun postJson(apiKey: String, body: String, onDebug: (String) -> Unit): String {
        val start = System.currentTimeMillis()
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Connection", "close") // no keep-alive pooling
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            onDebug("已送出請求，等待回應…")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            val elapsed = String.format(
                java.util.Locale.US, "%.1f", (System.currentTimeMillis() - start) / 1000f
            )
            onDebug("收到回應: HTTP $code，${response.length} bytes（耗時 ${elapsed}s）")
            if (code !in 200..299) {
                throw IllegalStateException("DashScope HTTP $code: ${shortError(response)}")
            }

            val obj = JSONObject(response)
            val choices = obj.getJSONArray("choices")
            if (choices.length() == 0) {
                throw IllegalStateException("DashScope 回傳沒有結果 / Empty response")
            }
            return choices.getJSONObject(0).getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun shortError(body: String): String {
        if (body.isBlank()) return "no error body"
        return try {
            JSONObject(body).optJSONObject("error")?.optString("message")
                ?: body.take(200)
        } catch (_: Exception) {
            body.take(200)
        }
    }

    companion object {
        private const val ENDPOINT =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions"
        private const val MAX_ATTEMPTS = 2
    }
}