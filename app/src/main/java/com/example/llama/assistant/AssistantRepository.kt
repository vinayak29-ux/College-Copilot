package com.example.llama.assistant

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.io.DataOutputStream

data class AssistantChatMessage(
    val id: String,
    val role: String,
    val content: String
)

data class AssistantThread(
    val threadId: String,
    val messages: List<AssistantChatMessage>
)

data class AssistantReply(
    val threadId: String,
    val messageId: String?,
    val content: String
)

data class AssistantAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray
)

interface AssistantRepository {
    fun storedThreadId(): String?
    fun openOrCreateThread(): AssistantThread
    fun sendMessage(threadId: String, content: String, attachment: AssistantAttachment? = null): AssistantReply
}

class BackboardAssistantRepository(
    private val api: AssistantApiClient,
    private val preferences: SharedPreferences
) : AssistantRepository {
    override fun storedThreadId(): String? = preferences.getString(KEY_THREAD_ID, null)

    override fun openOrCreateThread(): AssistantThread {
        val thread = api.openThread(storedThreadId())
        preferences.edit()
            .putString(KEY_ASSISTANT_ID, ASSISTANT_ID)
            .putString(KEY_THREAD_ID, thread.threadId)
            .apply()
        return thread
    }

    override fun sendMessage(threadId: String, content: String, attachment: AssistantAttachment?): AssistantReply {
        val reply = api.sendMessage(threadId, content, attachment)
        preferences.edit()
            .putString(KEY_ASSISTANT_ID, ASSISTANT_ID)
            .putString(KEY_THREAD_ID, reply.threadId)
            .apply()
        return reply
    }

    companion object {
        const val ASSISTANT_ID = "8e4957ab-77c4-48dd-87eb-cc770eb17f28"
        private const val KEY_ASSISTANT_ID = "backboard_assistant_id"
        private const val KEY_THREAD_ID = "backboard_thread_id"
    }
}

class AssistantApiClient(
    private val apiKey: String
) {
    private val baseUrl = "https://app.backboard.io/api"

    fun openThread(threadId: String?): AssistantThread {
        val response = if (!threadId.isNullOrBlank()) {
            request("GET", "/threads/${threadId.trim()}")
        } else {
            request("POST", "/assistants/${BackboardAssistantRepository.ASSISTANT_ID}/threads", JSONObject())
        }
        val messagesJson = response.optJSONArray("messages") ?: JSONArray()
        val messages = (0 until messagesJson.length()).mapNotNull { index ->
            val item = messagesJson.optJSONObject(index) ?: return@mapNotNull null
            val role = item.optString("role")
            val contentValue = item.opt("content")
            val content = when (contentValue) {
                is String -> contentValue
                is JSONArray -> (0 until contentValue.length()).mapNotNull { partIndex ->
                    contentValue.optJSONObject(partIndex)?.optString("text")?.takeIf(String::isNotBlank)
                }.joinToString("\n")
                else -> ""
            }
            if ((role != "user" && role != "assistant") || content.isBlank()) return@mapNotNull null
            AssistantChatMessage(item.optString("id", "$role-$index"), role, content)
        }
        val id = response.optString("thread_id").ifBlank { threadId.orEmpty() }
        if (id.isBlank()) throw IOException("Backboard did not return a thread ID.")
        return AssistantThread(id, messages)
    }

    fun sendMessage(threadId: String, content: String, attachment: AssistantAttachment? = null): AssistantReply {
        val response = if (attachment == null) {
            request("POST", "/threads/messages", messageBody(threadId, content))
        } else {
            requestMultipart(threadId, content, attachment)
        }
        return AssistantReply(
            threadId = response.getString("thread_id"),
            messageId = response.optString("message_id").takeIf { it.isNotBlank() },
            content = response.getString("content")
        )
    }

    private fun messageBody(threadId: String, content: String) = JSONObject().apply {
        put("thread_id", threadId)
        put("assistant_id", BackboardAssistantRepository.ASSISTANT_ID)
        put("content", content)
        put("llm_provider", "google")
        put("model_name", "gemini-2.5-flash")
        put("stream", false)
    }

    private fun requestMultipart(threadId: String, content: String, attachment: AssistantAttachment): JSONObject {
        if (apiKey.isBlank()) throw IOException("Backboard API key is not configured in local.properties.")
        val boundary = "LlamaAndroid-${System.currentTimeMillis()}"
        val connection = (URL("$baseUrl/threads/messages").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 90_000
            instanceFollowRedirects = false
            doOutput = true
            setChunkedStreamingMode(64 * 1024)
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-API-Key", apiKey)
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        return try {
            DataOutputStream(connection.outputStream.buffered()).use { output ->
                val fields = linkedMapOf(
                    "thread_id" to threadId,
                    "assistant_id" to BackboardAssistantRepository.ASSISTANT_ID,
                    "content" to content,
                    "llm_provider" to "google",
                    "model_name" to "gemini-2.5-flash",
                    "stream" to "false"
                )
                fields.forEach { (name, value) ->
                    output.writeBytes("--$boundary\r\n")
                    output.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                    output.write(value.toByteArray(Charsets.UTF_8))
                    output.writeBytes("\r\n")
                }
                val safeName = attachment.fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "image.jpg" }
                val safeType = attachment.mimeType.takeIf { it.matches(Regex("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+")) }
                    ?: "application/octet-stream"
                output.writeBytes("--$boundary\r\n")
                output.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"$safeName\"\r\n")
                output.writeBytes("Content-Type: $safeType\r\n\r\n")
                output.write(attachment.bytes)
                output.writeBytes("\r\n--$boundary--\r\n")
            }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        if (apiKey.isBlank()) throw IOException("Backboard API key is not configured in local.properties.")
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 90_000
            instanceFollowRedirects = false
            doOutput = body != null
            setRequestProperty("Accept", "application/json")
            setRequestProperty("X-API-Key", apiKey)
            if (body != null) setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw IOException("Backboard returned HTTP $status.")
        return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
    }
}
