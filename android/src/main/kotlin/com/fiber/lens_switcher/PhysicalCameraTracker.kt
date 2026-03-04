package com.fiber.lens_switcher

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build

/**
 * Tracks which physical camera is currently active in a CameraX logical multi-camera.
 *
 * Uses Camera2's [CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID] (API 29+)
 * to read the physical camera ID from each captured frame. This is the only reliable
 * way to know which physical lens CameraX is routing to at any given zoom ratio.
 *
 * Install the callback on a CameraX Preview via Camera2Interop:
 * ```kotlin
 * val tracker = PhysicalCameraTracker()
 * Camera2Interop.Extender(previewBuilder)
 *     .setSessionCaptureCallback(tracker.createCallback())
 * ```
 */
class PhysicalCameraTracker {

    /**
     * The physical camera ID currently active, as reported by Camera2 capture results.
     * Updated on every frame. Null until the first capture result arrives.
     *
     * Thread-safe: written from camera callback thread, read from main/coroutine thread.
     */
    @Volatile
    var activePhysicalCameraId: String? = null
        private set

    /**
     * Creates a [CameraCaptureSession.CaptureCallback] that reads the active physical
     * camera ID from each frame's capture result.
     *
     * On API < 29, the callback is still installed but does nothing (the field it reads
     * doesn't exist). The probe will return an empty list on those devices.
     */
    fun createCallback(): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val physId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                    if (physId != null && physId != activePhysicalCameraId) {
                        activePhysicalCameraId = physId
                    }
                }
            }
        }
    }

    /** Reset tracking state. Called before each probe sweep. */
    fun reset() {
        activePhysicalCameraId = null
    }
}
