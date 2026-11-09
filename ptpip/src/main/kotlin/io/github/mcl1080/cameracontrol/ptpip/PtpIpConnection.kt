package io.github.mcl1080.cameracontrol.ptpip

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import javax.net.SocketFactory

/** A PTP event sent by the camera on the event channel. */
data class PtpEvent(val code: Int, val transactionId: Int, val params: List<Int>)

/** The camera's answer to one operation, with any data it sent back. */
data class PtpResponse(val code: Int, val params: List<Int>, val data: ByteArray?) {
    val isOk get() = code == ResponseCode.OK
}

/**
 * A PTP/IP connection: a command channel for request/response transactions and an
 * event channel the camera uses to push events and keep-alive probes.
 *
 * [socketFactory] lets Android bind the sockets to the camera's Wi-Fi network, which
 * has no internet and would otherwise be bypassed in favour of mobile data.
 */
class PtpIpConnection(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val guid: UUID = UUID.randomUUID(),
    private val friendlyName: String = "Latlo",
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 15_000,
) : Closeable {

    var cameraName: String = ""
        private set

    /** Called on a background thread for each event the camera sends. */
    @Volatile
    var eventListener: ((PtpEvent) -> Unit)? = null

    private var commandSocket: Socket? = null
    private var commandInput: InputStream? = null
    private var eventSocket: Socket? = null
    private var eventThread: Thread? = null
    private var nextTransactionId = 1

    @Volatile
    private var closed = false

    fun connect() {
        try {
            val cmd = openSocket(readTimeoutMs)
            commandSocket = cmd
            // Buffered so a large live view frame arrives in few big reads.
            val cmdIn = BufferedInputStream(cmd.getInputStream(), 256 * 1024)
            commandInput = cmdIn
            PtpIpPacket.write(
                cmd.getOutputStream(),
                PacketType.INIT_COMMAND_REQUEST,
                PtpDataWriter()
                    .bytes(guidBytes(guid))
                    .nullTerminatedUtf16(friendlyName)
                    .u32(PROTOCOL_VERSION)
                    .toByteArray(),
            )
            val ack = PtpIpPacket.read(cmdIn)
            when (ack.type) {
                PacketType.INIT_COMMAND_ACK -> Unit
                PacketType.INIT_FAIL -> throw PtpIpException(
                    "Camera refused the connection (reason 0x%X). ".format(ack.reader().u32()) +
                        "Check the camera screen: it may be asking you to allow this phone.",
                )
                else -> throw PtpIpException("Unexpected reply ${ack.type} to connection request")
            }
            val r = ack.reader()
            val connectionNumber = r.u32()
            r.bytes(16) // camera GUID
            cameraName = r.nullTerminatedUtf16()

            // The event channel has no read timeout: the camera may stay quiet for a long time.
            val evt = openSocket(0)
            eventSocket = evt
            PtpIpPacket.write(
                evt.getOutputStream(),
                PacketType.INIT_EVENT_REQUEST,
                PtpDataWriter().u32(connectionNumber).toByteArray(),
            )
            val evtAck = PtpIpPacket.read(evt.getInputStream())
            if (evtAck.type != PacketType.INIT_EVENT_ACK) {
                throw PtpIpException("Camera refused the event channel (packet ${evtAck.type})")
            }
            startEventLoop(evt)
        } catch (e: Exception) {
            close()
            throw if (e is PtpIpException) e else PtpIpException("Could not connect to $host: ${e.message}", e)
        }
    }

    /**
     * Runs one PTP transaction. [dataOut] is sent to the camera in the data phase
     * (for example a property value); data the camera sends back is in the response.
     */
    @Synchronized
    fun transaction(opcode: Int, params: IntArray = IntArray(0), dataOut: ByteArray? = null): PtpResponse {
        require(params.size <= 5) { "PTP allows at most 5 parameters" }
        val socket = commandSocket ?: throw PtpIpException("Not connected")
        val out = socket.getOutputStream()
        val input = commandInput ?: throw PtpIpException("Not connected")

        val tid = if (opcode == OpCode.OPEN_SESSION) 0 else nextTransactionId++

        try {
            val req = PtpDataWriter()
                .u32(if (dataOut != null) DATA_PHASE_OUT else DATA_PHASE_NONE_OR_IN)
                .u16(opcode)
                .u32(tid)
            params.forEach { req.u32(it) }
            PtpIpPacket.write(out, PacketType.OPERATION_REQUEST, req.toByteArray())

            if (dataOut != null) {
                PtpIpPacket.write(
                    out,
                    PacketType.START_DATA,
                    PtpDataWriter().u32(tid).u64(dataOut.size.toLong()).toByteArray(),
                )
                PtpIpPacket.write(
                    out,
                    PacketType.END_DATA,
                    PtpDataWriter().u32(tid).bytes(dataOut).toByteArray(),
                )
            }

            var data: ByteArrayOutputStream? = null
            while (true) {
                val packet = PtpIpPacket.read(input)
                when (packet.type) {
                    PacketType.START_DATA -> data = ByteArrayOutputStream()
                    PacketType.DATA, PacketType.END_DATA -> {
                        (data ?: ByteArrayOutputStream().also { data = it })
                            .write(packet.payload, 4, packet.payload.size - 4)
                    }
                    PacketType.OPERATION_RESPONSE -> {
                        val r = packet.reader()
                        val code = r.u16()
                        r.u32() // transaction id
                        val respParams = List(r.remaining / 4) { r.u32() }
                        return PtpResponse(code, respParams, data?.toByteArray())
                    }
                    else -> Unit // ignore anything unexpected
                }
            }
        } catch (e: IOException) {
            close()
            throw PtpIpException("Lost connection to camera: ${e.message}", e)
        }
    }

    val isConnected: Boolean get() = !closed && commandSocket?.isConnected == true

    override fun close() {
        closed = true
        runCatching { eventSocket?.close() }
        runCatching { commandSocket?.close() }
        eventSocket = null
        commandSocket = null
        commandInput = null
    }

    private fun openSocket(readTimeout: Int): Socket {
        val socket = socketFactory.createSocket()
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.receiveBufferSize = 512 * 1024
        socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        socket.soTimeout = readTimeout
        return socket
    }

    private fun startEventLoop(socket: Socket) {
        eventThread = Thread({
            try {
                val input = socket.getInputStream()
                val out = socket.getOutputStream()
                while (!closed) {
                    val packet = PtpIpPacket.read(input)
                    when (packet.type) {
                        PacketType.PROBE_REQUEST -> synchronized(out) {
                            PtpIpPacket.write(out, PacketType.PROBE_RESPONSE)
                        }
                        PacketType.EVENT -> {
                            val r = packet.reader()
                            val code = r.u16()
                            val tid = r.u32()
                            val params = List(r.remaining / 4) { r.u32() }
                            eventListener?.invoke(PtpEvent(code, tid, params))
                        }
                    }
                }
            } catch (_: IOException) {
                // Socket closed; the command channel reports the disconnect.
            }
        }, "ptpip-events").apply {
            isDaemon = true
            start()
        }
    }

    companion object {
        const val DEFAULT_PORT = 15740
        private const val PROTOCOL_VERSION = 0x00010000
        private const val DATA_PHASE_NONE_OR_IN = 1
        private const val DATA_PHASE_OUT = 2

        fun guidBytes(uuid: UUID): ByteArray = PtpDataWriter()
            .bytes(longToBytesBigEndian(uuid.mostSignificantBits))
            .bytes(longToBytesBigEndian(uuid.leastSignificantBits))
            .toByteArray()

        private fun longToBytesBigEndian(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }
    }
}
