package ai.colin.app

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Runs the real brain loop against a fake Messages API that streams canned SSE responses. */
class ColinBrainTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<String>()
    private val responses = ArrayDeque<String>()

    @Before fun start() = server.start()

    @After fun stop() = server.shutdown()

    private fun enqueueAll() = responses.forEach {
        server.enqueue(MockResponse().setHeader("content-type", "text/event-stream").setBody(it))
    }

    private fun sse(vararg events: String) = events.joinToString("") { e ->
        "event: ${JSONObject(e).getString("type")}\ndata: $e\n\n"
    }

    private val msgStart = """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}"""

    @Test fun rememberThenSearchAnswer() {
        // Turn 1: Claude calls the local remember tool.
        responses += sse(
            msgStart,
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"toolu_1","name":"remember","input":{}}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"fact\": \"Colin likes short answers.\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use","stop_sequence":null},"usage":{"output_tokens":20}}""",
            """{"type":"message_stop"}""",
        )
        // Turn 2: thinking, web search with a result, then the answer.
        responses += sse(
            msgStart,
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Need current gold price."}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"sig"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"server_tool_use","id":"srvtoolu_1","name":"web_search","input":{}}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"query\":\"gold price\"}"}}""",
            """{"type":"content_block_stop","index":1}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"web_search_tool_result","tool_use_id":"srvtoolu_1","content":[{"type":"web_search_result","title":"Gold Price Today","url":"https://example.com/gold","encrypted_content":"x","page_age":null}]}}""",
            """{"type":"content_block_stop","index":2}""",
            """{"type":"content_block_start","index":3,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"Got it. Gold is "}}""",
            """{"type":"content_block_delta","index":3,"delta":{"type":"text_delta","text":"up today."}}""",
            """{"type":"content_block_stop","index":3}""",
            """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":30}}""",
            """{"type":"message_stop"}""",
        )

        val text = StringBuilder(); val thinking = StringBuilder()
        val sources = mutableListOf<Source>(); val remembered = mutableListOf<String>(); val statuses = mutableListOf<String?>()
        val listener = object : BrainListener {
            override fun onText(delta: String) { text.append(delta) }
            override fun onThinking(delta: String) { thinking.append(delta) }
            override fun onStatus(status: String?) { statuses += status }
            override fun onSource(source: Source) { sources += source }
            override fun onRemember(fact: String) { remembered += fact }
        }
        enqueueAll()
        ColinBrain("test-key", server.url("/").toString().trimEnd('/')).reply(
            history = listOf(
                ChatMessage(Role.USER, "Hi"), ChatMessage(Role.ASSISTANT, "Hey!"),
                ChatMessage(Role.USER, "I like short answers. What's gold doing?"),
            ),
            settings = Settings(apiKey = "test-key"),
            memories = listOf("Colin lives in Switzerland."),
            listener = listener,
        )

        assertEquals("Got it. Gold is up today.", text.toString())
        assertEquals("Need current gold price.", thinking.toString())
        assertEquals(listOf("Colin likes short answers."), remembered)
        assertEquals(listOf(Source("Gold Price Today", "https://example.com/gold")), sources)
        assertTrue(statuses.contains("Searching the web…"))
        repeat(server.requestCount) { requests += server.takeRequest().body.readUtf8() }
        assertEquals(2, requests.size)

        val first = JSONObject(requests[0])
        println("REQUEST 1: " + first.toString(2).take(3000))
        assertEquals("claude-opus-5", first.getString("model"))
        assertEquals("default", first.getString("fallbacks"))
        assertEquals("adaptive", first.getJSONObject("thinking").getString("type"))
        assertEquals("high", first.getJSONObject("output_config").getString("effort"))
        assertTrue(first.getString("system").contains("Colin lives in Switzerland."))
        val tools = first.getJSONArray("tools")
        assertEquals("web_search_20260209", tools.getJSONObject(0).getString("type"))
        assertEquals("remember", tools.getJSONObject(1).getString("name"))
        assertEquals(3, first.getJSONArray("messages").length())

        val second = JSONObject(requests[1])
        val msgs: JSONArray = second.getJSONArray("messages")
        assertEquals(5, msgs.length())
        assertEquals("tool_use", msgs.getJSONObject(3).getJSONArray("content").getJSONObject(0).getString("type"))
        val result = msgs.getJSONObject(4).getJSONArray("content").getJSONObject(0)
        assertEquals("tool_result", result.getString("type"))
        assertEquals("toolu_1", result.getString("tool_use_id"))
    }
}
