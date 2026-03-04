# lens_switcher Example

## Minimal Integration

### 1. Android Bridge Setup (Kotlin)

Add two lines to your CameraX camera setup:

```kotlin
import com.fiber.lens_switcher.LensSwitcherBridge

val previewBuilder = Preview.Builder()
LensSwitcherBridge.installOnPreview(previewBuilder)  // before binding

val camera = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
LensSwitcherBridge.attachCamera(camera)               // after binding
```

### 2. Flutter Usage (Dart)

```dart
import 'package:flutter/material.dart';
import 'package:lens_switcher/lens_switcher.dart';

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  List<Lens> _lenses = [];
  int _activeLensIndex = 0;
  bool _probeStarted = false;

  /// Call this once when the camera is ready.
  Future<void> discoverLenses() async {
    if (_probeStarted) return;
    _probeStarted = true;

    final lenses = await LensSwitcher.discoverLenses();
    if (!mounted || lenses.isEmpty) return;

    final mainIndex = lenses.indexWhere((l) => l.zoomRatio == 1.0);
    setState(() {
      _lenses = lenses;
      _activeLensIndex = mainIndex >= 0 ? mainIndex : 0;
    });
  }

  Future<void> switchToLens(int index) async {
    if (index == _activeLensIndex) return;
    setState(() => _activeLensIndex = index);
    await LensSwitcher.setZoomRatio(_lenses[index].zoomRatio);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Your camera preview widget here
          const Placeholder(),

          // Lens picker — auto-hides when fewer than 2 lenses
          Positioned(
            bottom: 16,
            right: 16,
            child: LensPicker(
              lenses: _lenses,
              activeLensIndex: _activeLensIndex,
              onLensSelected: switchToLens,
            ),
          ),
        ],
      ),
    );
  }
}
```

### 3. Custom Styling

```dart
// Apple Camera-inspired style
LensPicker(
  style: LensPickerStyle.ios,
  lenses: _lenses,
  activeLensIndex: _activeLensIndex,
  onLensSelected: switchToLens,
)

// Fully custom
LensPicker(
  style: LensPickerStyle(
    activeTextColor: Colors.amber,
    inactiveTextColor: Colors.white70,
    activePillColor: Colors.amber.withOpacity(0.2),
    backgroundColor: Colors.black54,
    pillSize: 36,
    fontSize: 13,
  ),
  lenses: _lenses,
  activeLensIndex: _activeLensIndex,
  onLensSelected: switchToLens,
)
```

See the [README](https://github.com/nicholasgasior/lens_switcher) for full integration guides with CamerAwesome, the Flutter camera package, and custom CameraX setups.
