package com.example.ommstall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Serves tiles from inside the app, so the repro needs no network at all.
 *
 * Two routes, because that is the shape the problem was first seen in:
 *
 *  - `/fast/{z}/{x}/{y}.png` answers immediately
 *  - `/slow/{z}/{x}/{y}.png` answers after [slowDelayMs]
 *
 * The slow route stands in for a tile the app rasterises itself, which on an
 * older phone takes a few hundred milliseconds. That is long enough for the
 * camera to move on while the tile is still in flight, so the layer cancels
 * the load -- which is what this repro is about.
 *
 * Every request and every cancellation is counted, so the log can say which
 * layer is still asking for tiles and which one has gone quiet.
 */
class SlowTileServer(private val slowDelayMs: Long) {

    private val socket = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
    private val fastRequests = AtomicInteger()
    private val slowRequests = AtomicInteger()
    private val cancelled = AtomicInteger()
    private val orphaned = AtomicInteger()

    /**
     * The `?v=` the live overlay layer carries. Anything else arriving on the
     * slow route belongs to a layer the app has already removed from the map.
     */
    @Volatile
    private var currentVersion = 0

    val port: Int get() = socket.localPort

    fun fastUrlTemplate(): String = "http://127.0.0.1:$port/fast/{z}/{x}/{y}.png"

    fun slowUrlTemplate(): String = "http://127.0.0.1:$port/slow/{z}/{x}/{y}.png"

    fun fastCount(): Int = fastRequests.get()

    fun slowCount(): Int = slowRequests.get()

    /** Responses the client stopped waiting for -- i.e. loads the layer cancelled. */
    fun cancelledCount(): Int = cancelled.get()

    /** Tiles fetched for layers that were already removed from the map. */
    fun orphanCount(): Int = orphaned.get()

    fun setCurrentVersion(version: Int) {
        currentVersion = version
    }

    fun start() {
        thread(isDaemon = true, name = "tile-server") {
            while (true) {
                val client =
                    try {
                        socket.accept()
                    } catch (error: Exception) {
                        return@thread
                    }
                thread(isDaemon = true) { handle(client) }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            val input = socket.getInputStream()
            val line = StringBuilder()
            while (!line.endsWith("\r\n")) {
                val byte = input.read()
                if (byte == -1) return
                line.append(byte.toChar())
            }
            val path = line.toString().split(" ").getOrNull(1) ?: return
            val slow = path.startsWith("/slow")
            if (slow) slowRequests.incrementAndGet() else fastRequests.incrementAndGet()
            val version = path.substringAfter("?v=", "").toIntOrNull()
            val orphan = slow && version != null && version != currentVersion
            if (orphan) orphaned.incrementAndGet()
            Log.i(TAG, "REQ ${if (slow) "slow" else "fast"} $path${if (orphan) "  <- layer already removed" else ""}")

            if (slow && slowDelayMs > 0) Thread.sleep(slowDelayMs)

            val body = if (slow) slowTile else fastTile
            val head =
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: image/png\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n\r\n"
            try {
                socket.getOutputStream().apply {
                    write(head.toByteArray())
                    write(body)
                    flush()
                }
            } catch (error: Exception) {
                // The layer cancelled this load and closed the connection.
                val total = cancelled.incrementAndGet()
                Log.i(TAG, "CANCELLED $path (total=$total)")
            }
        }
    }

    private companion object {
        const val TAG = "OmmStall"
        const val TILE_PX = 256

        /** A plain tile, so something visible arrives. */
        fun tile(
            fill: Int,
            border: Int,
        ): ByteArray {
            val bitmap = Bitmap.createBitmap(TILE_PX, TILE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(fill)
            val paint =
                Paint().apply {
                    color = border
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }
            canvas.drawRect(2f, 2f, TILE_PX - 2f, TILE_PX - 2f, paint)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            return out.toByteArray()
        }

        val fastTile: ByteArray by lazy { tile(Color.argb(255, 232, 232, 232), Color.argb(255, 180, 180, 180)) }
        val slowTile: ByteArray by lazy { tile(Color.argb(110, 40, 120, 220), Color.argb(200, 20, 60, 160)) }
    }
}
