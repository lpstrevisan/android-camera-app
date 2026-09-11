package com.robotics.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * CameraController manages the Camera2 hardware pipeline with strict manual control.
 *
 * All automated OEM 3A algorithms (Auto-Focus, Auto-Exposure, Auto-White Balance),
 * optical and video stabilization, noise reduction, and edge filtering are explicitly disabled
 * to provide a 100% deterministic visual sensor suitable for robotics and dataset collection.
 */
class CameraController(
    private val context: Context,
    private val onSettingsChangedListener: ((CameraSettings) -> Unit)? = null
) {
    companion object {
        private const val TAG = "CameraController"
        private const val TARGET_WIDTH = 1920
        private const val TARGET_HEIGHT = 1080
        private const val TARGET_ASPECT_RATIO = 16.0 / 9.0
    }

    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Background Threading
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Hardware Handles
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    // Camera Metadata and Characteristics
    private var cameraId: String = ""
    private var characteristics: CameraCharacteristics? = null
    private var exposureRange: Range<Long>? = null
    private var isoRange: Range<Int>? = null
    private var minFocusDistance: Float = 0.0f // In diopters (1/meters). 0.0 = infinity.

    // Active Manual Settings
    @Volatile
    var currentSettings = CameraSettings(
        focusDistance = 0.0f,          // Default: Infinity focus (0 diopters)
        iso = 100,                     // Default: Clean baseline sensitivity
        exposureTimeNs = 10_000_000L   // Default: 10 ms (1/100 sec)
    )
        private set

    // Capture Synchronization
    private val captureLock = ReentrantLock()
    private val pendingCaptureLatch = AtomicReference<CountDownLatch?>(null)
    private val pendingCaptureResult = AtomicReference<ByteArray?>(null)
    val totalCapturesServed = AtomicLong(0)

    // Request Builders
    private var previewRequestBuilder: CaptureRequest.Builder? = null
    private var stillRequestBuilder: CaptureRequest.Builder? = null

    // ---------------------------------------------------------------------------------------------
    // Lifecycle Management
    // ---------------------------------------------------------------------------------------------

    fun startBackgroundThread() {
        if (backgroundThread == null) {
            backgroundThread = HandlerThread("Camera2BackgroundThread").apply {
                start()
                backgroundHandler = Handler(looper)
            }
        }
    }

    fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e(TAG, "Error stopping background thread", e)
        }
    }

    /**
     * Initializes and opens the Main Rear Camera (LENS_FACING_BACK).
     * Binds the preview to the provided SurfaceTexture.
     */
    @SuppressLint("MissingPermission")
    fun openCamera(surfaceTexture: SurfaceTexture, previewWidth: Int, previewHeight: Int) {
        startBackgroundThread()

        try {
            // Find Rear Facing Camera
            val backCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: cameraManager.cameraIdList.first()

            cameraId = backCameraId
            val chars = cameraManager.getCameraCharacteristics(cameraId)
            characteristics = chars

            // Query Sensor Capabilities
            isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            minFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.0f

            Log.i(TAG, "Camera $cameraId selected. ISO Range: $isoRange, Exposure Range: $exposureRange, Max Focus Diopter: $minFocusDistance")

            // Clamp initial settings to hardware limits
            currentSettings = clampSettings(currentSettings)

            // Setup ImageReader for JPEG capture
            setupImageReader(chars)

            // Configure SurfaceTexture buffer size
            surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight)
            previewSurface = Surface(surfaceTexture)

            // Open the hardware camera device
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    createCaptureSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error: $error")
                    camera.close()
                    cameraDevice = null
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera", e)
        }
    }

    /**
     * Finds the closest 16:9 1080p resolution and initializes the ImageReader.
     */
    private fun setupImageReader(chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpegSizes = map?.getOutputSizes(ImageFormat.JPEG) ?: emptyArray()

        // Find standard 1080p (1920x1080) or best matching 16:9 resolution
        val selectedSize = jpegSizes.firstOrNull { it.width == TARGET_WIDTH && it.height == TARGET_HEIGHT }
            ?: jpegSizes.filter { size ->
                val ratio = size.width.toDouble() / size.height.toDouble()
                abs(ratio - TARGET_ASPECT_RATIO) < 0.05
            }.maxByOrNull { it.width * it.height }
            ?: Size(TARGET_WIDTH, TARGET_HEIGHT)

        Log.i(TAG, "Configured ImageReader for JPEG resolution: ${selectedSize.width}x${selectedSize.height}")

        // MaxImages = 2 to avoid buffering lag and keep memory bounded
        imageReader = ImageReader.newInstance(
            selectedSize.width,
            selectedSize.height,
            ImageFormat.JPEG,
            2
        ).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage()
                if (image != null) {
                    try {
                        val latch = pendingCaptureLatch.get()
                        if (latch != null) {
                            val buffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.remaining())
                            buffer.get(bytes)
                            pendingCaptureResult.set(bytes)
                            latch.countDown()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading image buffer", e)
                    } finally {
                        image.close()
                    }
                }
            }, backgroundHandler)
        }
    }

    /**
     * Builds the CameraCaptureSession with both the preview surface and ImageReader surface.
     */
    private fun createCaptureSession() {
        val device = cameraDevice ?: return
        val pSurface = previewSurface ?: return
        val reader = imageReader ?: return

        try {
            val surfaces = listOf(pSurface, reader.surface)

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        // Create repeating preview request with full manual control
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(pSurface)
                            applyStrictManualSettings(this, currentSettings)
                        }
                        previewRequestBuilder = builder

                        // Start continuous sensor stream
                        session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                        Log.i(TAG, "Repeating preview request started with manual 3A controls disabled.")

                        // Notify UI of initial settings
                        onSettingsChangedListener?.invoke(currentSettings)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start camera preview", e)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "CaptureSession configuration failed")
                }
            }, backgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, "Error creating capture session", e)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // CRITICAL: STRICT MANUAL CAMERA2 CONFIGURATION
    // ---------------------------------------------------------------------------------------------

    /**
     * Configures the CaptureRequest.Builder with absolute manual hardware parameters,
     * disabling all automated 3A loops, post-processing, and sensor stabilization.
     */
    private fun applyStrictManualSettings(builder: CaptureRequest.Builder, settings: CameraSettings) {

        // 1. MASTER CONTROL MODE:
        // Set CONTROL_MODE to OFF. This tells the camera HAL that application-level manual controls
        // take complete priority and automated scene modes or 3A algorithms must not intervene.
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_OFF)

        // 2. AUTO-FOCUS (AF) CONTROL:
        // Disable the auto-focus algorithm completely. The lens actuator remains strictly stationary
        // at the configured focal distance (in diopters: 1 / meters).
        builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, settings.focusDistance)

        // 3. AUTO-EXPOSURE (AE) CONTROL:
        // Disable the auto-exposure loop. The integration time (shutter speed) and analog/digital
        // sensor amplification (sensitivity/ISO) are fixed to exact values.
        builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTimeNs)
        builder.set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)

        // 4. AUTO-WHITE BALANCE (AWB) CONTROL:
        // Disable dynamic white balance calculations to prevent color shifting between frames.
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)

        // 4b. MANUAL COLOR CORRECTION GAINS & TRANSFORM MATRIX:
        // When CONTROL_AWB_MODE is OFF, the ISP requires explicit RGB gains and color transform.
        // Without this, many OEM camera HALs render completely green, purple, or dark frames.
        // We set COLOR_CORRECTION_MODE to TRANSFORM_MATRIX with calibrated daylight gains (D65 baseline).
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        // Standard D65 neutral channel gains: Red = 1.8f, Even Green = 1.0f, Odd Green = 1.0f, Blue = 1.6f
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(1.8f, 1.0f, 1.0f, 1.6f))
        // Identity color space matrix (no chromatic shearing)
        builder.set(
            CaptureRequest.COLOR_CORRECTION_TRANSFORM,
            ColorSpaceTransform(
                arrayOf(
                    Rational(1, 1), Rational(0, 1), Rational(0, 1),
                    Rational(0, 1), Rational(1, 1), Rational(0, 1),
                    Rational(0, 1), Rational(0, 1), Rational(1, 1)
                )
            )
        )

        // 5. STABILIZATION:
        // Disable Optical Image Stabilization (OIS) to prevent physical voice-coil lens movements
        // caused by robotic arm vibrations or accelerations.
        builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF)
        // Disable Electronic/Video Stabilization (EIS) to avoid geometric warping/cropping across frames.
        builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)

        // 6. ZERO IMAGE POST-PROCESSING:
        // Turn off temporal and spatial noise reduction filters. Raw sensor noise pattern is preserved.
        builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_OFF)
        // Turn off edge enhancement / sharpening filters to ensure pixel-level linear fidelity.
        builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF)
        // Disable chromatic aberration correction algorithms.
        builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_OFF)

        // 7. ADDITIONAL DETERMINISM CONTROLS:
        // Disable hot-pixel removal filters if supported
        builder.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF)
        // Disable lens shading compensation (vignette correction) to preserve raw illumination gradients
        builder.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_OFF)
    }

    // ---------------------------------------------------------------------------------------------
    // FRAME CAPTURE & BLOCKING HTTP PIPELINE
    // ---------------------------------------------------------------------------------------------

    /**
     * Executes a single still capture directly from the active CameraCaptureSession to ImageReader.
     * Blocks synchronously until the frame is produced by ImageReader or until timeoutMs expires.
     *
     * @param timeoutMs Maximum milliseconds to wait for the frame.
     * @return Raw JPEG ByteArray.
     * @throws TimeoutException or IllegalStateException on capture failure.
     */
    fun captureStillFrame(timeoutMs: Long = 3000): ByteArray {
        val session = captureSession ?: throw IllegalStateException("Camera capture session is not active")
        val device = cameraDevice ?: throw IllegalStateException("Camera device is closed")
        val reader = imageReader ?: throw IllegalStateException("ImageReader is not initialized")

        captureLock.withLock {
            val latch = CountDownLatch(1)
            pendingCaptureResult.set(null)
            pendingCaptureLatch.set(latch)

            try {
                // Build single capture request using STILL_CAPTURE template with manual overrides
                val stillBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(reader.surface)
                    applyStrictManualSettings(this, currentSettings)
                    // High-quality JPEG output
                    set(CaptureRequest.JPEG_QUALITY, 100.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, 0)
                }
                stillRequestBuilder = stillBuilder

                // Submit capture request to hardware session
                session.capture(stillBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure
                    ) {
                        Log.e(TAG, "Still capture failed: ${failure.reason}")
                        latch.countDown()
                    }
                }, backgroundHandler)

                // Block thread until ImageReader's OnImageAvailableListener produces the frame
                val received = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
                if (!received) {
                    throw java.util.concurrent.TimeoutException("Timed out waiting for Camera2 frame from ImageReader ($timeoutMs ms)")
                }

                val result = pendingCaptureResult.get()
                    ?: throw IllegalStateException("Image buffer could not be retrieved from ImageReader")

                totalCapturesServed.incrementAndGet()
                return result

            } finally {
                pendingCaptureLatch.set(null)
                pendingCaptureResult.set(null)
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // DYNAMIC CONFIGURATION (POST /config)
    // ---------------------------------------------------------------------------------------------

    /**
     * Dynamically updates the manual camera parameters (focus, ISO, exposure time)
     * on the active repeating preview stream and subsequent captures.
     */
    fun updateManualSettings(newSettings: CameraSettings): CameraSettings {
        val clamped = clampSettings(newSettings)
        currentSettings = clamped

        val session = captureSession
        val builder = previewRequestBuilder
        if (session != null && builder != null) {
            try {
                applyStrictManualSettings(builder, clamped)
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                Log.i(TAG, "Dynamically updated manual parameters: Focus=${clamped.focusDistance}D, ISO=${clamped.iso}, Exp=${clamped.exposureTimeNs / 1_000_000.0}ms")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply dynamic configuration update", e)
            }
        }

        onSettingsChangedListener?.invoke(clamped)
        return clamped
    }

    private fun clampSettings(settings: CameraSettings): CameraSettings {
        val clampedIso = isoRange?.clamp(settings.iso) ?: settings.iso
        val clampedExp = exposureRange?.clamp(settings.exposureTimeNs) ?: settings.exposureTimeNs
        val clampedFocus = if (minFocusDistance > 0.0f) {
            settings.focusDistance.coerceIn(0.0f, minFocusDistance)
        } else {
            settings.focusDistance.coerceAtLeast(0.0f)
        }

        return CameraSettings(
            focusDistance = clampedFocus,
            iso = clampedIso,
            exposureTimeNs = clampedExp
        )
    }

    // ---------------------------------------------------------------------------------------------
    // TEARDOWN
    // ---------------------------------------------------------------------------------------------

    fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null

            cameraDevice?.close()
            cameraDevice = null

            imageReader?.close()
            imageReader = null

            previewSurface?.release()
            previewSurface = null

            stopBackgroundThread()
            Log.i(TAG, "Camera2 pipeline closed successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera", e)
        }
    }
}
