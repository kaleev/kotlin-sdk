package client

import org.jetbrains.kotlinx.mcp.ClientCapabilities
import org.jetbrains.kotlinx.mcp.CreateMessageRequest
import org.jetbrains.kotlinx.mcp.CreateMessageResult
import org.jetbrains.kotlinx.mcp.EmptyJsonObject
import org.jetbrains.kotlinx.mcp.Implementation
import InMemoryTransport
import org.jetbrains.kotlinx.mcp.InitializeRequest
import org.jetbrains.kotlinx.mcp.InitializeResult
import org.jetbrains.kotlinx.mcp.JSONRPCMessage
import org.jetbrains.kotlinx.mcp.JSONRPCRequest
import org.jetbrains.kotlinx.mcp.JSONRPCResponse
import org.jetbrains.kotlinx.mcp.LATEST_PROTOCOL_VERSION
import org.jetbrains.kotlinx.mcp.ListResourcesRequest
import org.jetbrains.kotlinx.mcp.ListResourcesResult
import org.jetbrains.kotlinx.mcp.ListRootsRequest
import org.jetbrains.kotlinx.mcp.ListToolsRequest
import org.jetbrains.kotlinx.mcp.ListToolsResult
import org.jetbrains.kotlinx.mcp.LoggingLevel
import org.jetbrains.kotlinx.mcp.LoggingMessageNotification
import org.jetbrains.kotlinx.mcp.Method
import org.jetbrains.kotlinx.mcp.Role
import org.jetbrains.kotlinx.mcp.SUPPORTED_PROTOCOL_VERSIONS
import org.jetbrains.kotlinx.mcp.ServerCapabilities
import org.jetbrains.kotlinx.mcp.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.kotlinx.mcp.client.Client
import org.jetbrains.kotlinx.mcp.client.ClientOptions
import org.junit.jupiter.api.Test
import org.jetbrains.kotlinx.mcp.server.Server
import org.jetbrains.kotlinx.mcp.server.ServerOptions
import org.jetbrains.kotlinx.mcp.shared.Transport
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ClientTest {
    @Test
    fun `should initialize with matching protocol version`() = runTest {
        var initialied = false
        var clientTransport = object : Transport {
            override suspend fun start() {}

            override suspend fun send(message: JSONRPCMessage) {
                if (message !is JSONRPCRequest) return
                initialied = true
                val result = InitializeResult(
                    protocolVersion = LATEST_PROTOCOL_VERSION,
                    capabilities = ServerCapabilities(),
                    serverInfo = Implementation(
                        name = "test",
                        version = "1.0"
                    )
                )

                val response = JSONRPCResponse(
                    id = message.id,
                    result = result
                )

                onMessage?.invoke(response)
            }

            override suspend fun close() {
            }

            override var onClose: (() -> Unit)? = null
            override var onError: ((Throwable) -> Unit)? = null
            override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
        }

        val client = Client(
            clientInfo = Implementation(
                name = "test client",
                version = "1.0"
            ),
            options = ClientOptions(
                capabilities = ClientCapabilities(
                    sampling = EmptyJsonObject
                )
            )
        )

        client.connect(clientTransport)
        assertTrue(initialied)
    }

    @Test
    fun `should initialize with supported older protocol version`() = runTest {
        val OLD_VERSION = SUPPORTED_PROTOCOL_VERSIONS[1]
        var clientTransport = object : Transport {
            override suspend fun start() {}

            override suspend fun send(message: JSONRPCMessage) {
                if (message !is JSONRPCRequest) return
                check(message.method == Method.Defined.Initialize.value)

                val result = InitializeResult(
                    protocolVersion = OLD_VERSION,
                    capabilities = ServerCapabilities(),
                    serverInfo = Implementation(
                        name = "test",
                        version = "1.0"
                    )
                )

                val response = JSONRPCResponse(
                    id = message.id,
                    result = result
                )
                onMessage?.invoke(response)
            }

            override suspend fun close() {
            }

            override var onClose: (() -> Unit)? = null
            override var onError: ((Throwable) -> Unit)? = null
            override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
        }

        val client = Client(
            clientInfo = Implementation(
                name = "test client",
                version = "1.0"
            ),
            options = ClientOptions(
                capabilities = ClientCapabilities(
                    sampling = EmptyJsonObject
                )
            )
        )

        client.connect(clientTransport)
        assertEquals(
            Implementation("test", "1.0"),
            client.getServerVersion()
        )
    }

    @Test
    fun `should reject unsupported protocol version`() = runTest {
        var closed = false
        val clientTransport = object : Transport {
            override suspend fun start() {}

            override suspend fun send(message: JSONRPCMessage) {
                if (message !is JSONRPCRequest) return
                check(message.method == Method.Defined.Initialize.value)

                val result = InitializeResult(
                    protocolVersion = "invalid-version",
                    capabilities = ServerCapabilities(),
                    serverInfo = Implementation(
                        name = "test",
                        version = "1.0"
                    )
                )

                val response = JSONRPCResponse(
                    id = message.id,
                    result = result
                )

                onMessage?.invoke(response)
            }

            override suspend fun close() {
                closed = true
            }

            override var onClose: (() -> Unit)? = null
            override var onError: ((Throwable) -> Unit)? = null
            override var onMessage: (suspend (JSONRPCMessage) -> Unit)? = null
        }

        val client = Client(
            clientInfo = Implementation(
                name = "test client",
                version = "1.0"
            ),
            options = ClientOptions()
        )

        assertFailsWith<IllegalStateException>("Server's protocol version is not supported: invalid-version") {
            client.connect(clientTransport)
        }

        assertTrue(closed)
    }

    @Test
    fun `should respect server capabilities`() = runTest {
        val serverOptions = ServerOptions(
            capabilities = ServerCapabilities(
                resources = ServerCapabilities.Resources(null, null),
                tools = ServerCapabilities.Tools(null)
            )
        )
        val server = Server(
            Implementation(name = "test server", version = "1.0"),
            serverOptions
        )

        server.setRequestHandler<InitializeRequest>(Method.Defined.Initialize) { request, _ ->
            InitializeResult(
                protocolVersion = LATEST_PROTOCOL_VERSION,
                capabilities = ServerCapabilities(
                    resources = ServerCapabilities.Resources(null, null),
                    tools = ServerCapabilities.Tools(null)
                ),
                serverInfo = Implementation(name = "test", version = "1.0")
            )
        }

        server.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { request, _ ->
            ListResourcesResult(resources = emptyList(), nextCursor = null)
        }

        server.setRequestHandler<ListToolsRequest>(Method.Defined.ToolsList) { request, _ ->
            ListToolsResult(tools = emptyList(), nextCursor = null)
        }

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()

        val client = Client(
            clientInfo = Implementation(name = "test client", version = "1.0"),
            options = ClientOptions(
                capabilities = ClientCapabilities(sampling = EmptyJsonObject),
            )
        )

        listOf(
            launch {
                client.connect(clientTransport)
            },
            launch {
                server.connect(serverTransport)
            }
        ).joinAll()

        // Server supports resources and tools, but not prompts
        val caps = client.getServerCapabilities()
        assertEquals(ServerCapabilities.Resources(null, null), caps?.resources)
        assertEquals(ServerCapabilities.Tools(null), caps?.tools)
        assertTrue(caps?.prompts == null) // or check that prompts are absent

        // These should not throw
        client.listResources()
        client.listTools()

        // This should fail because prompts are not supported
        val ex = assertFailsWith<IllegalStateException> {
            client.listPrompts()
        }
        assertTrue(ex.message?.contains("Server does not support prompts") == true)
    }

    @Test
    fun `should respect client notification capabilities`() = runTest {
        val server = Server(
            Implementation(name = "test server", version = "1.0"),
            ServerOptions(capabilities = ServerCapabilities())
        )

        val client = Client(
            clientInfo = Implementation(name = "test client", version = "1.0"),
            options = ClientOptions(
                capabilities = ClientCapabilities(
                    roots = ClientCapabilities.Roots(listChanged = true)
                )
            )
        )

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()

        listOf(
            launch {
                client.connect(clientTransport)
                println("Client connected")
            },
            launch {
                server.connect(serverTransport)
                println("Server connected")
            }
        ).joinAll()

        // This should not throw because the client supports roots.listChanged
        client.sendRootsListChanged()

        // Create a new client without the roots.listChanged capability
        val clientWithoutCapability = Client(
            clientInfo = Implementation(name = "test client without capability", version = "1.0"),
            options = ClientOptions(
                capabilities = ClientCapabilities(),
                //                enforceStrictCapabilities = true // TODO()
            )
        )

        clientWithoutCapability.connect(clientTransport)
        // Using the same transport pair might not be realistic - in a real scenario you'd create another pair.
        // Adjust if necessary.

        // This should fail
        val ex = assertFailsWith<IllegalStateException> {
            clientWithoutCapability.sendRootsListChanged()
        }
        assertTrue(ex.message?.startsWith("Client does not support") == true)
    }

    @Test
    fun `should respect server notification capabilities`() = runTest {
        val server = Server(
            Implementation(name = "test server", version = "1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    logging = EmptyJsonObject,
                    resources = ServerCapabilities.Resources(listChanged = true, subscribe = null)
                )
            )
        )

        val client = Client(
            clientInfo = Implementation(name = "test client", version = "1.0"),
            options = ClientOptions(
                capabilities = ClientCapabilities()
            )
        )

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()

        listOf(
            launch {
                client.connect(clientTransport)
                println("Client connected")
            },
            launch {
                server.connect(serverTransport)
                println("Server connected")
            }
        ).joinAll()

        // These should not throw
        val jsonObject = buildJsonObject {
            put("name", "John")
            put("age", 30)
            put("isStudent", false)
        }
        server.sendLoggingMessage(
            LoggingMessageNotification(
                level = LoggingLevel.info,
                data = jsonObject
            )
        )
        server.sendResourceListChanged()

        // This should fail because the server doesn't have the tools capability
        val ex = assertFailsWith<IllegalStateException> {
            server.sendToolListChanged()
        }
        assertTrue(ex.message?.contains("Server does not support notifying of tool list changes") == true)
    }

    @Test
    fun `should handle client cancelling a request`() = runTest {
        val server = Server(
            Implementation(name = "test server", version = "1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(resources = ServerCapabilities.Resources(listChanged = null, subscribe = null))
            )
        )

        val def = CompletableDeferred<Unit>()
        val defTimeOut = CompletableDeferred<Unit>()
        server.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { _, extra ->
            // Simulate delay
            def.complete(Unit)
            try {
                kotlinx.coroutines.delay(1000)
            } catch (e: CancellationException) {
                defTimeOut.complete(Unit)
                throw e
            }
            fail("Shouldn't have been called")
            ListResourcesResult(resources = emptyList())
        }

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()

        val client = Client(
            clientInfo = Implementation(name = "test client", version = "1.0"),
            options = ClientOptions(capabilities = ClientCapabilities())
        )

        listOf(
            launch {
                client.connect(clientTransport)
                println("Client connected")
            },
            launch {
                server.connect(serverTransport)
                println("Server connected")
            }
        ).joinAll()

        val defCancel = CompletableDeferred<Unit>()
        val job = launch {
            try {
                client.listResources()
            } catch (e: CancellationException) {
                defCancel.complete(Unit)
                assertEquals("Cancelled by test", e.message)
            }
        }
        def.await()
        runCatching { job.cancel("Cancelled by test") }
        defCancel.await()
        defTimeOut.await()
    }

    @Test
    fun `should handle request timeout`() = runTest {
        val server = Server(
            Implementation(name = "test server", version = "1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(resources = ServerCapabilities.Resources(listChanged = null, subscribe = null))
            )
        )

        server.setRequestHandler<ListResourcesRequest>(Method.Defined.ResourcesList) { _, extra ->
            // Simulate a delayed response
            // Wait ~100ms unless cancelled
            try {
                kotlinx.coroutines.withTimeout(100L) {
                    // Just delay here, if timeout is 0 on client side this won't return in time
                    kotlinx.coroutines.delay(100)
                }
            } catch (e: Exception) {
                // If aborted, just rethrow or return early
            }
            ListResourcesResult(resources = emptyList())
        }

        val (clientTransport, serverTransport) = InMemoryTransport.createLinkedPair()
        val client = Client(
            clientInfo = Implementation(name = "test client", version = "1.0"),
            options = ClientOptions(capabilities = ClientCapabilities())
        )

        listOf(
            launch {
                client.connect(clientTransport)
                println("Client connected")
            },
            launch {
                server.connect(serverTransport)
                println("Server connected")
            }
        ).joinAll()

        // Request with 1 msec timeout should fail immediately
        val ex = assertFailsWith<Exception> {
            kotlinx.coroutines.withTimeout(1) {
                client.listResources()
            }
        }
        assertTrue(ex is TimeoutCancellationException)
    }

    @Test
    fun `should only allow setRequestHandler for declared capabilities`() = runTest {
        val client = Client(
            clientInfo = Implementation(
                name = "test client",
                version = "1.0"
            ),
            options = ClientOptions(
                capabilities = ClientCapabilities(
                    sampling = EmptyJsonObject
                )
            )
        )

        client.setRequestHandler<CreateMessageRequest>(Method.Defined.SamplingCreateMessage) { request, _ ->
            CreateMessageResult(
                model = "test-model",
                role = Role.assistant,
                content = TextContent(
                    text = "Test response"
                )
            )
        }

        assertFailsWith<IllegalStateException>("Client does not support roots capability (required for RootsList)") {
            client.setRequestHandler<ListRootsRequest>(Method.Defined.RootsList) { _, _ -> null }
        }
    }


}