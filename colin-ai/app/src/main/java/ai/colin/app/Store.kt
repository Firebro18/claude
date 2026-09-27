package ai.colin.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val role: Role,
    val text: String,
    val isError: Boolean = false,
)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** The two offline brains shipped inside the app (GGUF files in assets/models). */
enum class Brain(
    val file: String,
    val label: String,
    val blurb: String,
    val contextTokens: Int,
    val historyChars: Int,
    val maxReplyTokens: Int,
    val system: String,
) {
    COLIN(
        "colin-ai.gguf", "Colin AI",
        "Main brain. An open model (Qwen2.5 0.5B) that was trained to be Colin AI.",
        contextTokens = 4096, historyChars = 6000, maxReplyTokens = 768,
        system = "You are Colin AI, a personal AI assistant made for Colin. You run fully offline on his phone. " +
            "Be direct, warm, honest and helpful. Answer in the language the user writes in.",
    ),
    MINI(
        "colin-mini.gguf", "Colin Mini",
        "Experimental. A tiny brain trained 100% from scratch. Good at simple chats and short stories.",
        contextTokens = 512, historyChars = 700, maxReplyTokens = 200,
        system = "You are Colin Mini, a tiny AI made for Colin.",
    ),
}

enum class Creativity(val label: String, val temperature: Float) {
    PRECISE("Precise", 0.2f), BALANCED("Balanced", 0.6f), CREATIVE("Creative", 0.9f)
}

data class Settings(
    val brain: Brain = Brain.COLIN,
    val creativity: Creativity = Creativity.BALANCED,
    val autoMemory: Boolean = true,
    val instructions: String = "",
    val userName: String = "Colin",
)

class Store(context: Context) {
    private val prefs = context.getSharedPreferences("colin", Context.MODE_PRIVATE)
    private val chatsDir = File(context.filesDir, "chats").apply { mkdirs() }

    fun loadSettings() = Settings(
        brain = runCatching { Brain.valueOf(prefs.getString("brain", "")!!) }.getOrDefault(Brain.COLIN),
        creativity = runCatching { Creativity.valueOf(prefs.getString("creativity", "")!!) }.getOrDefault(Creativity.BALANCED),
        autoMemory = prefs.getBoolean("auto_memory", true),
        instructions = prefs.getString("instructions", "") ?: "",
        userName = prefs.getString("user_name", "Colin") ?: "Colin",
    )

    fun saveSettings(s: Settings) {
        prefs.edit()
            .putString("brain", s.brain.name)
            .putString("creativity", s.creativity.name)
            .putBoolean("auto_memory", s.autoMemory)
            .putString("instructions", s.instructions)
            .putString("user_name", s.userName)
            .apply()
    }

    fun loadMemories(): List<String> {
        val arr = JSONArray(prefs.getString("memories", "[]"))
        return List(arr.length()) { arr.getString(it) }
    }

    fun saveMemories(list: List<String>) {
        prefs.edit().putString("memories", JSONArray(list).toString()).apply()
    }

    fun loadConversations(): List<Conversation> =
        chatsDir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    fun saveConversation(c: Conversation) {
        if (c.messages.isEmpty()) return
        File(chatsDir, "${c.id}.json").writeText(toJson(c).toString())
    }

    fun deleteConversation(id: String) {
        File(chatsDir, "$id.json").delete()
    }

    private fun toJson(c: Conversation) = JSONObject().apply {
        put("id", c.id)
        put("title", c.title)
        put("updatedAt", c.updatedAt)
        put("messages", JSONArray().apply {
            c.messages.forEach { m ->
                put(JSONObject().put("role", m.role.name).put("text", m.text).put("isError", m.isError))
            }
        })
    }

    private fun fromJson(o: JSONObject): Conversation {
        val msgs = o.getJSONArray("messages")
        return Conversation(
            id = o.getString("id"),
            title = o.optString("title", "Chat"),
            updatedAt = o.optLong("updatedAt"),
            messages = List(msgs.length()) { i ->
                val m = msgs.getJSONObject(i)
                ChatMessage(Role.valueOf(m.getString("role")), m.getString("text"), m.optBoolean("isError"))
            },
        )
    }
}
