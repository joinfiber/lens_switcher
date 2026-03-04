/// Physical camera lens switching for Flutter on Android.
///
/// Automatically discovers ultra-wide, main, and telephoto lenses and their
/// exact zoom switching points via CameraX. No hardcoded values — works on
/// any Android device with multiple cameras.
///
/// ## Quick start
///
/// **1.** Set up the bridge (2 lines of Kotlin in your CameraX setup):
///
/// ```kotlin
/// LensSwitcherBridge.installOnPreview(previewBuilder)  // before binding
/// LensSwitcherBridge.attachCamera(camera)               // after binding
/// ```
///
/// **2.** Use in Dart:
///
/// ```dart
/// import 'package:lens_switcher/lens_switcher.dart';
///
/// // Discover lenses (instant after first run, ~5s probe on first launch)
/// final lenses = await LensSwitcher.discoverLenses();
///
/// // Switch to telephoto
/// await LensSwitcher.setZoomRatio(lenses.last.zoomRatio);
///
/// // Or use the ready-made widget
/// LensPicker(
///   lenses: lenses,
///   activeLensIndex: currentIndex,
///   onLensSelected: (i) => LensSwitcher.setZoomRatio(lenses[i].zoomRatio),
/// )
/// ```
///
/// ## Main classes
///
/// - [LensSwitcher] — discover lenses, set zoom ratio, clear cache
/// - [Lens] — data model for a physical camera lens
/// - [LensPicker] — ready-to-use horizontal pill picker widget
/// - [LensPickerStyle] — visual style configuration with built-in presets
///
/// See the [README](https://github.com/joinfiber/lens_switcher) for
/// full integration guides (CamerAwesome, Flutter camera, custom CameraX).
library;

export 'src/lens.dart';
export 'src/lens_switcher_channel.dart';
export 'src/widgets/lens_picker.dart';
