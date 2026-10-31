package com.example.fireairmouse

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/** Tiny UDP client. Every message is "PIN|COMMAND|ARG". */
class Net {
    companion object { const val PORT = 9876 }

    private val sock = DatagramSocket().apply { broadcast = true }
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var addr: InetAddress? = null
    @Volatile var pin = ""
    @Volatile var lastPong = 0L
    @Volatile var lastNoPin = 0L
    @Volatile var tvName = ""
    @Volatile var keyMode = ""
    @Volatile var tvAdb = ""
    @Volatile var onRelayKey: ((Int) -> Unit)? = null
    @Volatile var onFound: ((String, String) -> Unit)? = null
    val hasTarget get() = addr != null

    init {
        Thread {
            val buf = ByteArray(512)
            while (!sock.isClosed) {
                try {
                    val p = DatagramPacket(buf, buf.size); sock.receive(p)
                    val m = String(p.data, 0, p.length)
                    when {
                        m.startsWith("PONG|") -> { lastPong = SystemClock.elapsedRealtime(); val q = m.substring(5).split("|"); tvName = q[0]; keyMode = q.getOrElse(1) { "" }; tvAdb = q.drop(2).joinToString("|") }
                        m == "NOPIN" -> lastNoPin = SystemClock.elapsedRealtime()
                        m.startsWith("KEY|") -> m.substring(4).toIntOrNull()?.let { onRelayKey?.invoke(it) }
                        m.startsWith("HELLO_R|") -> onFound?.invoke(p.address.hostAddress ?: "", m.substring(8))
                    }
                } catch (_: Exception) { }
            }
        }.apply { isDaemon = true }.start()
    }

    fun setTarget(ip: String) { exec.execute { addr = try { InetAddress.getByName(ip.trim()) } catch (_: Exception) { null } } }

    fun send(cmd: String) = exec.execute {
        val a = addr ?: return@execute
        val b = "$pin|$cmd".toByteArray()
        try { sock.send(DatagramPacket(b, b.size, a, PORT)) } catch (_: Exception) { }
    }

    fun discover() = exec.execute {
        val b = "HELLO".toByteArray()
        try { sock.send(DatagramPacket(b, b.size, InetAddress.getByName("255.255.255.255"), PORT)) } catch (_: Exception) { }
    }
}
