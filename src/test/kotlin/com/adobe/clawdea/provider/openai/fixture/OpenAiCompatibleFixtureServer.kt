/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.provider.openai.fixture

import com.adobe.clawdea.provider.openai.profile.OpenAiCompatibleProfile
import com.adobe.clawdea.provider.openai.profile.ResolvedProviderProfile
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Local, in-process OpenAI-compatible provider fixture. Binds a real ephemeral socket on
 * `127.0.0.1:0` and implements `/models` and `/chat/completions` so the agent stack can be driven
 * end-to-end through the real [com.adobe.clawdea.provider.openai.client.OpenAiCompatibleClient]
 * (real HTTP, real SSE parsing) without touching any external network.
 *
 * Uses ONLY generic identifiers (`model-agentic`, `model-completion`, `fixture-profile`) so the
 * privacy guard stays green.
 *
 * Each request to `/chat/completions` consumes the next [ScriptedResponse] queued via [script];
 * this lets one user turn drive multiple sequential HTTP rounds (tool call → follow-up completion).
 */
class OpenAiCompatibleFixtureServer {

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val scripted = LinkedBlockingQueue<ScriptedResponse>()

    /** Bodies of every `/chat/completions` request, in arrival order (test observability). */
    val requestBodies: MutableList<String> = CopyOnWriteArrayList()

    val baseUri: URI get() = URI("http://127.0.0.1:${server.address.port}")

    init {
        server.createContext("/models") { exchange ->
            val body = """{"data":[{"id":"model-agentic"},{"id":"model-completion"}]}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/chat/completions") { exchange ->
            requestBodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val response = scripted.poll() ?: ScriptedResponse.Sse(finalTextLines("(no script)"))
            response.writeTo(exchange)
        }
        server.executor = Executors.newCachedThreadPool()
        server.start()
    }

    /** Queue the responses served (in order) to subsequent `/chat/completions` requests. */
    fun script(vararg responses: ScriptedResponse) {
        scripted.clear()
        scripted.addAll(responses.toList())
    }

    /** Build a [ResolvedProviderProfile] pointing at this fixture. */
    fun profile(id: String = "fixture-profile"): ResolvedProviderProfile =
        ResolvedProviderProfile(
            profile = OpenAiCompatibleProfile(
                id = id,
                name = "Fixture Provider",
                baseUrl = baseUri.toString(),
            ),
            baseUrl = baseUri,
            configuredValues = emptyMap(),
        )

    fun stop() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}

/** A single scripted HTTP response served for one `/chat/completions` request. */
sealed interface ScriptedResponse {
    fun writeTo(exchange: HttpExchange)

    /** A 200 streamed SSE response made of the given already-formatted SSE lines. */
    data class Sse(val lines: List<String>) : ScriptedResponse {
        override fun writeTo(exchange: HttpExchange) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0) // chunked
            exchange.responseBody.use { out ->
                lines.forEach {
                    out.write(it.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        }
    }

    /** A non-200 status (no body), optionally with a `Retry-After` header. */
    data class Status(val code: Int, val retryAfterSeconds: Long? = null) : ScriptedResponse {
        override fun writeTo(exchange: HttpExchange) {
            retryAfterSeconds?.let { exchange.responseHeaders.add("Retry-After", it.toString()) }
            exchange.sendResponseHeaders(code, -1)
            exchange.close()
        }
    }

    /**
     * A 200 that promises (via Content-Length) more bytes than it delivers, then closes — the JDK
     * HTTP client observes a premature EOF and surfaces a transport failure to the agent loop.
     * Models the "disconnect after partial output" scenario.
     */
    data class Truncated(val lines: List<String>) : ScriptedResponse {
        override fun writeTo(exchange: HttpExchange) {
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            val body = lines.joinToString("").toByteArray(Charsets.UTF_8)
            // Promise more than we send so the client detects truncation.
            exchange.sendResponseHeaders(200, body.size.toLong() + 4096L)
            exchange.responseBody.use { out ->
                out.write(body)
                out.flush()
            }
        }
    }
}

// --- SSE line builders (data: {json}\n per OpenAI streaming chunk) ---

private fun data(json: String): String = "data: $json\n"

private fun usageLine(): String =
    data("""{"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":4},"completion_tokens_details":{"reasoning_tokens":3}}}""")

private fun doneLine(): String = "data: [DONE]\n"

private fun finalTextLines(text: String): List<String> = listOf(
    data("""{"choices":[{"index":0,"delta":{"content":${jsonString(text)}}}]}"""),
    data("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""),
    usageLine(),
    doneLine(),
)

private fun jsonString(s: String): String = com.google.gson.Gson().toJson(s)

/** Response that streams plain assistant [text] and finishes. */
fun finalText(text: String): ScriptedResponse = ScriptedResponse.Sse(finalTextLines(text))

/** Response that streams a reasoning summary then assistant [text]. */
fun reasoningThenText(reasoning: String, text: String): ScriptedResponse = ScriptedResponse.Sse(
    listOf(
        data("""{"choices":[{"index":0,"delta":{"reasoning_content":${jsonString(reasoning)}}}]}"""),
        data("""{"choices":[{"index":0,"delta":{"content":${jsonString(text)}}}]}"""),
        data("""{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}"""),
        usageLine(),
        doneLine(),
    ),
)

/** Response that streams a single tool call [name] with [args] and finishes with `tool_calls`. */
fun toolCall(name: String, args: String = "{}", id: String = "call_1"): ScriptedResponse = ScriptedResponse.Sse(
    listOf(
        data("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":${jsonString(id)},"type":"function","function":{"name":${jsonString(name)},"arguments":${jsonString(args)}}}]}}]}"""),
        data("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""),
        usageLine(),
        doneLine(),
    ),
)

/** Response that streams two interleaved tool calls (indices 0 and 1) and finishes with `tool_calls`. */
fun interleavedToolCalls(
    first: Triple<String, String, String>,
    second: Triple<String, String, String>,
): ScriptedResponse = ScriptedResponse.Sse(
    listOf(
        // First fragments of both calls, then the argument tails — interleaved.
        data("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":${jsonString(first.third)},"type":"function","function":{"name":${jsonString(first.first)},"arguments":""}}]}}]}"""),
        data("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":${jsonString(second.third)},"type":"function","function":{"name":${jsonString(second.first)},"arguments":""}}]}}]}"""),
        data("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":${jsonString(first.second)}}}]}}]}"""),
        data("""{"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":${jsonString(second.second)}}}]}}]}"""),
        data("""{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}"""),
        usageLine(),
        doneLine(),
    ),
)

/** A 429 rate-limit response carrying a `Retry-After` header. */
fun rateLimited(retryAfterSeconds: Long): ScriptedResponse = ScriptedResponse.Status(429, retryAfterSeconds)

/** A 401 unauthorized response. */
fun unauthorized(): ScriptedResponse = ScriptedResponse.Status(401)

/** A 500 server error emitted before any output. */
fun serverError(): ScriptedResponse = ScriptedResponse.Status(500)

/** Streams a partial assistant fragment then disconnects mid-stream. */
fun disconnectAfterPartial(partial: String): ScriptedResponse = ScriptedResponse.Truncated(
    listOf(data("""{"choices":[{"index":0,"delta":{"content":${jsonString(partial)}}}]}""")),
)
