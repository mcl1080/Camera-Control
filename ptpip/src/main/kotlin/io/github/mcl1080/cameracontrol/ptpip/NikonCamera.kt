package io.github.mcl1080.cameracontrol.ptpip

import java.io.Closeable

/**
 * High-level control of a Nikon camera over PTP/IP.
 * Step 1 covers connecting, reading the camera's identity and taking a photo.
 */
class NikonCamera(private val connection: PtpIpConnection) : Closeable {

    lateinit var deviceInfo: DeviceInfo
        private set

    var log: (String) -> Unit = {}

    val isConnected: Boolean get() = connection.isConnected

    fun open() {
        connection.connect()
        log("Connected to \"${connection.cameraName}\"")

        val info = connection.transaction(OpCode.GET_DEVICE_INFO)
        if (!info.isOk || info.data == null) throw PtpResponseException(info.code, OpCode.GET_DEVICE_INFO)
        deviceInfo = DeviceInfo.parse(info.data)
        log("Camera: ${deviceInfo.manufacturer} ${deviceInfo.model} (firmware ${deviceInfo.deviceVersion})")

        val session = connection.transaction(OpCode.OPEN_SESSION, intArrayOf(SESSION_ID))
        if (!session.isOk && session.code != ResponseCode.SESSION_ALREADY_OPEN) {
            throw PtpResponseException(session.code, OpCode.OPEN_SESSION)
        }
        log("Session open")
    }

    /** Takes a photo and saves it to the camera's memory card, the same as pressing the shutter. */
    fun capture() {
        val opcode: Int
        val params: IntArray
        when {
            deviceInfo.supports(OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA) -> {
                // 0xFFFFFFFF = autofocus then release; second parameter 0 = record to card.
                opcode = OpCode.NIKON_INITIATE_CAPTURE_REC_IN_MEDIA
                params = intArrayOf(-1, 0)
            }
            else -> {
                opcode = OpCode.INITIATE_CAPTURE
                params = intArrayOf(0, 0)
            }
        }
        log("Capture using operation 0x%04X".format(opcode))
        val resp = connection.transaction(opcode, params)
        if (!resp.isOk) throw PtpResponseException(resp.code, opcode)
        waitUntilReady()
        log("Photo taken")
    }

    /** Nikon cameras report busy while they focus and write the file; poll until done. */
    private fun waitUntilReady(timeoutMs: Long = 30_000) {
        if (!deviceInfo.supports(OpCode.NIKON_DEVICE_READY)) return
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val resp = connection.transaction(OpCode.NIKON_DEVICE_READY)
            when (resp.code) {
                ResponseCode.OK -> return
                ResponseCode.DEVICE_BUSY -> Thread.sleep(100)
                else -> throw PtpResponseException(resp.code, OpCode.NIKON_DEVICE_READY)
            }
        }
        throw PtpIpException("Camera stayed busy for more than ${timeoutMs / 1000} s")
    }

    override fun close() {
        if (connection.isConnected) {
            runCatching { connection.transaction(OpCode.CLOSE_SESSION) }
        }
        connection.close()
    }

    companion object {
        private const val SESSION_ID = 1
    }
}
