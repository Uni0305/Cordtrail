package com.github.uni0305.cordtrail.storage

import com.clickhouse.client.api.Client
import com.github.uni0305.cordtrail.config.ClickHouseConfig
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** A fake ClickHouse HTTP endpoint for tests. Records requests and responds with the status code chosen by [responder]. */
class FakeClickHouseServer(
    @Volatile var responder: (RecordedRequest) -> Response = { Response.OK },
) : AutoCloseable {
    data class RecordedRequest(val method: String, val query: Map<String, String>, val body: String)

    data class Response(val status: Int = 200, val body: String = "", val delayMillis: Long = 0) {
        companion object {
            val OK = Response()
            fun error(message: String = "Code: 60. DB::Exception: boom") = Response(status = 500, body = message)
        }
    }

    val requests = CopyOnWriteArrayList<RecordedRequest>()

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val request = RecordedRequest(exchange.requestMethod, parseQuery(exchange.requestURI.rawQuery), body)
            requests += request
            val response = responder(request)
            if (response.delayMillis > 0) Thread.sleep(response.delayMillis)
            val bytes = response.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.write(bytes)
            exchange.close()
        }
        start()
    }

    val url: String get() = "http://${server.address.hostString}:${server.address.port}"

    fun config(database: String = "test_db", timeout: Duration = Duration.ofSeconds(10)) =
        ClickHouseConfig(url, "default", "", database, timeout)

    fun client(timeout: Duration = Duration.ofSeconds(10)): Client = createClickHouseClient(config(timeout = timeout))

    private fun parseQuery(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val (key, value) = it.split('=', limit = 2).let { parts -> parts[0] to parts.getOrElse(1) { "" } }
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    override fun close() = server.stop(0)
}
