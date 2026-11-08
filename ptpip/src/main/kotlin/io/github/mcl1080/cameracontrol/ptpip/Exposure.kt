package io.github.mcl1080.cameracontrol.ptpip

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToLong

/** The exposure settings the remote shows. */
enum class ExposureSetting { SHUTTER_SPEED, APERTURE, ISO, EXPOSURE_COMPENSATION }

/** The camera's shooting mode, from the mode dial. */
enum class ShootingMode(val label: String) {
    MANUAL("M"),
    APERTURE_PRIORITY("A"),
    SHUTTER_PRIORITY("S"),
    PROGRAM("P"),
    AUTO("Auto"),
    OTHER("Other");

    /** Which settings the photographer controls in this mode; the camera picks the rest. */
    val userControls: Set<ExposureSetting>
        get() = when (this) {
            MANUAL -> setOf(ExposureSetting.SHUTTER_SPEED, ExposureSetting.APERTURE, ExposureSetting.ISO)
            APERTURE_PRIORITY -> setOf(ExposureSetting.APERTURE, ExposureSetting.ISO, ExposureSetting.EXPOSURE_COMPENSATION)
            SHUTTER_PRIORITY -> setOf(ExposureSetting.SHUTTER_SPEED, ExposureSetting.ISO, ExposureSetting.EXPOSURE_COMPENSATION)
            PROGRAM -> setOf(ExposureSetting.ISO, ExposureSetting.EXPOSURE_COMPENSATION)
            AUTO -> emptySet()
            // U1-U3 and anything unrecognised: trust what the camera says is writable.
            OTHER -> ExposureSetting.entries.toSet()
        }

    companion object {
        fun fromPtp(value: Long): ShootingMode = when (value) {
            1L -> MANUAL
            2L -> PROGRAM
            3L -> APERTURE_PRIORITY
            4L -> SHUTTER_PRIORITY
            0x8010L -> AUTO
            else -> OTHER
        }
    }
}

/** One setting as the remote shows it: current value, choices, and whether it can be changed now. */
data class SettingState(
    val setting: ExposureSetting,
    val propCode: Int,
    val dataType: Int,
    val value: Long,
    val choices: List<Long>,
    val editable: Boolean,
) {
    val label: String get() = ExposureFormat.format(propCode, value)
    fun labelFor(v: Long) = ExposureFormat.format(propCode, v)

    /** Position of the current value in [choices], or -1 if the camera reports one not listed. */
    val index: Int get() = choices.indexOf(value)

    /** The value [delta] steps along the camera's list from the current one, or null at either end. */
    fun step(delta: Int): Long? {
        val i = index
        if (i < 0) return choices.firstOrNull()
        return choices.getOrNull(i + delta)
    }
}

data class ExposureState(
    val mode: ShootingMode,
    val modeValue: Long,
    val modeDataType: Int = DataType.UINT16,
    val settings: Map<ExposureSetting, SettingState>,
) {
    /**
     * The cheap checks to run between full reads: each property code with its type and the
     * value last seen. If any differs, the camera changed and a full [NikonCamera.readExposure] is due.
     */
    fun watchList(): List<Triple<Int, Int, Long>> = buildList {
        if (modeValue >= 0) add(Triple(PropCode.EXPOSURE_PROGRAM_MODE, modeDataType, modeValue))
        settings.values.forEach { add(Triple(it.propCode, it.dataType, it.value)) }
    }

    /** The same state with one setting's value replaced, for showing a change straight away. */
    fun withValue(setting: ExposureSetting, value: Long): ExposureState {
        val s = settings[setting] ?: return this
        return copy(settings = settings + (setting to s.copy(value = value)))
    }
}

/** Turns raw property values into the text a photographer expects. */
object ExposureFormat {
    private val shutterDenominators = listOf(
        8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320,
        250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3,
    )

    fun format(propCode: Int, value: Long): String = when (propCode) {
        PropCode.F_NUMBER -> aperture(value)
        PropCode.EXPOSURE_TIME -> shutterFromTenThousandths(value)
        PropCode.NIKON_EXPOSURE_TIME -> shutterFromFraction(value)
        PropCode.EXPOSURE_INDEX -> "ISO $value"
        PropCode.EXPOSURE_BIAS_COMPENSATION -> compensation(value)
        else -> value.toString()
    }

    /** F-number in hundredths: 560 -> "f/5.6", 1100 -> "f/11". */
    fun aperture(hundredths: Long): String {
        val f = hundredths / 100.0
        return if (f >= 10 || f == f.toLong().toDouble()) "f/${f.roundToLong()}" else "f/%.1f".format(f)
    }

    /** Standard PTP exposure time in 1/10000 s. */
    fun shutterFromTenThousandths(v: Long): String = when (v) {
        0xFFFFFFFFL -> "Bulb"
        0xFFFFFFFDL -> "Time"
        0L -> "–"
        else -> seconds(v / 10_000.0)
    }

    /** Nikon exposure time as numerator/denominator packed into 32 bits. */
    fun shutterFromFraction(v: Long): String {
        if (v == 0xFFFFFFFFL) return "Bulb"
        if (v == 0xFFFFFFFDL) return "Time"
        val num = (v ushr 16) and 0xFFFF
        val den = v and 0xFFFF
        if (num == 0L || den == 0L) return "–"
        if (num == 1L && den > 1) return "1/$den"
        return seconds(num.toDouble() / den)
    }

    private fun seconds(s: Double): String {
        if (s < 0.35) { // 1/3 s is the slowest speed shown as a fraction
            // Snap to the nearest marked speed, so 1/8000 (stored as 1/10000 s) shows correctly.
            val den = shutterDenominators.minBy { abs(ln(it * s)) }
            return "1/$den"
        }
        return if (s == s.toLong().toDouble()) "${s.toLong()}\"" else "%.1f\"".format(s)
    }

    /** Exposure compensation in 1/1000 EV: 667 -> "+0.7", 0 -> "0.0". */
    fun compensation(thousandths: Long): String {
        val ev = thousandths / 1000.0
        return when {
            thousandths > 0 -> "+%.1f".format(ev)
            thousandths < 0 -> "%.1f".format(ev)
            else -> "0.0"
        }
    }
}
