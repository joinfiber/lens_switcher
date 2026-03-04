import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'lens.dart';

/// Discovers and switches between physical camera lenses on Android.
///
/// Uses CameraX's zoom ratio control combined with Camera2's physical camera
/// tracking to find the exact zoom ratios where the device switches between
/// its ultra-wide, main, and telephoto cameras.
///
/// Supports both back (rear) and front (selfie) cameras independently.
/// Results are cached per device model and sensor direction, so the expensive
/// probe (~8-11 seconds) only runs once — ever.
///
/// ## Quick start
///
/// ```dart
/// // 1. Discover available back lenses (instant after first run)
/// final lenses = await LensSwitcher.discoverLenses();
///
/// // 2. Switch to a specific back lens
/// await LensSwitcher.setZoomRatio(lenses.last.zoomRatio); // telephoto
///
/// // 3. Discover front lenses (empty on most phones — no multi-camera front)
/// final frontLenses = await LensSwitcher.discoverFrontLenses();
/// ```
///
/// ## Android setup required
///
/// The host camera plugin must call bridge methods during CameraX setup.
/// See the package README for integration instructions.
///
/// ## iOS
///
/// Returns empty lists on iOS. Physical lens switching on iOS is handled
/// natively by AVFoundation and doesn't need this package.
class LensSwitcher {
  static const _channel = MethodChannel('lens_switcher');
  static const _progressChannel = EventChannel('lens_switcher/probe_progress');

  /// Stream of probe progress updates during lens discovery.
  ///
  /// Each event is a `Map<String, dynamic>` with:
  /// - `step` (int): Current step number (0-based)
  /// - `totalSteps` (int): Total expected steps
  ///
  /// The stream emits events during any active probe (back or front camera).
  /// If lenses are returned from cache, no events are emitted.
  ///
  /// Listen to this stream *before* calling [discoverLenses] or
  /// [discoverFrontLenses] to receive all progress updates.
  static Stream<Map<String, dynamic>> get probeProgress {
    try {
      return _progressChannel.receiveBroadcastStream().map((event) {
        return Map<String, dynamic>.from(event as Map);
      }).handleError((_) {}, test: (e) => e is MissingPluginException);
    } on MissingPluginException {
      return const Stream.empty();
    }
  }

  // ── Back camera ─────────────────────────────────────────────────────────

  /// Discover available physical back-camera lenses.
  ///
  /// On the first call for a given device model, this runs an empirical probe
  /// that takes ~8-11 seconds. All subsequent calls return cached results
  /// instantly (<1ms).
  ///
  /// Returns a list of [Lens] objects sorted by zoom ratio (ultra-wide first,
  /// telephoto last). The list is empty if:
  /// - The device has only one back camera (no switching possible)
  /// - Running on iOS (not supported)
  /// - Running on Android API < 29
  /// - The bridge is not set up (see README)
  ///
  /// Typical results:
  /// - 2 lenses: ultra-wide + main (most mid-range phones)
  /// - 3 lenses: ultra-wide + main + telephoto (flagship phones)
  static Future<List<Lens>> discoverLenses() async {
    return _discoverLenses('discoverLenses');
  }

  /// Switch to a physical back-camera lens by setting the CameraX zoom ratio.
  ///
  /// Pass the [Lens.zoomRatio] from a lens returned by [discoverLenses].
  /// CameraX will physically switch to the camera that activates at that ratio.
  static Future<void> setZoomRatio(double ratio) async {
    await _setZoomRatio('setZoomRatio', ratio);
  }

  /// Clear cached back-camera lens data for the current device model.
  ///
  /// The next call to [discoverLenses] will re-run the probe (~8-11 seconds).
  static Future<void> clearCache() async {
    await _invokeVoid('clearCache');
  }

  // ── Front camera ────────────────────────────────────────────────────────

  /// Discover available physical front-camera (selfie) lenses.
  ///
  /// Behaves identically to [discoverLenses] but targets the front-facing camera.
  /// On most Android phones the front camera is a single sensor, so this returns
  /// an empty list (no multi-lens front setup detected). The lens picker widget
  /// handles an empty list gracefully by not rendering.
  ///
  /// Devices with wide + standard selfie cameras (some Samsung Galaxy S-series)
  /// will return 2 lenses here.
  static Future<List<Lens>> discoverFrontLenses() async {
    return _discoverLenses('discoverFrontLenses');
  }

  /// Switch to a physical front-camera lens by setting the CameraX zoom ratio.
  ///
  /// Pass the [Lens.zoomRatio] from a lens returned by [discoverFrontLenses].
  static Future<void> setFrontCameraZoomRatio(double ratio) async {
    await _setZoomRatio('setFrontCameraZoomRatio', ratio);
  }

  /// Clear cached front-camera lens data for the current device model.
  ///
  /// The next call to [discoverFrontLenses] will re-run the probe.
  static Future<void> clearFrontCache() async {
    await _invokeVoid('clearFrontCache');
  }

  // ── Combined ─────────────────────────────────────────────────────────────

  /// Clear both back and front camera lens caches.
  ///
  /// The next calls to [discoverLenses] and [discoverFrontLenses] will re-run
  /// their respective probes. Use this for the "Recalibrate lenses" settings action.
  static Future<void> clearAllCaches() async {
    await _invokeVoid('clearAllCaches');
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  static Future<List<Lens>> _discoverLenses(String method) async {
    try {
      final result = await _channel.invokeMethod(method);
      if (result == null || result is! List) return [];

      final lenses = <Lens>[];
      for (final item in result) {
        final map = Map<String, dynamic>.from(item as Map);
        lenses.add(Lens(
          label: map['label'] as String,
          zoomRatio: (map['zoomRatio'] as num).toDouble(),
          physicalId: map['physicalId'] as String,
        ));
      }
      return lenses;
    } on MissingPluginException {
      return [];
    } catch (e) {
      debugPrint('[LensSwitcher] $method failed: $e');
      return [];
    }
  }

  static Future<void> _setZoomRatio(String method, double ratio) async {
    try {
      await _channel.invokeMethod(method, {'ratio': ratio});
    } on MissingPluginException {
      // Platform doesn't have the plugin
    } catch (e) {
      debugPrint('[LensSwitcher] $method failed: $e');
    }
  }

  static Future<void> _invokeVoid(String method) async {
    try {
      await _channel.invokeMethod(method);
    } on MissingPluginException {
      // Platform doesn't have the plugin
    } catch (e) {
      debugPrint('[LensSwitcher] $method failed: $e');
    }
  }
}
