package io.github.wilfredluijk.navalsdk

import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.wilfredluijk.navalsdk.internal.Wire
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64

/** Small RFC6455 test peer. Exercises the actual JDK WebSocket client. */
internal class LoopbackServer : AutoCloseable {
    private val listener =
        ServerSocket(0, 8, InetAddress.getLoopbackAddress()).apply { soTimeout = 5000 }
    private var socket: Socket? = null
    val url = "ws://localhost:${listener.localPort}/bot"
    private val input
        get() = checkNotNull(socket).getInputStream()

    private val output
        get() = checkNotNull(socket).getOutputStream()

    fun accept() {
        socket?.close()
        socket = listener.accept().apply { soTimeout = 5000 }
        val bytes = ByteArrayOutputStream()
        while (!bytes.toString(Charsets.US_ASCII).endsWith("\r\n\r\n")) {
            val b = input.read()
            check(b >= 0 && bytes.size() < 16384)
            bytes.write(b)
        }
        val key =
            bytes
                .toString(Charsets.US_ASCII)
                .lineSequence()
                .first { it.startsWith("sec-websocket-key:", true) }
                .substringAfter(':')
                .trim()
        val accept =
            Base64.getEncoder()
                .encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest(
                            (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(
                                Charsets.US_ASCII
                            )
                        )
                )
        output.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
        )
        output.flush()
    }

    private fun read(): Int = input.read().also { if (it < 0) throw EOFException() }

    fun receive(): ObjectNode {
        while (true) {
            val first = read()
            val second = read()
            val opcode = first and 15
            var size = (second and 127).toLong()
            if (size == 126L) size = ((read() shl 8) or read()).toLong()
            else if (size == 127L) {
                size = 0
                repeat(8) { size = (size shl 8) or read().toLong() }
            }
            check(size in 0..1_048_576)
            val mask = if ((second and 128) != 0) input.readNBytes(4) else byteArrayOf()
            val data = input.readNBytes(size.toInt())
            check(data.size == size.toInt())
            if (mask.isNotEmpty())
                data.indices.forEach {
                    data[it] = (data[it].toInt() xor mask[it % 4].toInt()).toByte()
                }
            if (opcode == 1) return Wire.parse(data.toString(Charsets.UTF_8))
            if (opcode == 8) throw EOFException("client closed")
        }
    }

    private fun frame(opcode: Int, bytes: ByteArray) {
        output.write(opcode)
        if (bytes.size < 126) output.write(bytes.size)
        else {
            output.write(126)
            output.write(bytes.size shr 8)
            output.write(bytes.size and 255)
        }
        output.write(bytes)
        output.flush()
    }

    fun send(value: ObjectNode) = sendText(value.toString())

    fun sendText(value: String) = frame(0x81, value.toByteArray(Charsets.UTF_8))

    fun fragmented(value: ObjectNode) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val half = bytes.size / 2
        frame(0x01, bytes.copyOfRange(0, half))
        frame(0x80, bytes.copyOfRange(half, bytes.size))
    }

    fun binary() = frame(0x82, byteArrayOf(1, 2, 3))

    fun ping() = frame(0x89, byteArrayOf(7))

    fun closePeer() {
        frame(0x88, byteArrayOf(3, 232.toByte()))
        socket?.close()
    }

    override fun close() {
        socket?.close()
        listener.close()
    }
}
