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
    /** Exposure program mode: 1 = M, 2 = P, 3 = A, 4 = S. */
    var mode: Long = 1,
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
            OpCode.GET_DEVICE_PROP_DESC, OpCode.SET_DEVICE_PROP_VALUE,
            OpCode.NIKON_START_LIVE_VIEW, OpCode.NIKON_END_LIVE_VIEW, OpCode.NIKON_GET_LIVE_VIEW_IMAGE,
        ),
        eventsSupported = setOf(0x4002),
        devicePropertiesSupported = setOf(
            PropCode.DATE_TIME, PropCode.EXPOSURE_PROGRAM_MODE, PropCode.F_NUMBER,
            PropCode.NIKON_EXPOSURE_TIME, PropCode.EXPOSURE_TIME,
            PropCode.EXPOSURE_INDEX, PropCode.EXPOSURE_BIAS_COMPENSATION,
        ),
        captureFormats = listOf(0x3801),
        imageFormats = listOf(0x3801),
        manufacturer = "Nikon Corporation",
        model = "Z 5",
        deviceVersion = "V1.40",
        serialNumber = "0000000000000000000000000000000",
    )

    /** Property values, keyed by property code. */
    val props = mutableMapOf(
        PropCode.F_NUMBER to 560L,
        PropCode.NIKON_EXPOSURE_TIME to ((1L shl 16) or 250L),
        PropCode.EXPOSURE_TIME to 40L,
        PropCode.EXPOSURE_INDEX to 400L,
        PropCode.EXPOSURE_BIAS_COMPENSATION to 0L,
    )

    private val propTypes = mapOf(
        PropCode.EXPOSURE_PROGRAM_MODE to DataType.UINT16,
        PropCode.F_NUMBER to DataType.UINT16,
        PropCode.NIKON_EXPOSURE_TIME to DataType.UINT32,
        PropCode.EXPOSURE_TIME to DataType.UINT32,
        PropCode.EXPOSURE_INDEX to DataType.UINT16,
        PropCode.EXPOSURE_BIAS_COMPENSATION to DataType.INT16,
    )

    private val propChoices = mapOf(
        PropCode.F_NUMBER to listOf(400L, 450L, 500L, 560L, 630L, 710L, 800L),
        PropCode.NIKON_EXPOSURE_TIME to listOf(125L, 250L, 500L).map { (1L shl 16) or it },
        PropCode.EXPOSURE_TIME to listOf(80L, 40L, 20L),
        PropCode.EXPOSURE_INDEX to listOf(100L, 200L, 400L, 800L),
        PropCode.EXPOSURE_BIAS_COMPENSATION to listOf(-1000L, -667L, -333L, 0L, 333L, 667L, 1000L),
    )

    @Volatile
    var liveViewOn = false

    /** Bytes the fake sends as a live view frame: a fake Nikon header, then a tiny "JPEG". */
    val liveViewJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())

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
            OpCode.GET_DEVICE_PROP_DESC -> {
                val code = r.u32()
                val type = propTypes[code]
                if (type == null) {
                    respond(ResponseCode.OPERATION_NOT_SUPPORTED)
                } else {
                    val current = if (code == PropCode.EXPOSURE_PROGRAM_MODE) mode else props.getValue(code)
                    val w = PtpDataWriter().u16(code).u16(type)
                        .u8(if (code == PropCode.EXPOSURE_PROGRAM_MODE) 0 else 1)
                        .bytes(DevicePropDesc.encodeValue(type, current))
                        .bytes(DevicePropDesc.encodeValue(type, current))
                    val choices = propChoices[code]
                    if (choices == null) {
                        w.u8(0)
                    } else {
                        w.u8(2).u16(choices.size)
                        choices.forEach { w.bytes(DevicePropDesc.encodeValue(type, it)) }
                    }
                    sendData(out, tid, w.toByteArray())
                    respond(ResponseCode.OK)
                }
            }
            OpCode.SET_DEVICE_PROP_VALUE -> {
                val code = r.u32()
                check(PtpIpPacket.read(input).type == PacketType.START_DATA)
                val end = PtpIpPacket.read(input)
                check(end.type == PacketType.END_DATA)
                val reader = end.reader(4)
                props[code] = DevicePropDesc.readValue(reader, propTypes.getValue(code))
                respond(ResponseCode.OK)
            }
            OpCode.NIKON_START_LIVE_VIEW -> {
                liveViewOn = true
                respond(ResponseCode.OK)
            }
            OpCode.NIKON_END_LIVE_VIEW -> {
                liveViewOn = false
                respond(ResponseCode.OK)
            }
            OpCode.NIKON_GET_LIVE_VIEW_IMAGE -> {
                if (!liveViewOn) {
                    respond(ResponseCode.NIKON_NOT_LIVE_VIEW)
                } else {
                    sendData(out, tid, ByteArray(384) { 0x11 } + liveViewJpeg)
                    respond(ResponseCode.OK)
                }
            }
            OpCode.OPEN_SESSION, OpCode.CLOSE_SESSION -> respond(ResponseCode.OK)
            else -> respond(ResponseCode.OPERATION_NOT_SUPPORTED)
        }
    }

    private fun sendData(out: OutputStream, tid: Int, data: ByteArray) {
        PtpIpPacket.write(out, PacketType.START_DATA, PtpDataWriter().u32(tid).u64(data.size.toLong()).toByteArray())
        PtpIpPacket.write(out, PacketType.END_DATA, PtpDataWriter().u32(tid).bytes(data).toByteArray())
    }

    fun awaitProbe(): Boolean = probeAnswered.await(5, TimeUnit.SECONDS)

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
        server.close()
    }
}
