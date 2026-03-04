package com.fiber.lens_switcher

import android.app.Activity
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Flutter plugin entry point for lens_switcher.
 *
 * Registers a MethodChannel ("lens_switcher") with these methods:
 * - `discoverLenses`          — back camera: cached or empirical probe
 * - `discoverFrontLenses`     — front camera: cached or empirical probe
 * - `setZoomRatio`            — set back camera zoom ratio
 * - `setFrontCameraZoomRatio` — set front camera zoom ratio
 * - `clearCache`              — clear back camera cache
 * - `clearFrontCache`         — clear front camera cache
 * - `clearAllCaches`          — clear back + front camera caches
 *
 * Also registers an EventChannel ("lens_switcher/probe_progress") that streams
 * probe progress updates during any lens discovery: `{step: int, totalSteps: int}`.
 *
 * The plugin requires [LensSwitcherBridge] to be set up by the host camera plugin.
 * See the package README for integration instructions.
 */
class LensSwitcherPlugin : FlutterPlugin, ActivityAware {

    private var channel: MethodChannel? = null
    private var progressChannel: EventChannel? = null
    private var progressSink: EventChannel.EventSink? = null
    private var activity: Activity? = null

    companion object {
        private const val TAG = "LensSwitcher"
        private const val CHANNEL_NAME = "lens_switcher"
        private const val PROGRESS_CHANNEL_NAME = "lens_switcher/probe_progress"
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        // Progress EventChannel — streams probe progress to Dart
        progressChannel = EventChannel(binding.binaryMessenger, PROGRESS_CHANNEL_NAME).apply {
            setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    progressSink = events
                }
                override fun onCancel(arguments: Any?) {
                    progressSink = null
                }
            })
        }

        channel = MethodChannel(binding.binaryMessenger, CHANNEL_NAME).apply {
            setMethodCallHandler { call, result ->
                when (call.method) {

                    // ── Back camera ───────────────────────────────────────

                    "discoverLenses" -> {
                        val ctx = activity
                        if (ctx == null) {
                            result.error("NO_ACTIVITY", "Activity not available", null)
                            return@setMethodCallHandler
                        }

                        val cached = LensCache.load(ctx)
                        if (cached != null) {
                            Log.d(TAG, "Returning ${cached.size} cached back lenses")
                            result.success(cached)
                            return@setMethodCallHandler
                        }

                        val camera = LensSwitcherBridge.camera
                        if (camera == null) {
                            result.error(
                                "NO_CAMERA",
                                "Back camera not attached. Call LensSwitcherBridge.attachCamera() " +
                                "in your Android camera setup. See lens_switcher README.",
                                null
                            )
                            return@setMethodCallHandler
                        }

                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val sink = progressSink
                                val lenses = LensProbe.discover(
                                    camera = camera,
                                    tracker = LensSwitcherBridge.tracker,
                                    mainExecutor = ContextCompat.getMainExecutor(ctx),
                                    onProgress = if (sink != null) { step, totalSteps ->
                                        sink.success(mapOf("step" to step, "totalSteps" to totalSteps))
                                    } else null,
                                )
                                LensCache.save(ctx, lenses)
                                result.success(lenses)
                            } catch (e: Exception) {
                                Log.e(TAG, "Back probe failed: ${e.message}")
                                result.error("PROBE_FAILED", e.message, null)
                            }
                        }
                    }

                    "setZoomRatio" -> {
                        val ratio = call.argument<Double>("ratio") ?: 1.0
                        val camera = LensSwitcherBridge.camera
                        if (camera == null) {
                            result.error("NO_CAMERA", "Back camera not attached.", null)
                            return@setMethodCallHandler
                        }
                        try {
                            val future = camera.cameraControl.setZoomRatio(ratio.toFloat())
                            future.addListener({
                                try { future.get() } catch (e: Exception) {
                                    Log.w(TAG, "setZoomRatio async: ${e.message}")
                                }
                            }, ContextCompat.getMainExecutor(activity!!))
                            result.success(null)
                        } catch (e: Exception) {
                            Log.e(TAG, "setZoomRatio failed: ${e.message}")
                            result.error("ZOOM_ERROR", e.message, null)
                        }
                    }

                    "clearCache" -> {
                        val ctx = activity
                        if (ctx != null) LensCache.clear(ctx)
                        result.success(null)
                    }

                    // ── Front camera ──────────────────────────────────────

                    "discoverFrontLenses" -> {
                        val ctx = activity
                        if (ctx == null) {
                            result.error("NO_ACTIVITY", "Activity not available", null)
                            return@setMethodCallHandler
                        }

                        val cached = LensCache.loadFront(ctx)
                        if (cached != null) {
                            Log.d(TAG, "Returning ${cached.size} cached front lenses")
                            result.success(cached)
                            return@setMethodCallHandler
                        }

                        val frontCamera = LensSwitcherBridge.frontCamera
                        if (frontCamera == null) {
                            result.error(
                                "NO_CAMERA",
                                "Front camera not attached. Call LensSwitcherBridge.attachFrontCamera() " +
                                "after binding the front camera. See lens_switcher README.",
                                null
                            )
                            return@setMethodCallHandler
                        }

                        CoroutineScope(Dispatchers.Main).launch {
                            try {
                                val sink = progressSink
                                val lenses = LensProbe.discover(
                                    camera = frontCamera,
                                    tracker = LensSwitcherBridge.frontTracker,
                                    mainExecutor = ContextCompat.getMainExecutor(ctx),
                                    onProgress = if (sink != null) { step, totalSteps ->
                                        sink.success(mapOf("step" to step, "totalSteps" to totalSteps))
                                    } else null,
                                )
                                LensCache.saveFront(ctx, lenses)
                                result.success(lenses)
                            } catch (e: Exception) {
                                Log.e(TAG, "Front probe failed: ${e.message}")
                                result.error("PROBE_FAILED", e.message, null)
                            }
                        }
                    }

                    "setFrontCameraZoomRatio" -> {
                        val ratio = call.argument<Double>("ratio") ?: 1.0
                        val frontCamera = LensSwitcherBridge.frontCamera
                        if (frontCamera == null) {
                            result.error("NO_CAMERA", "Front camera not attached.", null)
                            return@setMethodCallHandler
                        }
                        try {
                            val future = frontCamera.cameraControl.setZoomRatio(ratio.toFloat())
                            future.addListener({
                                try { future.get() } catch (e: Exception) {
                                    Log.w(TAG, "setFrontCameraZoomRatio async: ${e.message}")
                                }
                            }, ContextCompat.getMainExecutor(activity!!))
                            result.success(null)
                        } catch (e: Exception) {
                            Log.e(TAG, "setFrontCameraZoomRatio failed: ${e.message}")
                            result.error("ZOOM_ERROR", e.message, null)
                        }
                    }

                    "clearFrontCache" -> {
                        val ctx = activity
                        if (ctx != null) LensCache.clearFront(ctx)
                        result.success(null)
                    }

                    // ── Combined ──────────────────────────────────────────

                    "clearAllCaches" -> {
                        val ctx = activity
                        if (ctx != null) LensCache.clearAll(ctx)
                        result.success(null)
                    }

                    else -> result.notImplemented()
                }
            }
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        progressChannel?.setStreamHandler(null)
        progressChannel = null
        progressSink = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }
}
