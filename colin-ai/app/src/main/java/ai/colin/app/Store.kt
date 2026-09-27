package ai.colin.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class Role { USER, ASSISTANT }

data class ChatMessage(
    val role: Role,
    val text: String,
    val thinking: String = "",
    val sources: List<Source> = emptyList(),
    val isError: Boolean = false,
)

data class Source(val title: String, val url: String)

data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "New chat",
    val messages: List<ChatMessage> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
)

/** A model Colin AI can think with. Only models that support adaptive thinking, effort and the latest web search tool. */
enum class Brain(val id: String, val label: String, val blurb: String) {
    OPUS("claude-opus-5", "Colin Pro", "Smart and balanced (recommended)"),
    FABLE("claude-fable-5-1", "Colin Ultra", "Maximum intelligence, slower and pricier"),
    SONNET("claude-sonnet-5", "Colin Fast", "Quick and cheap for everyday questions"),
}

enum class Effort(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High"), XHIGH("Extra high"), MAX("Max") }

data class Settings(
    val apiKey: String = "",
    val brain: Brain = Brain.OPUS,
    val effort: Effort = Effort.HIGH,
    val webSearch: Boolean = true,
    val autoMemory: Boolean = true,
    val showThinking: Boolean = true,
    val instructions: String = "",
    val userName: String = "Colin",
)

class Store(context: Context) {
    private val secure: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "colin_secure",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    private val prefs = context.getSharedPreferences("colin", Context.MODE_PRIVATE)
    private val chatsDir = File(context.filesDir, "chats").apply { mkdirs() }

    fun loadSettings() = Settings(
        apiKey = secure.getString("api_key", "") ?: "",
        brain = runCatching { Brain.valueOf(prefs.getString("brain", "")!!) }.getOrDefault(Brain.OPUS),
        effort = runCatching { Effort.valueOf(prefs.getString("effort", "")!!) }.getOrDefault(Effort.HIGH),
        webSearch = prefs.getBoolean("web_search", true),
        autoMemory = prefs.getBoolean("auto_memory", true),
        showThinking = prefs.getBoolean("show_thinking", true),
        instructions = prefs.getString("instructions", "") ?: "",
        userName = prefs.getString("user_name", "Colin") ?: "Colin",
    )

    fun saveSettings(s: Settings) {
        secure.edit().putString("api_key", s.apiKey.trim()).apply()
        prefs.edit()
            .putString("brain", s.brain.name)
            .putString("effort", s.effort.name)
            .putBoolean("web_search", s.webSearch)
            .putBoolean("auto_memory", s.autoMemory)
            .putBoolean("show_thinking", s.showThinking)
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
                put(JSONObject().apply {
                    put("role", m.role.name)
                    put("text", m.text)
                    put("thinking", m.thinking)
                    put("isError", m.isError)
                    put("sources", JSONArray().apply {
                        m.sources.forEach { s -> put(JSONObject().put("title", s.title).put("url", s.url)) }
                    })
                })
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
                val src = m.optJSONArray("sources") ?: JSONArray()
                ChatMessage(
                    role = Role.valueOf(m.getString("role")),
                    text = m.getString("text"),
                    thinking = m.optString("thinking"),
                    isError = m.optBoolean("isError"),
                    sources = List(src.length()) { j ->
                        src.getJSONObject(j).let { Source(it.getString("title"), it.getString("url")) }
                    },
                )
            },
        )
    }
}
