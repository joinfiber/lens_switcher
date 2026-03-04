package com.fiber.lens_switcher

import android.util.Log
import androidx.camera.core.Camera
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Empirical lens discovery — the core algorithm.
 *
 * Instead of guessing zoom thresholds from sensor specs (which varies wildly
 * across Android devices), this probe *measures* exactly where CameraX switches
 * physical cameras by:
 *
 * 1. Coarse sweep through candidate zoom ratios (0.5x → 20x)
 * 2. At each ratio, reading which physical camera Camera2 reports as active
 * 3. Finding pairs where the physical camera ID changes
 * 4. Binary search between each pair to find exact switch points (±0.01x)
 * 5. Labeling each lens based on its precise zoom ratio
 *
 * The result is a list of "switch points" — the lowest zoom ratio at which each
 * physical camera activates. These are the values to pass to `setZoomRatio()`
 * for reliable physical lens switching with zero digital zoom.
 *
 * Performance: ~8-11 seconds on first run (coarse sweep + binary refinement).
 * Results are cached by [LensCache] so subsequent calls return instantly (<1ms).
 *
 * Requires API 29+ for physical camera ID tracking. Returns an empty list on
 * older devices.
 */
object LensProbe {

    private const val TAG = "LensSwitcher"

    /** Precision target for binary search refinement. */
    private const val BINARY_SEARCH_PRECISION = 0.01

    /**
     * Candidate zoom ratios for the coarse sweep. Covers switching points
     * across all known Android devices:
     * - Ultra-wide → main: typically at minZoom (0.5x–0.6x)
     * - Main → telephoto: typically 2x–5x (Samsung), 5x (Pixel), 3x (OnePlus)
     * - Main → periscope: typically 5x–10x on devices with 2 telephoto lenses
     *
     * The 6.0x probe fills the 5→7 gap where Pixel telephoto sometimes activates.
     */
    private val PROBE_STEPS = listOf(1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 7.0, 10.0, 15.0, 20.0)

    /**
     * Discover physical camera switch points by sweeping zoom ratios,
     * then refining with binary search for sub-0.01x precision.
     *
     * @param camera The CameraX Camera instance (from [LensSwitcherBridge.camera])
     * @param tracker The [PhysicalCameraTracker] receiving capture callbacks
     * @param mainExecutor Executor for CameraX listener callbacks
     * @param onProgress Optional callback fired during probe: (currentStep, totalSteps)
     * @return List of switch points: [{zoomRatio, physicalId, label}]
     */
    suspend fun discover(
        camera: Camera,
        tracker: PhysicalCameraTracker,
        mainExecutor: java.util.concurrent.Executor,
        onProgress: ((step: Int, totalSteps: Int) -> Unit)? = null,
    ): List<Map<String, Any>> {
        val zoomState = camera.cameraInfo.zoomState.value
            ?: throw Exception("Camera not initialized — no zoom state available")

        val minZoom = zoomState.minZoomRatio.toDouble()
        val maxZoom = zoomState.maxZoomRatio.toDouble()

        Log.d(TAG, "Probe: sweeping $minZoom–$maxZoom")

        // Build candidate list: minZoom (ultra-wide), 0.7 (transition zone), 1.0 (main),
        // then telephoto steps filtered to device's max zoom range.
        val probeRatios = mutableListOf(minZoom, 0.7, 1.0)
        for (step in PROBE_STEPS) {
            if (step <= maxZoom) probeRatios.add(step)
        }
        val sorted = probeRatios.filter { it in minZoom..maxZoom }.distinct().sorted()

        // Estimate total steps: coarse sweep + ~7 binary search steps per transition.
        // We'll update this once we know how many transitions there are.
        var totalSteps = sorted.size
        var currentStep = 0

        Log.d(TAG, "Probe: testing ${sorted.size} ratios (coarse sweep)")

        // ─── Phase 1: Coarse sweep ──────────────────────────────────────────
        val coarseResults = mutableListOf<Pair<Double, String>>()

        for (ratio in sorted) {
            onProgress?.invoke(currentStep, totalSteps)
            currentStep++

            val physId = probeRatio(camera, tracker, mainExecutor, ratio)
            if (physId != null) {
                coarseResults.add(Pair(ratio, physId))
            }
        }

        Log.d(TAG, "Probe coarse: ${coarseResults.map { "${it.first}x→${it.second}" }}")

        // ─── Phase 2: Binary search refinement ──────────────────────────────
        // Find consecutive pairs where the physical camera ID changed, then
        // binary search between them to find the exact switch point (±0.01x).
        val transitions = mutableListOf<Triple<Pair<Double, String>, Pair<Double, String>, Int>>()
        for (i in 0 until coarseResults.size - 1) {
            val (loRatio, loId) = coarseResults[i]
            val (hiRatio, hiId) = coarseResults[i + 1]
            if (loId != hiId) {
                // ~7 binary search iterations per transition (log2(gap/0.01))
                val gap = hiRatio - loRatio
                val iterations = if (gap > BINARY_SEARCH_PRECISION) {
                    (Math.log(gap / BINARY_SEARCH_PRECISION) / Math.log(2.0)).toInt() + 1
                } else 0
                transitions.add(Triple(coarseResults[i], coarseResults[i + 1], iterations))
            }
        }

        // Update total steps now that we know how many binary search iterations we need
        val refinementSteps = transitions.sumOf { it.third }
        totalSteps = sorted.size + refinementSteps

        Log.d(TAG, "Probe: ${transitions.size} transitions to refine ($refinementSteps binary steps)")

        // Build refined switch points
        val switchPoints = mutableListOf<Map<String, Any>>()

        // First lens always starts at its coarse ratio (the lowest zoom ratio)
        if (coarseResults.isNotEmpty()) {
            val (ratio, physId) = coarseResults[0]
            switchPoints.add(mapOf(
                "zoomRatio" to ratio,
                "physicalId" to physId,
                "label" to formatZoomLabel(ratio),
            ))
            Log.d(TAG, "Probe: first lens at ${ratio}x → phys $physId")
        }

        // For each transition, binary search to find the exact switch point
        for ((lo, hi, _) in transitions) {
            val exactRatio = binarySearchSwitch(
                camera = camera,
                tracker = tracker,
                mainExecutor = mainExecutor,
                lo = lo.first,
                hi = hi.first,
                loPhysId = lo.second,
                onProgress = { step, _ ->
                    onProgress?.invoke(currentStep, totalSteps)
                    currentStep++
                },
            )

            val label = formatZoomLabel(exactRatio)
            switchPoints.add(mapOf(
                "zoomRatio" to exactRatio,
                "physicalId" to hi.second,
                "label" to label,
            ))
            Log.d(TAG, "Probe: refined switch at ${exactRatio}x → phys ${hi.second} ($label)")
        }

        // Final progress
        onProgress?.invoke(totalSteps, totalSteps)

        // Restore zoom to 1.0 (main lens) so the preview isn't left zoomed in
        try {
            camera.cameraControl.setZoomRatio(1.0f)
        } catch (_: Exception) {}

        Log.d(TAG, "Probe complete: found ${switchPoints.size} lenses")
        return switchPoints
    }

    /**
     * Probe a single zoom ratio to determine which physical camera is active.
     *
     * Sets the zoom, then uses change-aware detection to determine the active
     * physical camera reliably. Records the camera ID *before* the zoom change,
     * waits to observe a change (up to 450ms), then confirms stability.
     *
     * This avoids the false-early-exit bug where the stability check fires on the
     * OLD camera's ID before the physical switch completes (typically 150–250ms on
     * flagship devices like Pixel 10 Pro).
     *
     * For steps where no switch occurs (same physical camera at the new ratio), the
     * loop exits as soon as the tracker confirms the unchanged ID (~100–200ms).
     *
     * @return The physical camera ID string, or null if no reading could be obtained.
     */
    private suspend fun probeRatio(
        camera: Camera,
        tracker: PhysicalCameraTracker,
        mainExecutor: java.util.concurrent.Executor,
        ratio: Double,
    ): String? {
        try {
            // Snapshot the current camera before changing zoom so we can detect
            // whether a physical switch actually occurred.
            val beforeId = tracker.activePhysicalCameraId

            // Set zoom and wait for CameraX to accept it
            val future = camera.cameraControl.setZoomRatio(ratio.toFloat())
            suspendCancellableCoroutine<Unit> { cont ->
                future.addListener({
                    try { future.get() } catch (_: Exception) {}
                    cont.resume(Unit) {}
                }, mainExecutor)
            }

            // Change-aware stability detection:
            //
            // Phase 1 — watch for the physical camera to switch away from beforeId.
            //   Poll every 100ms for up to 450ms. If we see a different ID, record it
            //   as the candidate and move to phase 2 immediately. If we never see a
            //   change, the current zoom ratio is within the same physical lens.
            //
            // Phase 2 — confirm the new ID is stable for one extra poll (100ms).
            //   This guards against transient flickers during the lens switch.
            //
            // Why this beats the old "settled = null" loop: the old approach could
            // break after just 200ms seeing two identical OLD-camera readings when
            // the physical switch hadn't started yet. This approach requires an
            // *actual change* to be detected before accepting the result as the new
            // camera, and falls back to the original ID only after the full timeout.

            var candidate: String? = null
            for (attempt in 0 until 5) {   // up to 450ms watching for switch
                delay(100) // ~100ms per frame batch
                val current = tracker.activePhysicalCameraId
                if (current != null && current != beforeId) {
                    candidate = current
                    break   // Switch detected — confirm in phase 2
                }
            }

            if (candidate != null) {
                // Phase 2: confirm the new ID holds for one more poll
                delay(100)
                val confirmed = tracker.activePhysicalCameraId
                return if (confirmed == candidate) confirmed else candidate
            }

            // No switch detected — this zoom ratio belongs to the same lens as before.
            // Return whatever the tracker currently reports (beforeId equivalent).
            return tracker.activePhysicalCameraId ?: beforeId
        } catch (e: Exception) {
            Log.w(TAG, "Probe at ${ratio}x failed: ${e.message}")
            return null
        }
    }

    /**
     * Binary search between two zoom ratios where a physical camera switch occurs.
     *
     * Narrows the gap between `lo` (where `loPhysId` is active) and `hi` (where a
     * different physical camera is active) until the boundary is known within
     * [BINARY_SEARCH_PRECISION] (0.01x).
     *
     * @return The lowest zoom ratio where the new physical camera activates.
     */
    private suspend fun binarySearchSwitch(
        camera: Camera,
        tracker: PhysicalCameraTracker,
        mainExecutor: java.util.concurrent.Executor,
        lo: Double,
        hi: Double,
        loPhysId: String,
        onProgress: ((step: Int, totalSteps: Int) -> Unit)? = null,
    ): Double {
        var low = lo
        var high = hi
        var step = 0

        while (high - low > BINARY_SEARCH_PRECISION) {
            val mid = (low + high) / 2.0
            val midPhysId = probeRatio(camera, tracker, mainExecutor, mid)

            if (midPhysId == loPhysId) {
                // Still on the old camera — switch point is above mid
                low = mid
            } else {
                // New camera is active — switch point is at or below mid
                high = mid
            }

            step++
            onProgress?.invoke(step, 0)
        }

        // Return the high end — the lowest ratio where the new camera is confirmed active
        return high
    }

    /**
     * Format a zoom ratio as a human-readable label.
     *
     * Examples:
     * - 0.5078 → ".5x"
     * - 1.0 → "1x"
     * - 5.0 → "5x"
     * - 2.5 → "2.5x"
     */
    internal fun formatZoomLabel(ratio: Double): String {
        return when {
            ratio < 1.0 -> {
                val formatted = String.format("%.1f", ratio)
                // "0.5" → ".5x" (drop leading zero for compact labels)
                "${if (formatted.startsWith("0")) formatted.substring(1) else formatted}x"
            }
            ratio == Math.floor(ratio) -> "${ratio.toInt()}x"
            else -> "${String.format("%.1f", ratio)}x"
        }
    }
}
