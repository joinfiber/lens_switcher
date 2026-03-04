package com.fiber.lens_switcher

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.Preview

/**
 * Static bridge connecting lens_switcher to the host app's CameraX session.
 *
 * Because lens_switcher doesn't manage its own camera, the host camera plugin
 * (CamerAwesome, flutter camera, or custom CameraX) must call two methods
 * during its camera setup for each sensor direction:
 *
 * **Back camera:**
 * 1. [installOnPreview] — BEFORE binding the back camera
 * 2. [attachCamera] — AFTER binding (with the returned Camera object)
 *
 * **Front camera:**
 * 1. [installOnFrontPreview] — BEFORE binding the front camera
 * 2. [attachFrontCamera] — AFTER binding (with the returned Camera object)
 *
 * When the user flips the camera, the host plugin should call the appropriate
 * attach method with the new Camera object. Detach methods are optional but
 * recommended for clean lifecycle management.
 *
 * Example (in CameraXState.updateLifecycle):
 * ```kotlin
 * if (isFrontCamera) {
 *     LensSwitcherBridge.installOnFrontPreview(previewBuilder)
 *     // ... bind ...
 *     LensSwitcherBridge.attachFrontCamera(camera)
 * } else {
 *     LensSwitcherBridge.installOnPreview(previewBuilder)
 *     // ... bind ...
 *     LensSwitcherBridge.attachCamera(camera)
 * }
 * ```
 */
object LensSwitcherBridge {

    private const val TAG = "LensSwitcher"

    /** Physical camera tracker for the back (rear-facing) camera. */
    val tracker = PhysicalCameraTracker()

    /** Physical camera tracker for the front (selfie) camera. */
    val frontTracker = PhysicalCameraTracker()

    /** The currently bound back-facing CameraX Camera instance. */
    var camera: Camera? = null
        private set

    /** The currently bound front-facing CameraX Camera instance. */
    var frontCamera: Camera? = null
        private set

    // ─── Back camera ──────────────────────────────────────────────────────

    /**
     * Install the physical camera tracking callback on a back-camera Preview builder.
     *
     * **Must be called BEFORE** `ProcessCameraProvider.bindToLifecycle()` for the back camera.
     *
     * On API < 29, this is a no-op (physical camera tracking requires Camera2's
     * `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` which was added in API 29).
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun installOnPreview(previewBuilder: Preview.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Camera2Interop.Extender(previewBuilder)
                    .setSessionCaptureCallback(tracker.createCallback())
                Log.d(TAG, "Installed back camera tracker on preview")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install back tracker: ${e.message}")
            }
        } else {
            Log.d(TAG, "API ${Build.VERSION.SDK_INT} < 29, physical camera tracking unavailable")
        }
    }

    /**
     * Attach the back-facing CameraX Camera instance returned by `bindToLifecycle()`.
     *
     * **Must be called AFTER** `ProcessCameraProvider.bindToLifecycle()` for the back camera.
     */
    fun attachCamera(camera: Camera) {
        this.camera = camera
        Log.d(TAG, "Back camera attached")
    }

    /**
     * Detach the back camera when it's unbound or the activity is destroyed.
     */
    fun detachCamera() {
        camera = null
        tracker.reset()
        Log.d(TAG, "Back camera detached")
    }

    // ─── Front camera ─────────────────────────────────────────────────────

    /**
     * Install the physical camera tracking callback on a front-camera Preview builder.
     *
     * **Must be called BEFORE** `ProcessCameraProvider.bindToLifecycle()` for the front camera.
     *
     * On most devices, the front camera is a single physical sensor and no switching
     * occurs. The probe will still run but will return only one lens (empty list from
     * the cache's 2-lens minimum), so the lens picker simply won't appear.
     */
    @SuppressLint("UnsafeOptInUsageError")
    fun installOnFrontPreview(previewBuilder: Preview.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Camera2Interop.Extender(previewBuilder)
                    .setSessionCaptureCallback(frontTracker.createCallback())
                Log.d(TAG, "Installed front camera tracker on preview")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install front tracker: ${e.message}")
            }
        }
    }

    /**
     * Attach the front-facing CameraX Camera instance returned by `bindToLifecycle()`.
     *
     * **Must be called AFTER** `ProcessCameraProvider.bindToLifecycle()` for the front camera.
     */
    fun attachFrontCamera(camera: Camera) {
        frontCamera = camera
        Log.d(TAG, "Front camera attached")
    }

    /**
     * Detach the front camera when it's unbound or the activity is destroyed.
     */
    fun detachFrontCamera() {
        frontCamera = null
        frontTracker.reset()
        Log.d(TAG, "Front camera detached")
    }
}
