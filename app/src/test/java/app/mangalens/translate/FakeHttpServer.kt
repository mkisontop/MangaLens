package app.mangalens.translate

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A plain HTTP/1.1 server on the loopback interface, one connection per
 * request, so the Gemini client can be tested end to end — real sockets,
 * real streaming, real cancellation — without leaving the machine.
 */
internal class FakeHttpServer(private val handler: (Exchange) -> Unit) : Closeable {

    class Exchange(
        val method: String,
        /** Path and query, as sent. */
        val target: String,
        /** Header names lower-cased. */
        val headers: Map<String, String>,
        val body: String,
        private val input: InputStream,
        private val out: OutputStream,
    ) {
        fun respond(code: Int, body: String, contentType: String = "application/json") {
            val bytes = body.toByteArray()
            out.write(
                ("HTTP/1.1 $code X\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray()
            )
            out.write(bytes)
            out.flush()
        }

        /** Opens a close-delimited event stream; follow with [event] calls. */
        fun startEvents() {
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
            out.flush()
        }

        fun event(json: String) {
            out.write("data: $json\r\n\r\n".toByteArray())
            out.flush()
        }

        /** Blocks until the client hangs up. */
        fun awaitHangUp() {
            runCatching { while (input.read() >= 0) Unit }
        }
    }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    val exchanges = CopyOnWriteArrayList<Exchange>()

    /** Set when a client hung up on a response still being written. */
    @Volatile
    var hangUps = 0

    val base: String get() = "http://127.0.0.1:${server.localPort}/v1beta/models/"

    init {
        thread(isDaemon = true, name = "fake-http") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.use { exchange(it) }
    }

    private fun exchange(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val parts = requestLine.split(' ')
        val exchange = Exchange(
            parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, headers,
            // Decoded as a server would: request bodies may come gzipped.
            if (headers["content-encoding"] == "gzip") {
                java.util.zip.GZIPInputStream(body.inputStream(0, read)).readBytes().toString(Charsets.UTF_8)
            } else {
                String(body, 0, read)
            },
            input, socket.getOutputStream(),
        )
        exchanges += exchange
        try {
            handler(exchange)
        } catch (e: java.io.IOException) {
            hangUps++
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString()
            if (c == '\n'.code) return buf.toString().removeSuffix("\r")
            buf.write(c)
        }
    }

    override fun close() {
        runCatching { server.close() }
    }
}
