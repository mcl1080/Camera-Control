package io.github.mcl1080.cameracontrol.ptpip

/** PTP/IP packet types (CIPA DC-005). */
object PacketType {
    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val CANCEL = 11
    const val END_DATA = 12
    const val PROBE_REQUEST = 13
    const val PROBE_RESPONSE = 14
}

/** PTP operation codes: standard (0x1xxx) and Nikon vendor extensions (0x9xxx). */
object OpCode {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val INITIATE_CAPTURE = 0x100E
    const val GET_DEVICE_PROP_DESC = 0x1014
    const val GET_DEVICE_PROP_VALUE = 0x1015
    const val SET_DEVICE_PROP_VALUE = 0x1016

    const val NIKON_INITIATE_CAPTURE_REC_IN_SDRAM = 0x90C0
    const val NIKON_DEVICE_READY = 0x90C8
    const val NIKON_START_LIVE_VIEW = 0x9201
    const val NIKON_END_LIVE_VIEW = 0x9202
    const val NIKON_GET_LIVE_VIEW_IMAGE = 0x9203
    const val NIKON_INITIATE_CAPTURE_REC_IN_MEDIA = 0x9207
}

/** PTP response codes. */
object ResponseCode {
    const val OK = 0x2001
    const val GENERAL_ERROR = 0x2002
    const val SESSION_NOT_OPEN = 0x2003
    const val OPERATION_NOT_SUPPORTED = 0x2005
    const val STORE_FULL = 0x200C
    const val ACCESS_DENIED = 0x200F
    const val STORE_NOT_AVAILABLE = 0x2013
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201E

    const val NIKON_OUT_OF_FOCUS = 0xA002
    const val NIKON_NOT_LIVE_VIEW = 0xA00B

    fun describe(code: Int): String = when (code) {
        OK -> "OK"
        GENERAL_ERROR -> "General error"
        SESSION_NOT_OPEN -> "Session not open"
        OPERATION_NOT_SUPPORTED -> "Operation not supported"
        STORE_FULL -> "Memory card full"
        ACCESS_DENIED -> "Access denied"
        STORE_NOT_AVAILABLE -> "No memory card"
        DEVICE_BUSY -> "Camera busy"
        SESSION_ALREADY_OPEN -> "Session already open"
        NIKON_OUT_OF_FOCUS -> "Could not focus"
        NIKON_NOT_LIVE_VIEW -> "Live view is not running"
        else -> "Error 0x%04X".format(code)
    }
}

/** PTP device property codes used for exposure control. */
object PropCode {
    const val F_NUMBER = 0x5007
    const val EXPOSURE_TIME = 0x500D
    const val EXPOSURE_PROGRAM_MODE = 0x500E
    const val EXPOSURE_INDEX = 0x500F
    const val EXPOSURE_BIAS_COMPENSATION = 0x5010
    const val DATE_TIME = 0x5011

    /** Nikon shutter speed as numerator (high 16 bits) / denominator (low 16 bits). */
    const val NIKON_EXPOSURE_TIME = 0xD100
}

/** PTP data type codes, used in property descriptions. */
object DataType {
    const val INT8 = 0x0001
    const val UINT8 = 0x0002
    const val INT16 = 0x0003
    const val UINT16 = 0x0004
    const val INT32 = 0x0005
    const val UINT32 = 0x0006
    const val INT64 = 0x0007
    const val UINT64 = 0x0008
    const val STRING = 0xFFFF
}

/** PTP event codes. */
object EventCode {
    const val DEVICE_PROP_CHANGED = 0x4006
}
