package ai.colin.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

    /** All native engine calls happen on this one thread. */
    private val engineThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val engines = mutableMapOf<Brain, LocalEngine>()
    @Volatile private var active: LocalEngine? = null

    var settings by mutableStateOf(store.loadSettings())
        private set
    var memories by mutableStateOf(store.loadMemories())
        private set
    var conversations by mutableStateOf(store.loadConversations())
        private set
    var current by mutableStateOf(Conversation())
        private set
    var status by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var toast by mutableStateOf<String?>(null)

    fun updateSettings(s: Settings) {
        settings = s
        store.saveSettings(s)
    }

    fun addMemory(fact: String) {
        val f = fact.trim().trimEnd('.').plus(".")
        if (f.length < 3 || memories.any { it.equals(f, ignoreCase = true) }) return
        memories = memories + f
        store.saveMemories(memories)
    }

    fun removeMemory(index: Int) {
        memories = memories.filterIndexed { i, _ -> i != index }
        store.saveMemories(memories)
    }

    fun clearMemories() {
        memories = emptyList()
        store.saveMemories(memories)
    }

    fun newChat() {
        if (busy) stop()
        current = Conversation()
    }

    fun open(c: Conversation) {
        if (busy) stop()
        current = c
    }

    fun delete(c: Conversation) {
        store.deleteConversation(c.id)
        conversations = conversations.filterNot { it.id == c.id }
        if (current.id == c.id) current = Conversation()
    }

    fun stop() {
        active?.stop()
    }

    fun retry() {
        val msgs = current.messages
        val lastUser = msgs.indexOfLast { it.role == Role.USER }
        if (lastUser < 0 || busy) return
        current = current.copy(messages = msgs.take(lastUser + 1))
        respond()
    }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        val title = if (current.messages.isEmpty()) t.lineSequence().first().take(40) else current.title
        current = current.copy(title = title, messages = current.messages + ChatMessage(Role.USER, t))
        if (settings.autoMemory) learnFrom(t)
        respond()
    }

    /** Offline "learning": explicit remember requests go straight into long-term memory. */
    private fun learnFrom(text: String) {
        val m = REMEMBER.find(text) ?: return
        val fact = m.groupValues[1].trim()
        if (fact.length >= 3) {
            addMemory(fact.replaceFirstChar { it.uppercase() })
            toast = "Remembered: $fact"
        }
    }

    private fun systemPrompt(brain: Brain): String = buildString {
        append(brain.system)
        val name = settings.userName.trim()
        if (name.isNotEmpty() && name != "Colin") append(" The user's name is $name.")
        if (brain == Brain.COLIN) {
            if (memories.isNotEmpty()) {
                append("\n\nWhat you know about ${settings.userName}:\n")
                memories.forEach { append("- ").append(it).append('\n') }
            }
            if (settings.instructions.isNotBlank()) append("\n\nInstructions from ${settings.userName}:\n").append(settings.instructions.trim())
        }
    }

    /** Most recent turns that fit the brain's small context window (always ends with the user's message). */
    private fun trimmed(brain: Brain): List<ChatMessage> {
        val msgs = current.messages.filterNot { it.isError || it.text.isBlank() }
        val out = ArrayDeque<ChatMessage>()
        var chars = 0
        for (m in msgs.asReversed()) {
            if (out.isNotEmpty() && chars + m.text.length > brain.historyChars) break
            out.addFirst(if (m.text.length > brain.historyChars) m.copy(text = m.text.takeLast(brain.historyChars)) else m)
            chars += m.text.length
        }
        while (out.firstOrNull()?.role == Role.ASSISTANT) out.removeFirst()
        return out
    }

    private fun respond() {
        val brain = settings.brain
        val history = trimmed(brain)
        val system = systemPrompt(brain)
        val temperature = settings.creativity.temperature
        busy = true
        current = current.copy(messages = current.messages + ChatMessage(Role.ASSISTANT, ""))

        viewModelScope.launch {
            val error = withContext(engineThread) {
                runCatching {
                    val engine = engines[brain] ?: run {
                        ui { status = "Waking up ${brain.label}… (first time takes a few seconds)" }
                        LocalEngine.load(getApplication(), brain).also { engines[brain] = it }
                    }
                    active = engine
                    ui { status = "Thinking…" }
                    engine.generate(system, history, brain.maxReplyTokens, temperature) { piece ->
                        ui {
                            status = null
                            editLast { it.copy(text = it.text + piece) }
                        }
                        true
                    }
                }.exceptionOrNull()
            }
            active = null
            if (error != null) {
                editLast { it.copy(text = "Something went wrong: ${error.message}", isError = true) }
            } else if (current.messages.lastOrNull()?.text.isNullOrBlank()) {
                editLast { it.copy(text = "_(no reply)_") }
            } else {
                editLast { it.copy(text = it.text.trim()) }
            }
            busy = false
            status = null
            persist()
        }
    }

    private fun ui(block: () -> Unit) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Main) { block() }
    }

    private fun editLast(f: (ChatMessage) -> ChatMessage) {
        val msgs = current.messages
        if (msgs.isEmpty()) return
        current = current.copy(messages = msgs.dropLast(1) + f(msgs.last()))
    }

    private fun persist() {
        current = current.copy(updatedAt = System.currentTimeMillis())
        store.saveConversation(current)
        conversations = (listOf(current) + conversations.filterNot { it.id == current.id })
            .filter { it.messages.isNotEmpty() }
    }

    override fun onCleared() {
        active?.stop()
        val all = engines.values.toList()
        engines.clear()
        engineThread.executor.execute { all.forEach { it.close() } }
        engineThread.close()
    }

    companion object {
        private val REMEMBER = Regex(
            """^\s*(?:please\s+)?(?:remember(?:\s+that)?|don't forget(?:\s+that)?|merk dir(?:,)?(?:\s+dass)?|vergiss nicht(?:,)?(?:\s+dass)?)\s*[:,]?\s+(.+)$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    }
}
