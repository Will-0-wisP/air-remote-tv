package com.example.fireairmouse

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends REAL key events (DPAD, BACK, HOME, MENU, media, volume ...) through the Fire Stick's ADB daemon (port 5555;
 * needs Developer options > ADB debugging ON and one "Allow USB debugging?" approval on the TV).
 * Used from the TV app (talks to itself via 127.0.0.1 / its LAN IP) and from the phone app (talks to the TV's IP,
 * exactly like "adb connect" from a computer). Keys arriving while a command runs are batched into one call.
 */
object AdbRemote {
    @Volatile var status = "Not connected yet"
    @Volatile var lastReadyAt = 0L
    @Volatile private var lastUsed = 0L
    @Volatile private var userBlocked = false     // prompt was refused / timed out: do not auto-retry until asked
    @Volatile private var rxLog = ""
    @Volatile var blocked = false          // TV side: the stick drops connections from its own apps
    @Volatile var ready = false
        private set

    private const val A_CNXN = 0x4e584e43; private const val A_AUTH = 0x48545541; private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f; private const val A_CLSE = 0x45534c43; private const val A_WRTE = 0x45545257
    private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)
    private class Variant(val version: Int, val maxData: Int, val name: String, val banner: String)
    private val VARIANTS = listOf(
        Variant(0x01000001, 256 * 1024, "v2", "host::\u0000"),
        Variant(0x01000000, 4096, "v1", "host::\u0000"),
        Variant(0x01000001, 1024 * 1024, "v3", "host::features=shell_v2,cmd\u0000"))

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "fam-adb").apply { isDaemon = true } }
    private val queue = ConcurrentLinkedQueue<Int>()
    private val pumping = AtomicBoolean(false)
    private val connecting = AtomicBoolean(false)
    private val lock = Any()
    private var appCtx: Context? = null
    private var lastTry = 0L
    private var hosts: List<String> = listOf("127.0.0.1")
    @Volatile private var stage = "idle"
    @Volatile private var trace = ""
    private var sock: Socket? = null
    private var inp: DataInputStream? = null
    private var out: OutputStream? = null
    private var priv: PrivateKey? = null
    private var pub: RSAPublicKey? = null
    private var nextId = 1
    private var peerMax = 4096

    fun init(ctx: Context) { if (appCtx == null) appCtx = ctx.applicationContext }
    fun setHosts(h: List<String>) { hosts = h }

    fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress
    } catch (_: Exception) { null }

    /** On the TV: try the loopback address first, then the TV's own LAN address. */
    fun useTvHosts() { hosts = listOfNotNull("127.0.0.1", lanIp()) }

    fun connect(ctx: Context) {
        init(ctx)
        if (ready || blocked || !connecting.compareAndSet(false, true)) return
        lastTry = SystemClock.elapsedRealtime()
        exec.execute { try { run("true") } finally { connecting.set(false) } }
    }

    /** Starts over without blocking the caller. Does nothing while an approval prompt is already pending. */
    fun forceReconnect(ctx: Context) {
        init(ctx); blocked = false; userBlocked = false
        if (!connecting.compareAndSet(false, true)) return
        lastTry = SystemClock.elapsedRealtime()
        status = "Reconnecting…"
        exec.execute {
            try { synchronized(lock) { closeQuietly(); ready = false }; run("true") } finally { connecting.set(false) }
        }
    }

    fun retrySoon(ctx: Context) { if (!userBlocked && SystemClock.elapsedRealtime() - lastTry > 8_000) connect(ctx) }

    /** Keeps an idle link from going stale. */
    fun keepAlive() {
        if (ready && !pumping.get() && SystemClock.elapsedRealtime() - lastUsed > 15_000) exec.execute { run("true") }
    }

    fun key(code: Int) { queue.add(code); pump() }

    fun text(s: String) {
        val esc = s.replace("'", "'\\''").replace(" ", "%s")
        exec.execute { run("input text '$esc'") }
    }

    private fun pump() {
        if (!pumping.compareAndSet(false, true)) return
        exec.execute {
            try {
                while (true) {
                    val batch = ArrayList<Int>()
                    while (batch.size < 10) { batch.add(queue.poll() ?: break) }
                    if (batch.isEmpty()) break
                    run("input keyevent " + batch.joinToString(" "))
                }
            } finally { pumping.set(false); if (queue.isNotEmpty()) pump() }
        }
    }

    // ---------------- connection ----------------
    private fun runOnce(cmd: String): String {
        val fresh = sock == null
        if (fresh) { loadKeys(); rxLog = ""; openConnection() }
        stage = if (fresh) "shell (new link)" else "shell (reused link)"
        val out = shellRaw(cmd)
        ready = true; lastReadyAt = SystemClock.elapsedRealtime(); lastUsed = lastReadyAt
        status = "Real remote keys: ready ✔"
        return out
    }

    private fun fail(e: Exception) {
        ready = false; status = describe(e)
        if (stage == "sent CNXN" && e is java.io.EOFException && hosts.contains("127.0.0.1")) blocked = true
        if (stage == "waiting for approval" || e is SocketTimeoutException) userBlocked = true
    }

    /** If an old link went stale (Wi-Fi sleep, idle), reconnect once and repeat the command instead of giving up. */
    private fun runCapture(cmd: String): String? = synchronized(lock) {
        val reused = sock != null
        try { runOnce(cmd) }
        catch (e: Exception) {
            closeQuietly()
            if (reused && e is IOException) {
                try { runOnce(cmd) } catch (e2: Exception) { closeQuietly(); fail(e2); null }
            } else { fail(e); null }
        }
    }

    private fun run(cmd: String): Boolean = runCapture(cmd) != null

    /** For setup scripts: runs a shell command on the stick and returns its output (call from [task]). */
    fun sh(cmd: String): String? = runCapture(cmd)

    /** Runs a multi-step job on the ADB thread (never on the UI thread). */
    fun task(block: () -> Unit) { exec.execute { try { block() } catch (_: Exception) { } } }

    /** Forget earlier refusals so an explicit user action can try again. */
    fun reset() { blocked = false; userBlocked = false }

    /** Installs an APK on the stick, like "adb install -r" (streams it through "cmd package install -S"). Returns the installer output. */
    fun installApk(bytes: ByteArray): String? {
        synchronized(lock) {
            try {
                if (sock == null) { loadKeys(); rxLog = ""; openConnection() }
                stage = "install"
                val id = nextId++
                send(A_OPEN, id, 0, "exec:cmd package install -S ${bytes.size} -r -t\u0000".toByteArray())
                val sb = StringBuilder(); var remote = 0
                while (remote == 0) {
                    val m = recv(); if (m.arg1 != id) continue
                    when (m.cmd) {
                        A_OKAY -> remote = m.arg0
                        A_WRTE -> { sb.append(String(m.data)); send(A_OKAY, id, m.arg0) }
                        A_CLSE -> return sb.toString()
                    }
                }
                var off = 0; val chunk = minOf(peerMax, 64 * 1024)
                while (off < bytes.size) {
                    val n = minOf(chunk, bytes.size - off)
                    send(A_WRTE, id, remote, bytes.copyOfRange(off, off + n)); off += n
                    var acked = false
                    while (!acked) {
                        val m = recv(); if (m.arg1 != id) continue
                        when (m.cmd) {
                            A_OKAY -> acked = true
                            A_WRTE -> { sb.append(String(m.data)); send(A_OKAY, id, m.arg0) }
                            A_CLSE -> return sb.toString().ifBlank { "stream closed early" }
                        }
                    }
                }
                try { sock?.soTimeout = 120_000 } catch (_: Exception) { }      // installing can take a while on a stick
                while (true) {
                    val m = recv(); if (m.arg1 != id) continue
                    when (m.cmd) {
                        A_WRTE -> { sb.append(String(m.data)); send(A_OKAY, id, m.arg0) }
                        A_CLSE -> {
                            try { send(A_CLSE, id, remote) } catch (_: Exception) { }
                            try { sock?.soTimeout = 15_000 } catch (_: Exception) { }
                            return sb.toString()
                        }
                    }
                }
            } catch (e: Exception) { closeQuietly(); fail(e); return null }
        }
        return null
    }

    private fun describe(e: Exception): String = when {
        e is ConnectException -> "ADB debugging is OFF (or unreachable) – Settings > My Fire TV > Developer options > ADB debugging = ON"
        e is SocketTimeoutException -> "No answer – approve “Allow USB debugging?” on the TV (tick Always allow), then retry"
        stage == "sent CNXN" && e is java.io.EOFException && hosts.contains("127.0.0.1") ->
            "The stick refuses ADB connections from apps on itself – expected on some Fire OS versions. Not needed: the PHONE app sends the real keys (phone shows “real keys ✔ (direct)”).\n$trace"
        stage == "waiting for approval" -> "The TV closed the approval prompt (denied or timed out) – retry and choose “Always allow”"
        else -> "Failed at “$stage”: ${e.javaClass.simpleName} ${e.message ?: ""}\nreceived:$rxLog\n$trace"
    }

    private fun closeQuietly() {
        try { sock?.close() } catch (_: Exception) { }
        sock = null; inp = null; out = null
    }

    private fun loadKeys() {
        if (priv != null) return
        val dir = appCtx!!.filesDir
        val pf = File(dir, "adb_priv.der"); val uf = File(dir, "adb_pub.der")
        val kf = KeyFactory.getInstance("RSA")
        if (pf.exists() && uf.exists()) {
            priv = kf.generatePrivate(PKCS8EncodedKeySpec(pf.readBytes()))
            pub = kf.generatePublic(X509EncodedKeySpec(uf.readBytes())) as RSAPublicKey
        } else {
            status = "Creating this app's ADB key…"
            val g = KeyPairGenerator.getInstance("RSA"); g.initialize(2048)
            val kp = g.generateKeyPair()
            pf.writeBytes(kp.private.encoded); uf.writeBytes(kp.public.encoded)
            priv = kp.private; pub = kp.public as RSAPublicKey
        }
    }

    /** Tries each host and handshake variant. Never retries once the approval prompt has been shown. */
    private fun openConnection() {
        val t = StringBuilder(); var last: Exception? = null
        for (host in hosts) for (v in VARIANTS) {
            stage = "connect"
            try { attempt(host, v); trace = t.toString(); return }
            catch (e: Exception) {
                closeQuietly()
                t.append("$host ${v.name}: ${e.javaClass.simpleName} at “$stage”\n"); last = e
                trace = t.toString()
                if (e is SocketTimeoutException || stage == "waiting for approval") throw e
                if (e is ConnectException) break
            }
        }
        throw last ?: IOException("no host to connect to")
    }

    private fun attempt(host: String, v: Variant) {
        status = "Connecting to $host:5555 (${v.name})…"
        val s = Socket(); s.tcpNoDelay = true; s.keepAlive = true
        s.connect(InetSocketAddress(host, 5555), 4000)
        s.soTimeout = 90_000                        // time to approve the on-screen prompt
        sock = s; inp = DataInputStream(BufferedInputStream(s.getInputStream())); out = BufferedOutputStream(s.getOutputStream())
        stage = "sent CNXN"
        send(A_CNXN, v.version, v.maxData, v.banner.toByteArray())
        var signed = false; var keysSent = 0
        while (true) {
            val m = recv()
            when (m.cmd) {
                A_CNXN -> { s.soTimeout = 15_000; stage = "connected"; peerMax = minOf(m.arg1, v.maxData).coerceAtLeast(4096); return }
                A_AUTH -> if (m.arg0 == 1) {
                    if (!signed) { signed = true; stage = "sent signature"; status = "Authenticating…"; send(A_AUTH, 2, 0, sign(m.data)) }
                    else {
                        if (++keysSent > 2) throw IOException("approval refused")
                        stage = "waiting for approval"
                        status = "WAITING: on the TV choose “Always allow”, then OK on “Allow USB debugging?”"
                        send(A_AUTH, 3, 0, publicKeyBlob())
                    }
                }
            }
        }
    }

    private fun shellRaw(cmd: String): String {
        val id = nextId++
        send(A_OPEN, id, 0, "shell:$cmd\u0000".toByteArray())
        val sb = StringBuilder()
        while (true) {
            val m = recv()
            if (m.arg1 != id) continue
            when (m.cmd) {
                A_WRTE -> { sb.append(String(m.data)); send(A_OKAY, id, m.arg0) }
                A_CLSE -> { try { send(A_CLSE, id, m.arg0) } catch (_: Exception) { }; return sb.toString() }
            }
        }
    }

    // ---------------- ADB wire format ----------------
    private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        var sum = 0; for (b in data) sum += (b.toInt() and 0xFF)
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd xor -1)
        val o = out ?: throw IOException("not connected")
        o.write(h.array()); if (data.isNotEmpty()) o.write(data); o.flush()
    }

    private fun recv(): Msg {
        val i = inp ?: throw IOException("not connected")
        val hb = ByteArray(24); i.readFully(hb)
        val b = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        rxLog = (rxLog + " " + when (cmd) { A_CNXN -> "CNXN"; A_AUTH -> "AUTH"; A_OKAY -> "OKAY"; A_WRTE -> "WRTE"; A_CLSE -> "CLSE"; else -> "?" }).takeLast(60)
        if (len < 0 || len > 1024 * 1024) throw IOException("bad packet")
        val d = ByteArray(len); if (len > 0) i.readFully(d)
        return Msg(cmd, a0, a1, d)
    }

    private fun sign(token: ByteArray): ByteArray {
        val s = Signature.getInstance("NONEwithRSA"); s.initSign(priv); s.update(SHA1_PREFIX + token); return s.sign()
    }

    private fun publicKeyBlob(): ByteArray {
        val key = pub ?: throw IOException("no key"); val n = key.modulus
        val bb = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
        val two32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = n.mod(two32).modInverse(two32).negate().mod(two32)
        bb.putInt(64); bb.putInt(n0inv.toLong().toInt())
        words(bb, n); words(bb, BigInteger.ONE.shiftLeft(4096).mod(n)); bb.putInt(key.publicExponent.toInt())
        return (Base64.encodeToString(bb.array(), Base64.NO_WRAP) + " fireairmouse@firetv\u0000").toByteArray()
    }
    private fun words(bb: ByteBuffer, v: BigInteger) {
        val mask = BigInteger.valueOf(0xFFFFFFFFL); var x = v
        repeat(64) { bb.putInt(x.and(mask).toLong().toInt()); x = x.shiftRight(32) }
    }
}
