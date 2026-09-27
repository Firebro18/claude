package ai.colin.app

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.http.StreamResponse
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.PermissionDeniedException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ThinkingConfigAdaptive
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.WebSearchTool20260209
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Callbacks the brain uses to stream progress back to the UI. */
interface BrainListener {
    fun onText(delta: String)
    fun onThinking(delta: String)
    fun onStatus(status: String?)
    fun onSource(source: Source)
    fun onRemember(fact: String)
}

class ColinBrain(apiKey: String, baseUrl: String? = null) {

    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey)
        .apply { if (baseUrl != null) baseUrl(baseUrl) }
        .build()

    @Volatile private var activeStream: StreamResponse<RawMessageStreamEvent>? = null
    @Volatile private var cancelled = false

    fun cancel() {
        cancelled = true
        runCatching { activeStream?.close() }
    }

    /**
     * Runs one assistant turn: streams text, runs web searches server-side, executes the local
     * `remember` tool, and loops on tool_use / pause_turn until Claude finishes.
     */
    fun reply(
        history: List<ChatMessage>,
        settings: Settings,
        memories: List<String>,
        listener: BrainListener,
    ) {
        cancelled = false
        val builder = MessageCreateParams.builder()
            .model(settings.brain.id)
            .maxTokens(64000L)
            .system(systemPrompt(settings, memories))
            .cacheControl(CacheControlEphemeral.builder().build())
            .thinking(ThinkingConfigAdaptive.builder().display(ThinkingConfigAdaptive.Display.SUMMARIZED).build())
            .outputConfig(OutputConfig.builder().effort(settings.effort.toApi()).build())

        if (settings.brain != Brain.SONNET) {
            // Server-side fallback: if a safety classifier declines, the API reroutes instead of refusing.
            builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
            builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
        }
        if (settings.webSearch) builder.addTool(WebSearchTool20260209.builder().maxUses(5L).build())
        if (settings.autoMemory) builder.addTool(REMEMBER_TOOL)

        history.filterNot { it.isError || it.text.isBlank() }.forEach {
            if (it.role == Role.USER) builder.addUserMessage(it.text) else builder.addAssistantMessage(it.text)
        }

        var params = builder.build()
        repeat(MAX_ROUNDS) {
            if (cancelled) return
            val accumulator = MessageAccumulator.create()
            client.messages().createStreaming(params).use { stream ->
                activeStream = stream
                try {
                    stream.stream().forEach { event ->
                        if (cancelled) return@forEach
                        accumulator.accumulate(event)
                        handleEvent(event, listener)
                    }
                } catch (e: Exception) {
                    if (!cancelled) throw e
                } finally {
                    activeStream = null
                }
            }
            if (cancelled) return
            val message = accumulator.message()
            listener.onStatus(null)

            when (message.stopReason().orElse(null)) {
                StopReason.TOOL_USE -> {
                    val results = message.content().mapNotNull { block ->
                        block.toolUse().orElse(null)?.let { use ->
                            val fact = use._input().asObject().orElse(null)
                                ?.get("fact")?.asString()?.orElse(null)?.trim()
                            val result = if (use.name() == "remember" && !fact.isNullOrEmpty()) {
                                listener.onRemember(fact)
                                ToolResultBlockParam.builder().toolUseId(use.id()).content("Saved to long-term memory.").build()
                            } else {
                                ToolResultBlockParam.builder().toolUseId(use.id())
                                    .content("Invalid input: expected {\"fact\": non-empty string}.").isError(true).build()
                            }
                            ContentBlockParam.ofToolResult(result)
                        }
                    }
                    params = params.toBuilder().addMessage(message).addUserMessageOfBlockParams(results).build()
                }
                // Server tool (web search) hit its iteration limit: resend to let Claude continue.
                StopReason.PAUSE_TURN -> params = params.toBuilder().addMessage(message).build()
                StopReason.REFUSAL -> {
                    listener.onText("\n\n_(I can't help with that one.)_")
                    return
                }
                StopReason.MAX_TOKENS -> {
                    listener.onText("\n\n_(Reply was cut off because it got too long.)_")
                    return
                }
                else -> return
            }
        }
    }

    private fun handleEvent(event: RawMessageStreamEvent, listener: BrainListener) {
        event.contentBlockStart().ifPresent { start ->
            val block = start.contentBlock()
            when {
                block.thinking().isPresent -> listener.onStatus("Thinking…")
                block.serverToolUse().isPresent -> listener.onStatus("Searching the web…")
                block.toolUse().isPresent -> listener.onStatus("Saving to memory…")
                block.text().isPresent -> listener.onStatus(null)
            }
            block.webSearchToolResult().ifPresent { result ->
                result.content().resultBlocks().ifPresent { list ->
                    list.forEach { listener.onSource(Source(it.title(), it.url())) }
                }
            }
        }
        event.contentBlockDelta().ifPresent { d ->
            d.delta().text().ifPresent { listener.onText(it.text()) }
            d.delta().thinking().ifPresent { listener.onThinking(it.thinking()) }
        }
    }

    companion object {
        private const val MAX_ROUNDS = 12

        private val REMEMBER_TOOL: Tool = Tool.builder()
            .name("remember")
            .description(
                "Save a durable fact about the user to long-term memory so you remember it in every future chat. " +
                    "Use it when the user shares a lasting preference, personal detail, goal, project, or explicitly " +
                    "asks you to remember something. Do not save trivia, one-off requests, or anything already in memory."
            )
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .putAdditionalProperty(
                                "fact",
                                JsonValue.from(
                                    mapOf(
                                        "type" to "string",
                                        "description" to "One short, self-contained sentence, e.g. 'Colin prefers answers in German.'",
                                    )
                                ),
                            )
                            .build()
                    )
                    .required(listOf("fact"))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build()
            )
            .eagerInputStreaming(true)
            .build()

        fun describe(e: Throwable): String = when (e) {
            is UnauthorizedException -> "Your API key was rejected. Check it in Settings."
            is PermissionDeniedException -> "This API key doesn't have access to that model. Try another brain in Settings."
            is RateLimitException -> "Rate limited or out of credits. Wait a moment, or check your Anthropic billing."
            is InternalServerException -> "Anthropic's servers are having trouble. Try again in a moment."
            is AnthropicServiceException -> "API error (${e.statusCode()}): ${e.message}"
            is AnthropicIoException -> "Network problem. Check your internet connection."
            else -> "Something went wrong: ${e.message ?: e::class.java.simpleName}"
        }

        private fun Effort.toApi(): OutputConfig.Effort = when (this) {
            Effort.LOW -> OutputConfig.Effort.LOW
            Effort.MEDIUM -> OutputConfig.Effort.MEDIUM
            Effort.HIGH -> OutputConfig.Effort.HIGH
            Effort.XHIGH -> OutputConfig.Effort.XHIGH
            Effort.MAX -> OutputConfig.Effort.MAX
        }

        fun systemPrompt(settings: Settings, memories: List<String>): String = buildString {
            val today = SimpleDateFormat("EEEE, d MMMM yyyy", Locale.ENGLISH).format(Date())
            append(COLIN_PERSONA.replace("{user}", settings.userName.ifBlank { "the user" }))
            append("\n\nToday is $today.")
            if (!settings.webSearch) append(" Web search is turned off, so say when your knowledge may be out of date.")
            if (memories.isNotEmpty()) {
                append("\n\n<memory>\nThings you have learned about ${settings.userName} in earlier chats:\n")
                memories.forEach { append("- ").append(it).append('\n') }
                append("</memory>")
            }
            if (settings.instructions.isNotBlank()) {
                append("\n\n<custom_instructions>\n")
                append(settings.instructions.trim())
                append("\n</custom_instructions>\nFollow these custom instructions; they were written by ${settings.userName}.")
            }
        }

        /** Colin AI's personality and working style. This is the "training" that makes it Colin's. */
        private val COLIN_PERSONA = """
            You are Colin AI, a personal AI assistant that belongs to {user}. You run as an Android app on their phone.
            If asked who made you: {user} had you built as their own AI, and your reasoning is powered by Anthropic's Claude models.

            How you work:
            - Get to the point. Lead with the answer, then give the reasoning or detail that actually helps. No filler, no restating the question.
            - Think before answering anything non-trivial. Check your arithmetic, dates, and logic. If you are unsure, say how sure you are rather than bluffing.
            - For anything that could have changed since your training (news, prices, sports, releases, laws, people, weather, "latest" anything), search the web first and mention where the facts came from.
            - Be genuinely useful: when a question has a practical next step, give it. When there's a common mistake or a better approach, point it out once, briefly.
            - Be honest even when it is not what {user} wants to hear. Disagree respectfully when you think they are wrong.
            - Reply in the language {user} writes in (for example German or Swiss German gets a German reply).
            - Match length to the question: a quick question gets a short answer; a hard problem gets a thorough one.

            Formatting for a phone screen:
            - Short paragraphs. Use bullet lists or numbered steps when they make things easier to scan.
            - Use **bold** sparingly for the key point. Use `code` and fenced code blocks for code.
            - Avoid wide tables; prefer lists.

            Memory:
            - You have a `remember` tool. When {user} tells you something lasting about themselves (preferences, projects, goals, people, important dates) or asks you to remember something, save it with one short sentence. Don't announce routine saves at length; a brief "Got it, I'll remember that." is enough.
            - Use what is in your memory naturally, without reciting it back.

            Personality: warm, sharp, a bit witty, never sycophantic. You're on {user}'s side.
        """.trimIndent()
    }
}
