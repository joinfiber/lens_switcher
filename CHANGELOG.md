## 0.3.0

Front camera support, probe reliability, and recalibration.

### Features

- **Front camera lens discovery** — `discoverFrontLenses()`, `setFrontCameraZoomRatio()`,
  and `clearFrontCache()` mirror the back-camera API. Most selfie cameras have a single
  sensor (returns empty list), but multi-lens front cameras on flagship devices are now
  fully supported.

- **`clearAllCaches()`** — Clears both back and front camera caches in one call.
  Designed for settings screens with a "Recalibrate lenses" action.

- **Change-aware stability detection** — The probe now waits for the physical camera to
  *actually change* after a zoom ratio update, then confirms the new ID is stable. This
  eliminates false positives where the old camera ID was still being reported during
  the hardware transition. More reliable on devices with slow lens switching.

### Breaking Changes

- **Cache auto-invalidation** — v0.2.0 caches are silently ignored (cache version
  bumped from v2 to v3). The first launch after updating will re-probe once.

### Kotlin Bridge

- `LensSwitcherBridge.installOnFrontPreview()`, `.attachFrontCamera()`,
  `.detachFrontCamera()` — front camera bridge methods (same 2-line pattern).

---

## 0.2.0

Precision, quality, and calibration UX.

### Breaking Changes

- **Cache auto-invalidation** — v0.1.0 caches are silently ignored. The first
  launch after updating will re-probe with the improved algorithm. No action
  required — this is transparent to users.

### Features

- **Binary search precision** — After the coarse sweep, a binary search phase
  refines each lens transition to 0.01x accuracy (~7 iterations per boundary).
  Eliminates digital zoom bleeding through on physical lens taps.

- **Probe progress stream** — `LensSwitcher.probeProgress` provides a
  `Stream<Map<String, dynamic>>` with `{step, totalSteps}` events during
  calibration. Use this to build loading screens for first-launch UX.

### Internal

- Cache keys now include a version prefix (`v2_lenses_{model}`) for
  automatic invalidation when the probe algorithm changes.
- `LensProbe.discover()` accepts an optional `onProgress` callback.
- `LensSwitcherPlugin` registers an `EventChannel` for probe progress.

---

## 0.1.0

Initial release.

### Features

- **Empirical lens discovery** — sweeps CameraX zoom ratios and reads Camera2's
  `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` to find exact physical camera switch points
- **SharedPreferences caching** — keyed by `Build.MODEL`, so the ~5-second probe
  only runs once per device model (instant on every subsequent launch)
- **`LensSwitcher` API** — `discoverLenses()`, `setZoomRatio()`, `clearCache()`
- **`LensPicker` widget** — ready-to-use horizontal pill picker with two built-in
  styles (`.glass` and `.ios`) and full customization via `LensPickerStyle`
- **Static bridge pattern** — `LensSwitcherBridge.installOnPreview()` and
  `.attachCamera()` integrate with any CameraX-based camera plugin in 2 lines
- **Graceful cross-platform** — returns empty list on iOS, web, and desktop
  (no crashes, no conditional logic needed)

### Tested

- Pixel 10 Pro: 3 lenses (.5x ultra-wide, 1x main, 6x telephoto)
