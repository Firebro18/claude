package ai.colin.app

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)

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

    private var brain: ColinBrain? = null
    private var brainKey: String? = null

    fun updateSettings(s: Settings) {
        settings = s
        store.saveSettings(s)
    }

    fun addMemory(fact: String) {
        val f = fact.trim()
        if (f.isEmpty() || memories.any { it.equals(f, ignoreCase = true) }) return
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
        brain?.cancel()
    }

    /** Re-run the last user message (after an error or a weak answer). */
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
        respond()
    }

    private fun respond() {
        if (settings.apiKey.isBlank()) {
            appendAssistant(ChatMessage(Role.ASSISTANT, "I need an Anthropic API key before I can think. Open Settings (⚙) to add one.", isError = true))
            return
        }
        if (brainKey != settings.apiKey) {
            brain = ColinBrain(settings.apiKey)
            brainKey = settings.apiKey
        }
        val b = brain!!
        val history = current.messages
        busy = true
        status = "Thinking…"
        appendAssistant(ChatMessage(Role.ASSISTANT, ""))

        viewModelScope.launch {
            val listener = object : BrainListener {
                override fun onText(delta: String) = ui { editLast { it.copy(text = it.text + delta) } }
                override fun onThinking(delta: String) = ui { editLast { it.copy(thinking = it.thinking + delta) } }
                override fun onStatus(status: String?) = ui { this@ChatViewModel.status = status }
                override fun onSource(source: Source) = ui {
                    editLast { m -> if (m.sources.any { it.url == source.url }) m else m.copy(sources = m.sources + source) }
                }
                override fun onRemember(fact: String) = ui {
                    addMemory(fact)
                    toast = "Remembered: $fact"
                }
            }
            val error = withContext(Dispatchers.IO) {
                runCatching { b.reply(history, settings, memories, listener) }.exceptionOrNull()
            }
            if (error != null) {
                editLast { it.copy(text = it.text.ifEmpty { ColinBrain.describe(error) }, isError = it.text.isEmpty()) }
            } else if (current.messages.lastOrNull()?.text.isNullOrEmpty()) {
                editLast { it.copy(text = "_(stopped)_") }
            }
            busy = false
            status = null
            persist()
        }
    }

    private fun ui(block: () -> Unit) {
        viewModelScope.launch(Dispatchers.Main) { block() }
    }

    private fun appendAssistant(m: ChatMessage) {
        current = current.copy(messages = current.messages + m)
        if (m.isError) persist()
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
}
