package com.robotics.camera

import org.json.JSONObject

/**
 * Encapsulates the manual camera parameters for deterministic capture.
 *
 * @property focusDistance Lens focus distance in diopters (1 / distance_in_meters).
 *                         0.0f corresponds to infinity focus; 10.0f corresponds to 10cm.
 * @property iso Sensor sensitivity (ISO rating). Common values: 50, 100, 200, 400, 800.
 * @property exposureTimeNs Exposure duration in nanoseconds.
 *                          Example: 10_000_000L = 10ms (1/100 sec).
 */
data class CameraSettings(
    val focusDistance: Float = 0.0f,
    val iso: Int = 100,
    val exposureTimeNs: Long = 10_000_000L // 10 ms default
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("focus_distance", focusDistance.toDouble())
            put("iso", iso)
            put("exposure_time_ns", exposureTimeNs)
            put("exposure_time_ms", exposureTimeNs / 1_000_000.0)
        }
    }

    companion object {
        fun fromJson(json: JSONObject, current: CameraSettings): CameraSettings {
            val focus = if (json.has("focus_distance")) {
                json.getDouble("focus_distance").toFloat()
            } else {
                current.focusDistance
            }

            val iso = if (json.has("iso")) {
                json.getInt("iso")
            } else {
                current.iso
            }

            val expNs = if (json.has("exposure_time_ns")) {
                json.getLong("exposure_time_ns")
            } else if (json.has("exposure_time")) {
                // Accept exposure_time either in ns or ms
                val raw = json.getDouble("exposure_time")
                if (raw < 1000.0) {
                    // Interpreted as milliseconds
                    (raw * 1_000_000).toLong()
                } else {
                    raw.toLong()
                }
            } else {
                current.exposureTimeNs
            }

            return CameraSettings(
                focusDistance = focus,
                iso = iso,
                exposureTimeNs = expNs
            )
        }
    }
}
