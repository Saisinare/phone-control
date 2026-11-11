package com.playground.phonecontrol

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A small HTTP server that speaks MCP Streamable HTTP at /<secret>/mcp.
 * Every request is answered with one JSON body, which the spec allows in place of an event stream.
 */
class McpServer(private val port: Int, private val lan: Boolean) {

    private var listener: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    @Throws(IOException::class)
    fun start() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(
            if (lan) InetSocketAddress(port)
            else InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port)
        )
        listener = s
        thread(name = "mcp-accept", isDaemon = true) {
            while (!s.isClosed) {
                val client = try {
                    s.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute {
                    client.use {
                        try {
                            handle(it)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
    }

    fun stop() {
        try {
            listener?.close()
        } catch (_: IOException) {
        }
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- HTTP

    private fun handle(client: Socket) {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val out = client.getOutputStream()

        val request = (readLine(input) ?: return).split(' ')
        if (request.size < 3) return send(out, 400, "Bad Request", "Bad request", "text/plain")
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
            if (headers.size > 100) return send(out, 431, "Request Header Fields Too Large", "Too many headers", "text/plain")
        }
        if (headers["expect"]?.lowercase() == "100-continue") {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }
        // The body is read before answering so the reply is not lost to a connection reset.
        val body = readBody(input, headers)
            ?: return send(out, 413, "Payload Too Large", "Request too large", "text/plain")

        val text = String(body, Charsets.UTF_8).trim()

        /** Answers and records the request (secret masked) so connection problems can be traced. */
        fun answer(code: Int, reason: String, content: String?, type: String = "application/json", extra: String = "") {
            val methods = Regex("\"method\"\\s*:\\s*\"([^\"]+)\"").findAll(text).joinToString(",") { it.groupValues[1] }
            Config.trace("${request[0]} ${request[1].replace(Config.secret(), "<secret>")} -> $code $methods".trim())
            send(out, code, reason, if (request[0] == "HEAD") null else content, type, extra)
        }

        val path = request[1].substringBefore('?').trimEnd('/')
        val expected = "/${Config.secret()}/mcp"
        if (!MessageDigest.isEqual(path.toByteArray(), expected.toByteArray())) {
            return answer(404, "Not Found", "Not Found", "text/plain")
        }
        if (request[0] != "POST") {
            return answer(405, "Method Not Allowed", "Method Not Allowed", "text/plain", "Allow: POST\r\n")
        }

        val reply: String? = try {
            if (text.startsWith("[")) {
                val batch = JSONArray(text)
                val replies = JSONArray()
                for (i in 0 until batch.length()) {
                    batch.optJSONObject(i)?.let { m -> rpc(m)?.let { replies.put(it) } }
                }
                if (replies.length() > 0) replies.toString() else null
            } else {
                rpc(JSONObject(text))?.toString()
            }
        } catch (e: JSONException) {
            return answer(400, "Bad Request", rpcError(JSONObject.NULL, -32700, "Parse error").toString())
        }
        if (reply == null) answer(202, "Accepted", null) else answer(200, "OK", reply)
    }

    private fun send(
        out: OutputStream, code: Int, reason: String, body: String?,
        type: String = "application/json", extraHeaders: String = "",
    ) {
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val head = StringBuilder("HTTP/1.1 $code $reason\r\n")
        if (bytes != null) head.append("Content-Type: $type\r\n")
        head.append("Content-Length: ${bytes?.size ?: 0}\r\n")
        head.append(extraHeaders)
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (bytes != null) out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) return null
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val data = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(data, got, n - got)
            if (r < 0) return null
            got += r
        }
        return data
    }

    /** Returns null when the body is larger than [MAX_BODY] or malformed. */
    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray? {
        val body = ByteArrayOutputStream()
        if (headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true) {
            while (true) {
                val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: return null
                if (size == 0) {
                    while (true) {
                        val trailer = readLine(input) ?: break
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                if (size < 0 || body.size() + size > MAX_BODY) return null
                body.write(readExactly(input, size) ?: return null)
                readLine(input)
            }
        } else {
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length < 0 || length > MAX_BODY) return null
            if (length > 0) body.write(readExactly(input, length) ?: return null)
        }
        return body.toByteArray()
    }

    // ------------------------------------------------------------ JSON-RPC

    private fun rpcError(id: Any, code: Int, message: String): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))

    /** Answers one message; notifications and client replies get no answer. */
    private fun rpc(m: JSONObject): JSONObject? {
        val method = m.optString("method", "")
        if (!m.has("id") || m.isNull("id") || method.isEmpty()) return null
        val id = m.get("id")
        val params = m.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> {
                val asked = params.optString("protocolVersion", "")
                val client = params.optJSONObject("clientInfo")?.optString("name", "").orEmpty()
                Config.log("connected", "client" to client.ifEmpty { "unknown" })
                JSONObject()
                    .put("protocolVersion", if (asked in VERSIONS) asked else VERSIONS[0])
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "android-control").put("version", "1.0"))
                    .put("instructions", Tools.INSTRUCTIONS)
            }
            "ping" -> JSONObject()
            "tools/list" -> JSONObject().put("tools", Tools.list())
            "tools/call" -> Tools.run(params.optString("name", ""), params.optJSONObject("arguments") ?: JSONObject())
            else -> return rpcError(id, -32601, "Method not found: $method")
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    private companion object {
        const val MAX_BODY = 1_000_000
        val VERSIONS = listOf("2025-06-18", "2025-11-25", "2025-03-26", "2024-11-05")
    }
}
