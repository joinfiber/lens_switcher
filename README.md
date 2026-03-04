# lens_switcher

**Physical camera lens switching for Flutter on Android.**

Automatically discovers ultra-wide, main, and telephoto lenses and their exact zoom switching points via CameraX. No hardcoded values, no device-specific tables — works on any Android device with multiple cameras.

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## Why This Exists

Modern Android phones have 2–4 physical cameras behind one logical lens. CameraX seamlessly switches between them as you change the zoom ratio, but it doesn't tell you:

- **Which physical camera is active** at any given zoom
- **Where the switching points are** (the zoom ratio where one lens hands off to the next)
- **What lenses the device even has**

Every device is different. Pixel switches to telephoto at 5–6x. Samsung does it at 2–3x. OnePlus at 3x. Hardcoding these values is fragile and breaks on new devices.

**lens_switcher solves this by measuring reality.** On first launch, it runs a two-phase probe: a coarse sweep identifies which lenses exist, then binary search refines each transition to 0.01x precision. Results are cached forever (same device model = same cameras). Every subsequent launch is instant.

## What You Get

- **`LensSwitcher.discoverLenses()`** — returns the device's physical camera lenses with their exact switch points
- **`LensSwitcher.discoverFrontLenses()`** — same for the front (selfie) camera
- **`LensSwitcher.setZoomRatio()`** / **`setFrontCameraZoomRatio()`** — switch to a specific physical lens with zero digital zoom
- **`LensSwitcher.clearAllCaches()`** — recalibrate both cameras (for settings screens)
- **`LensSwitcher.probeProgress`** — a stream of progress events for building calibration UIs
- **`LensPicker` widget** — a ready-made lens selector with two built-in styles (glass overlay and iOS Camera-inspired)
- **Binary search precision** — 0.01x accuracy on lens transitions, eliminating digital zoom bleed
- **Change-aware stability detection** — waits for the physical camera to actually switch before reading, eliminating false positives
- **Zero device-specific code** — the empirical probe adapts to any device automatically
- **Instant after first run** — SharedPreferences cache, keyed by device model

## How It Works

```
               Empirical Probe (~8-11 seconds, first launch only)
               ════════════════════════════════════════════════════

  Phase 1 — Coarse sweep (14 ratios, ~5s):
   Sweep zoom:    .5x   .7x   1x   1.5x   2x   3x   4x   5x   6x   7x   10x
                   │           │                                 │
   Active camera:  3     3     2     2      2    2    2    2     4    4     4
                   │           │                                 │
   Transitions:         ─────▶◄──                          ────▶◄──

  Phase 2 — Binary search refinement (~7 steps per transition, ~3s):
   Between 0.7x and 1.0x:    0.85→3  0.93→3  0.97→2  0.95→3  0.96→2  → switch at 0.96x
   Between 5.0x and 6.0x:    5.5→2   5.75→4  5.63→4  5.56→4  5.53→2  → switch at 4.98x

  Result: [Lens(.5x, zoom: 0.508), Lens(1x, zoom: 0.96), Lens(5x, zoom: 4.98)]

  Cached in SharedPreferences → instant on every subsequent launch (<1ms).
```

The probe uses Camera2's `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` (API 29+) — the only reliable way to know which physical camera CameraX is routing frames through at any given zoom ratio.

---

## Quick Start

### 1. Add the dependency

```yaml
# pubspec.yaml
dependencies:
  lens_switcher:
    git:
      url: https://github.com/joinfiber/lens_switcher.git
```

### 2. Set up the Android bridge (2 lines of Kotlin per camera)

lens_switcher needs to hook into your CameraX session. Wherever you set up CameraX, add two lines:

```kotlin
import com.fiber.lens_switcher.LensSwitcherBridge

// Back camera setup:
val previewBuilder = Preview.Builder()
LensSwitcherBridge.installOnPreview(previewBuilder)  // ← BEFORE binding

val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
LensSwitcherBridge.attachCamera(camera)               // ← AFTER binding
```

**Front camera** (optional — same pattern):

```kotlin
// Front camera setup:
val frontPreviewBuilder = Preview.Builder()
LensSwitcherBridge.installOnFrontPreview(frontPreviewBuilder)  // ← BEFORE binding

val frontCamera = cameraProvider.bindToLifecycle(lifecycleOwner, frontSelector, frontPreview)
LensSwitcherBridge.attachFrontCamera(frontCamera)               // ← AFTER binding
```

Two lines per camera. Most apps only need the back camera.

### 3. Use in Flutter

```dart
import 'package:lens_switcher/lens_switcher.dart';

// Discover lenses (instant after first run)
final lenses = await LensSwitcher.discoverLenses();
// → [Lens(.5x, zoom: 0.508), Lens(1x, zoom: 0.96), Lens(5x, zoom: 4.98)]

// Switch to telephoto
await LensSwitcher.setZoomRatio(lenses.last.zoomRatio);

// Or use the ready-made widget
LensPicker(
  lenses: lenses,
  activeLensIndex: _currentIndex,
  onLensSelected: (index) {
    setState(() => _currentIndex = index);
    LensSwitcher.setZoomRatio(lenses[index].zoomRatio);
  },
)
```

Three steps total. Physical lens switching on any Android device.

**Front camera** works the same way:

```dart
final frontLenses = await LensSwitcher.discoverFrontLenses();
// Most selfie cameras have a single sensor → empty list. Multi-lens
// front cameras on flagship devices return their lenses here.
```

---

## Calibration UI

On first launch, the probe takes ~8-11 seconds. Use `probeProgress` to show a loading screen:

```dart
import 'package:lens_switcher/lens_switcher.dart';

// Subscribe BEFORE calling discoverLenses
final sub = LensSwitcher.probeProgress.listen((event) {
  final step = event['step'] as int;
  final total = event['totalSteps'] as int;
  setState(() => _progress = step / total);
});

final lenses = await LensSwitcher.discoverLenses();
sub.cancel();

// On cache hit (every launch after the first), no progress events are
// emitted and discoverLenses returns in <1ms. Your loading screen is
// never shown — users only see it once, ever.
```

The progress stream emits `{step: int, totalSteps: int}` events during both the coarse sweep and binary search phases. `totalSteps` is estimated and may increase slightly as the probe discovers more transitions.

**Cache hit behavior:** When lenses are returned from cache (<1ms), no progress events are emitted. This means any loading UI you build will only appear on the very first launch — exactly when users need it.

---

## Integration Guides

### CamerAwesome

[CamerAwesome](https://pub.dev/packages/camerawesome) is the most popular Flutter camera plugin. It uses CameraX internally, but doesn't expose the Preview builder or Camera object — you'll need a local fork or patch.

**Step 1.** In your CamerAwesome fork, open `CameraXState.kt`. Find the `updateLifecycle()` method.

**Step 2.** Find where `Preview.Builder()` is created. Add one line before `bindToLifecycle`:

```kotlin
val previewBuilder = Preview.Builder()
    // ... existing config ...

// Add this line:
com.fiber.lens_switcher.LensSwitcherBridge.installOnPreview(previewBuilder)
```

**Step 3.** Find `cameraProvider.bindToLifecycle(...)`. Add one line after it:

```kotlin
previewCamera = cameraProvider.bindToLifecycle(
    activity as LifecycleOwner,
    cameraSelector,
    useCaseGroupBuilder.build(),
)

// Add this line:
com.fiber.lens_switcher.LensSwitcherBridge.attachCamera(previewCamera!!)
```

**Step 4.** Add lens_switcher as a Gradle dependency in CamerAwesome's `android/build.gradle`:

```gradle
dependencies {
    // ... existing deps ...
    implementation project(':lens_switcher')
}
```

**Step 5.** Use in your Flutter camera screen:

```dart
import 'package:lens_switcher/lens_switcher.dart';
import 'package:camerawesome/camerawesome_plugin.dart';

class CameraScreen extends StatefulWidget {
  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  List<Lens> _lenses = [];
  int _activeLensIndex = 0;
  bool _probeStarted = false;

  Future<void> _discoverLenses() async {
    if (_probeStarted) return;
    _probeStarted = true;

    final lenses = await LensSwitcher.discoverLenses();
    if (!mounted || lenses.isEmpty) return;

    final mainIndex = lenses.indexWhere((l) => l.zoomRatio == 1.0);
    final selected = mainIndex >= 0 ? mainIndex : 0;

    // Sync hardware to the selected lens on startup
    await LensSwitcher.setZoomRatio(lenses[selected].zoomRatio);
    if (!mounted) return;

    setState(() {
      _lenses = lenses;
      _activeLensIndex = selected;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: [
        CameraAwesomeBuilder.custom(
          builder: (state, previewSize) {
            return state.when(
              onPhotoMode: (photoState) {
                _discoverLenses();
                return const SizedBox.shrink();
              },
              onPreparingCamera: (_) => const Center(
                child: CircularProgressIndicator(),
              ),
              onVideoMode: (_) => const SizedBox.shrink(),
              onVideoRecordingMode: (_) => const SizedBox.shrink(),
            );
          },
        ),

        // Lens picker overlay — auto-hides with fewer than 2 lenses
        Positioned(
          bottom: 16,
          right: 16,
          child: LensPicker(
            lenses: _lenses,
            activeLensIndex: _activeLensIndex,
            onLensSelected: (index) {
              setState(() => _activeLensIndex = index);
              LensSwitcher.setZoomRatio(_lenses[index].zoomRatio);
            },
          ),
        ),
      ],
    );
  }
}
```

### Flutter `camera` Package

If you're using the official [camera](https://pub.dev/packages/camera) package, it also uses CameraX on Android. You'll need to fork it and add the same two bridge lines to its CameraX setup code (typically in `Camera2.kt` or similar).

### Custom CameraX (no Flutter plugin)

If you manage CameraX directly in your Android code:

```kotlin
import com.fiber.lens_switcher.LensSwitcherBridge

class MyCameraManager(private val context: Context) {

    fun startCamera(lifecycleOwner: LifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().also { builder ->
                LensSwitcherBridge.installOnPreview(builder)
            }.build()

            val imageCapture = ImageCapture.Builder().build()

            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
            )

            LensSwitcherBridge.attachCamera(camera)
        }, ContextCompat.getMainExecutor(context))
    }
}
```

---

## API Reference

### `LensSwitcher` (Dart)

The main entry point. All methods are static.

**Back camera:**

| Method | Returns | Description |
|--------|---------|-------------|
| `discoverLenses()` | `Future<List<Lens>>` | Discover physical camera lenses. Returns cached results instantly (<1ms), or runs the probe (~8-11s) on first call per device model. Returns an empty list on iOS, devices with a single camera, or Android API < 29. |
| `setZoomRatio(double ratio)` | `Future<void>` | Set the CameraX zoom ratio. Pass a `Lens.zoomRatio` value to switch to that physical camera with zero digital zoom. You can also pass intermediate values for digital zoom within a lens. |
| `clearCache()` | `Future<void>` | Clear cached back-camera lens data for the current device model. The next `discoverLenses()` call will re-run the probe. |

**Front camera:**

| Method | Returns | Description |
|--------|---------|-------------|
| `discoverFrontLenses()` | `Future<List<Lens>>` | Discover front camera lenses. Same probe algorithm as back camera. Most selfie cameras have a single sensor and return an empty list. Multi-lens front cameras on flagship devices are fully supported. |
| `setFrontCameraZoomRatio(double ratio)` | `Future<void>` | Set the front camera's CameraX zoom ratio. |
| `clearFrontCache()` | `Future<void>` | Clear cached front-camera lens data. |

**Shared:**

| Method | Returns | Description |
|--------|---------|-------------|
| `clearAllCaches()` | `Future<void>` | Clear both back and front camera caches. Designed for "Recalibrate lenses" settings actions. |
| `probeProgress` | `Stream<Map<String, dynamic>>` | Progress events during calibration: `{step: int, totalSteps: int}`. Only emits during an active probe (cache miss). Fires for both back and front camera probes. |

### `Lens` (Dart)

A physical camera lens discovered by the probe.

| Property | Type | Example | Description |
|----------|------|---------|-------------|
| `label` | `String` | `".5x"`, `"1x"`, `"5x"` | Human-readable zoom label |
| `zoomRatio` | `double` | `0.508`, `0.96`, `4.98` | The CameraX zoom ratio where this lens activates (0.01x precision) |
| `physicalId` | `String` | `"3"`, `"2"`, `"4"` | Camera2 physical camera ID (device-specific) |

`Lens` implements `==` and `hashCode` based on `physicalId`, so you can use it in sets and maps.

### `LensPicker` (Dart Widget)

A horizontal pill picker for switching between physical lenses. Auto-hides when given fewer than 2 lenses.

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `lenses` | `List<Lens>` | *required* | The lenses to display |
| `activeLensIndex` | `int` | *required* | Index of the currently active lens |
| `onLensSelected` | `ValueChanged<int>` | *required* | Called when the user taps a lens pill |
| `style` | `LensPickerStyle` | `.glass` | Visual style preset or custom style |
| `enableHaptics` | `bool` | `true` | Whether to trigger haptic feedback on tap |

### `LensPickerStyle`

Two built-in presets, or create your own:

| Preset | Look | Best for |
|--------|------|----------|
| `.glass` | Warm cream text on translucent dark background | Camera preview overlays |
| `.ios` | Yellow active indicator on dark background | Apple Camera-inspired UIs |

```dart
// Use a preset
LensPicker(style: LensPickerStyle.ios, ...)

// Or fully customize
LensPicker(
  style: LensPickerStyle(
    activeTextColor: Colors.amber,
    inactiveTextColor: Colors.white70,
    activePillColor: Colors.amber.withOpacity(0.2),
    backgroundColor: Colors.black54,
    pillSize: 36,
    fontSize: 13,
    activeFontWeight: FontWeight.w700,
    inactiveFontWeight: FontWeight.w400,
  ),
  ...
)
```

### `LensSwitcherBridge` (Kotlin/Android)

Static bridge connecting lens_switcher to the host camera plugin's CameraX session.

**Back camera:**

| Method | When to Call | What It Does |
|--------|-------------|--------------|
| `installOnPreview(builder)` | Before `bindToLifecycle()` | Installs a Camera2 capture callback on the preview that tracks which physical camera is active on each frame |
| `attachCamera(camera)` | After `bindToLifecycle()` | Gives lens_switcher access to `CameraControl` (zoom) and `CameraInfo` (zoom range) |
| `detachCamera()` | On camera unbind *(optional)* | Clears the camera reference and resets tracking state |

**Front camera:**

| Method | When to Call | What It Does |
|--------|-------------|--------------|
| `installOnFrontPreview(builder)` | Before `bindToLifecycle()` | Same as `installOnPreview` but for the front camera's preview |
| `attachFrontCamera(camera)` | After `bindToLifecycle()` | Same as `attachCamera` but for the front camera |
| `detachFrontCamera()` | On camera unbind *(optional)* | Clears the front camera reference and resets tracking state |

---

## Technical Details

### The Probe Algorithm

**Phase 1 — Coarse sweep** (~5 seconds):

1. **Read zoom range** — CameraX exposes `ZoomState.minZoomRatio` and `maxZoomRatio` (e.g., 0.5x–30x on Pixel 10 Pro)
2. **Build candidate ratios** — `[minZoom, 0.7, 1.0, 1.5, 2.0, 2.5, 3.0, 4.0, 5.0, 6.0, 7.0, 10.0, 15.0, 20.0]`, filtered to the device's zoom range
3. **Sweep** — For each candidate:
   - Call `CameraControl.setZoomRatio()`
   - Wait for CameraX to apply it (listen on the `ListenableFuture`)
   - **Change-aware stability detection:** Poll at 100ms intervals, waiting for the physical camera ID to *actually change* from the previous value (up to 500ms). Once a new ID appears, confirm it's stable with one additional 100ms poll. This eliminates false positives where the old camera is still being reported during hardware transition.
   - Record the mapping: `zoomRatio → physicalCameraId`
4. **Detect transitions** — Walk the sorted results and find consecutive pairs where the physical camera ID changed

**Phase 2 — Binary search refinement** (~3-6 seconds):

5. For each transition found in step 4:
   - Binary search between the two coarse ratios to find the exact switch point
   - Each iteration: probe the midpoint, narrow the half that still has the old camera
   - ~7 iterations per transition at ~350ms each = ~2.5s per boundary
   - Precision target: 0.01x (e.g., telephoto activates at 4.98x, not "somewhere between 4x and 5x")
6. **Label** — Format each switch point as a human-readable label: 0.508 → ".5x", 0.96 → "1x", 4.98 → "5x"
7. **Restore** — Set zoom back to 1.0x so the preview isn't left zoomed in
8. **Cache** — Save to SharedPreferences keyed by `v3_lenses_{Build.MODEL}` (back) or `v3_front_lenses_{Build.MODEL}` (front)

### Why Binary Search Matters

Without binary search, the coarse sweep might find that the telephoto activates "somewhere between 4x and 5x" and record 5.0x as the switch point. But if the physical camera actually activates at 4.8x, setting 5.0x applies 0.2x of unwanted digital zoom on top of the telephoto lens. Binary search finds 4.8x exactly, so tapping the telephoto button gives you a pristine physical lens image.

### Why Empirical > Heuristic

Other approaches and why they fall short:

| Approach | Problem |
|----------|---------|
| **Hardcoded thresholds** ("Samsung = 3x, Pixel = 5x") | Breaks on new devices, firmware updates, carrier variants |
| **Focal length ratios** | Different sensor sizes mean focal length doesn't map directly to CameraX zoom ratios |
| **FOV classification** | Can identify lens *types* (wide vs tele) but not the exact CameraX zoom ratio where the switch happens |
| **Camera2 physical camera enumeration** | Tells you what cameras exist, but not which zoom ratio activates which one |

The empirical probe measures the actual behavior of CameraX's zoom pipeline on the specific device. It's the only approach that works everywhere without maintenance.

### Caching

| Property | Value |
|----------|-------|
| **Cache key** | `v3_lenses_{Build.MODEL}` (back) / `v3_front_lenses_{Build.MODEL}` (front) |
| **Storage** | `SharedPreferences` (persists across app launches and updates) |
| **Lifetime** | Indefinite — same model always has the same cameras |
| **First run** | ~8-11 seconds (coarse sweep + binary search refinement) |
| **Subsequent runs** | <1ms (SharedPreferences read) |
| **Minimum lenses** | 2 (single-camera devices aren't cached — nothing to switch) |
| **Auto-invalidation** | Cache version prefix ensures old caches are ignored after algorithm updates |

### Platform Support

| Platform | Support | Notes |
|----------|---------|-------|
| **Android (API 29+)** | Full | Probe + switching + caching |
| **Android (API 24–28)** | Partial | `setZoomRatio()` works for digital zoom. Probe returns empty list (Camera2 physical camera tracking unavailable). |
| **iOS** | No-op | `discoverLenses()` returns `[]`. iOS handles physical lens switching natively via AVFoundation — no plugin needed. |
| **Web / Desktop** | No-op | Returns empty list. |

---

## Tested Devices

| Device | Lenses Found | Switch Points | Probe Time |
|--------|-------------|---------------|------------|
| Pixel 10 Pro | 3 | .5x (ultra-wide), 1x (main), 5x (telephoto) | ~9s first run |

**We'd love to expand this table.** If you test on your device, please [open an issue](https://github.com/joinfiber/lens_switcher/issues/new) with your results! Include:
- Device model
- Number of lenses found
- Switch points (from logcat: filter for `LensSwitcher`)
- Any issues or unexpected behavior

---

## Troubleshooting

### Probe returns empty list

1. **Check API level.** Physical camera tracking requires Android 10+ (API 29). On older devices, `discoverLenses()` returns `[]` by design.
2. **Check bridge setup.** The most common issue is forgetting to call `LensSwitcherBridge.installOnPreview()` *before* binding. Check logcat for `LensSwitcher: Installed physical camera tracker on preview` — if you don't see this, the bridge isn't wired up.
3. **Single camera.** If the device only has one back camera, there's nothing to switch. The probe will find one physical camera ID and return an empty list (2+ lenses required).
4. **Front camera.** Most selfie cameras have a single sensor — `discoverFrontLenses()` returning `[]` is expected and normal. Multi-lens front cameras (some Samsung flagships, foldables) will return lenses.

### Wrong lens labels

The probe labels lenses based on their zoom ratio, not their physical properties. If your device switches telephoto at 2x (common on Samsung), the label will be "2x" rather than "3x" or "5x". This is correct — it's the zoom ratio you'd pass to `setZoomRatio()` to activate that lens.

### Probe runs every launch

If the cache seems to not be working, check that the cache version matches. Upgrading between major versions (0.1.0 → 0.2.0 → 0.3.0) intentionally invalidates the cache when the probe algorithm changes. The re-probe will happen once and then cache permanently.

Check logcat — you should see either `Returning N cached lenses` (cache hit) or `Cached N lenses for <model>` (probe completed and cached).

### Camera preview flickers during probe

This is expected on the first launch — the probe physically changes the zoom ratio to sweep through lenses, which causes brief visual changes. Use the `probeProgress` stream to overlay a calibration screen during the probe so users see a polished loading state instead of camera flicker.

### Default lens wrong on startup

If the camera starts on the wrong physical lens (e.g., ultra-wide instead of main), call `setZoomRatio()` after `discoverLenses()` completes:

```dart
final lenses = await LensSwitcher.discoverLenses();
final mainIndex = lenses.indexWhere((l) => l.zoomRatio == 1.0);
final selected = mainIndex >= 0 ? mainIndex : 0;
await LensSwitcher.setZoomRatio(lenses[selected].zoomRatio);
```

This explicitly syncs the hardware to the main lens.

### Logcat tags

All logs use the tag `LensSwitcher`. Filter with:

```bash
adb logcat -e "LensSwitcher"
```

Key log messages:
- `Installed physical camera tracker on preview` — bridge connected
- `Camera attached` — CameraX session bound
- `Probe: sweeping X.XX–Y.YY` — probe starting (min/max zoom range)
- `Probe: testing N ratios (coarse sweep)` — coarse sweep starting
- `Probe coarse: [...]` — full zoom→physicalCameraId mapping
- `Probe: N transitions to refine (M binary steps)` — binary search starting
- `Probe: refined switch at X.XXx → phys N (label)` — precise switch point found
- `Probe complete: found N lenses` — probe finished
- `Cached N lenses for <model>` — saved to SharedPreferences
- `Returning N cached lenses` — loaded from cache (instant)

---

## FAQ

**Does this work on iOS?**
No — and that's OK. iOS handles physical lens switching natively through AVFoundation's `AVCaptureDevice.DiscoverySession` and virtual device switching. `discoverLenses()` returns an empty list on iOS so you can safely call it cross-platform without conditional logic.

**What about the front camera?**
Call `discoverFrontLenses()` instead. Most selfie cameras have a single sensor, so this returns an empty list on the majority of devices — which is correct. Multi-lens front cameras on flagship devices (some Samsung flagships, foldables) will return their lenses. The `LensPicker` auto-hides with fewer than 2 lenses, so you can wire it up unconditionally.

**What if my device only has one camera?**
`discoverLenses()` returns an empty list. The `LensPicker` widget automatically hides itself when given fewer than 2 lenses. No special handling needed.

**Can I use this without forking my camera plugin?**
Currently the bridge needs to be installed on the Preview builder before CameraX binding, which means modifying the camera plugin's Kotlin code. If your camera plugin exposes the `Preview.Builder` or has plugin hooks, you might not need a full fork. We're exploring ways to make this zero-config in future versions.

**What happens during the ~8-11 second probe?**
The camera preview briefly changes zoom levels as the probe sweeps through ratios, then binary search refines each transition. This only happens once per device model — every subsequent launch loads from cache instantly. Use the `probeProgress` stream to show a calibration screen during this time.

**Can I trigger the probe before the camera screen?**
Not easily — the probe requires an active CameraX session (it reads live capture frames). The bridge must be set up and the camera must be bound. However, once the probe runs once on any screen, the results are cached globally. So if your app has a QR scanner or any other camera feature, the lens data will already be cached when the user opens the main camera.

**How accurate are the switch points?**
Very accurate. The binary search phase refines each transition to 0.01x precision. This means when you tap a lens button, `setZoomRatio()` sets exactly the ratio where the physical camera activates — no digital zoom bleeding through.

**Can I set zoom ratios between switch points?**
Yes! `LensSwitcher.setZoomRatio()` accepts any value in the device's zoom range. Intermediate values use digital zoom on the currently active physical lens. For example, if telephoto activates at 4.98x and you set 10x, CameraX uses the telephoto sensor with additional digital zoom.

---

## Architecture

```
packages/lens_switcher/
├── android/src/main/kotlin/com/fiber/lens_switcher/
│   ├── LensSwitcherPlugin.kt       # Flutter plugin (MethodChannel + EventChannel)
│   ├── LensSwitcherBridge.kt       # Static bridge to host camera's CameraX session
│   ├── LensProbe.kt                # Two-phase probe: coarse sweep + binary search
│   ├── PhysicalCameraTracker.kt    # Camera2 capture callback (reads active phys camera)
│   └── LensCache.kt                # Versioned SharedPreferences per device model
├── lib/
│   ├── lens_switcher.dart           # Barrel export
│   └── src/
│       ├── lens_switcher_channel.dart   # Dart MethodChannel + EventChannel wrapper
│       ├── lens.dart                    # Lens data model
│       └── widgets/
│           └── lens_picker.dart         # Ready-to-use picker widget
├── README.md
├── LICENSE                          # MIT — use it however you want
├── CHANGELOG.md
└── pubspec.yaml
```

The package has **zero dependencies** beyond Flutter and CameraX (which your camera plugin already includes). No Guava, no Room, no Hilt — just CameraX core, Camera2 interop, and Kotlin coroutines.

---

## Contributing

Contributions are welcome! This package was extracted from a production app ([Fiber](https://joinfiber.app)) and has been tested on one device so far. Help us make it work everywhere.

**Especially valuable:**
- **Device test results** — Run the probe on your device and share what lenses it finds ([open an issue](https://github.com/joinfiber/lens_switcher/issues/new))
- **Bug reports** — Incorrect lens detection, crashes, or edge cases
- **Camera plugin integrations** — Guides for other camera plugins beyond CamerAwesome
- **iOS support** — AVFoundation-based physical camera detection
- **Widget styles** — New `LensPickerStyle` presets

**Before submitting a PR:**
1. Test on a physical device (emulators don't have physical camera switching)
2. Include logcat output showing the probe results
3. Ensure `dart analyze` passes

---

## License

MIT License — use it however you want. Commercial use, modification, distribution, private use — all permitted. See [LICENSE](LICENSE) for the full text.

Built with care by the [Fiber](https://joinfiber.app) team.
