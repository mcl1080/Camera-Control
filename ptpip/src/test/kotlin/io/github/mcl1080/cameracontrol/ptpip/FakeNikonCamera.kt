package io.github.mcl1080.cameracontrol.ptpip

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A tiny in-process stand-in for a Nikon camera's PTP/IP server, for tests. */
class FakeNikonCamera(
    private val refuseConnection: Boolean = false,
    private val busyPollsAfterCapture: Int = 2,
    private val sendProbe: Boolean = false,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    val receivedOpcodes: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    var receivedFriendlyName: String? = null
    val probeAnswered = CountDownLatch(1)

    private var busyRemaining = 0
    private val sockets = mutableListOf<Socket>()

    val deviceInfo = DeviceInfo(
        standardVersion = 100,
        vendorExtensionId = 0x0000000A,
        vendorExtensionVersion = 100,
        vendorExtensionDesc = "microsoft.com: 1.0",
        functionalMode = 0,
        operationsSupported = setOf(
            OpCode.GET_DEVICE_INFO, OpCode.OPEN_SESSION, OpCode.CLOSE_SESSION,
            OpCode.INITIATE_CAPTURE, OpCode.NIKON_DEVICE_READY,
            OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA,
        ),
        eventsSupported = setOf(0x4002),
        devicePropertiesSupported = setOf(0x5011),
        captureFormats = listOf(0x3801),
        imageFormats = listOf(0x3801),
        manufacturer = "Nikon Corporation",
        model = "Z 5",
        deviceVersion = "V1.40",
        serialNumber = "0000000000000000000000000000000",
    )

    init {
        Thread({ serve() }, "fake-camera").apply { isDaemon = true; start() }
    }

    private fun serve() {
        runCatching {
            val cmd = server.accept().also { sockets += it }
            val cin = cmd.getInputStream()
            val cout = cmd.getOutputStream()

            val init = PtpIpPacket.read(cin)
            check(init.type == PacketType.INIT_COMMAND_REQUEST)
            val r = init.reader()
            r.bytes(16)
            receivedFriendlyName = r.nullTerminatedUtf16()
            if (refuseConnection) {
                PtpIpPacket.write(cout, PacketType.INIT_FAIL, PtpDataWriter().u32(1).toByteArray())
                return
            }
            PtpIpPacket.write(
                cout,
                PacketType.INIT_COMMAND_ACK,
                PtpDataWriter().u32(7).bytes(ByteArray(16)).nullTerminatedUtf16("Z_5").u32(0x00010000).toByteArray(),
            )

            val evt = server.accept().also { sockets += it }
            val ein = evt.getInputStream()
            val eout = evt.getOutputStream()
            val evtInit = PtpIpPacket.read(ein)
            check(evtInit.type == PacketType.INIT_EVENT_REQUEST)
            check(evtInit.reader().u32() == 7)
            PtpIpPacket.write(eout, PacketType.INIT_EVENT_ACK)

            if (sendProbe) {
                Thread({
                    runCatching {
                        PtpIpPacket.write(eout, PacketType.PROBE_REQUEST)
                        if (PtpIpPacket.read(ein).type == PacketType.PROBE_RESPONSE) probeAnswered.countDown()
                    }
                }, "fake-probe").apply { isDaemon = true; start() }
            }

            while (true) handleOperation(cin, cout)
        }
    }

    private fun handleOperation(input: InputStream, out: OutputStream) {
        val req = PtpIpPacket.read(input)
        check(req.type == PacketType.OPERATION_REQUEST)
        val r = req.reader()
        r.u32() // data phase
        val opcode = r.u16()
        val tid = r.u32()
        receivedOpcodes += opcode

        fun respond(code: Int) = PtpIpPacket.write(
            out, PacketType.OPERATION_RESPONSE, PtpDataWriter().u16(code).u32(tid).toByteArray(),
        )

        when (opcode) {
            OpCode.GET_DEVICE_INFO -> {
                val data = deviceInfo.toBytes()
                PtpIpPacket.write(out, PacketType.START_DATA, PtpDataWriter().u32(tid).u64(data.size.toLong()).toByteArray())
                // Split into two chunks to exercise DATA + END_DATA reassembly.
                val half = data.size / 2
                PtpIpPacket.write(out, PacketType.DATA, PtpDataWriter().u32(tid).bytes(data.copyOfRange(0, half)).toByteArray())
                PtpIpPacket.write(out, PacketType.END_DATA, PtpDataWriter().u32(tid).bytes(data.copyOfRange(half, data.size)).toByteArray())
                respond(ResponseCode.OK)
            }
            OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA -> {
                busyRemaining = busyPollsAfterCapture
                respond(ResponseCode.OK)
            }
            OpCode.NIKON_DEVICE_READY -> {
                if (busyRemaining > 0) {
                    busyRemaining--
                    respond(ResponseCode.DEVICE_BUSY)
                } else {
                    respond(ResponseCode.OK)
                }
            }
            OpCode.OPEN_SESSION, OpCode.CLOSE_SESSION -> respond(ResponseCode.OK)
            else -> respond(ResponseCode.OPERATION_NOT_SUPPORTED)
        }
    }

    fun awaitProbe(): Boolean = probeAnswered.await(5, TimeUnit.SECONDS)

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
        server.close()
    }
}
