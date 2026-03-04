import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../lens.dart';

/// A horizontal pill picker for switching between physical camera lenses.
///
/// Displays each lens as a circular pill showing its zoom label (.5x, 1x, 5x).
/// Tapping a pill calls [onLensSelected] with the lens index.
///
/// Comes with two built-in styles:
/// - [LensPickerStyle.glass] — translucent dark, ideal over camera previews
/// - [LensPickerStyle.ios] — Apple Camera-inspired with yellow active indicator
///
/// ```dart
/// LensPicker(
///   lenses: lenses,
///   activeLensIndex: _currentIndex,
///   onLensSelected: (index) {
///     setState(() => _currentIndex = index);
///     LensSwitcher.setZoomRatio(lenses[index].zoomRatio);
///   },
/// )
/// ```
class LensPicker extends StatelessWidget {
  /// The available lenses, as returned by [LensSwitcher.discoverLenses].
  final List<Lens> lenses;

  /// Index of the currently active lens.
  final int activeLensIndex;

  /// Called when the user taps a lens pill.
  final ValueChanged<int> onLensSelected;

  /// Visual style for the picker. Defaults to [LensPickerStyle.glass].
  final LensPickerStyle style;

  /// Whether to trigger haptic feedback on lens selection. Defaults to true.
  final bool enableHaptics;

  /// Creates a [LensPicker] with the given [lenses] and selection callback.
  const LensPicker({
    super.key,
    required this.lenses,
    required this.activeLensIndex,
    required this.onLensSelected,
    this.style = LensPickerStyle.glass,
    this.enableHaptics = true,
  });

  @override
  Widget build(BuildContext context) {
    if (lenses.length < 2) return const SizedBox.shrink();

    return Container(
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(style.pillSize / 2 + 4),
        color: style.backgroundColor,
      ),
      padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: lenses.asMap().entries.map((entry) {
          final isActive = activeLensIndex == entry.key;
          return Padding(
            padding: const EdgeInsets.symmetric(horizontal: 1),
            child: _LensPill(
              label: entry.value.label,
              isActive: isActive,
              style: style,
              onTap: () {
                if (enableHaptics) {
                  HapticFeedback.selectionClick();
                }
                onLensSelected(entry.key);
              },
            ),
          );
        }).toList(),
      ),
    );
  }
}

/// Visual style configuration for [LensPicker].
///
/// Use the built-in presets or create a custom style:
///
/// ```dart
/// LensPicker(
///   style: LensPickerStyle(
///     activeTextColor: Colors.amber,
///     inactiveTextColor: Colors.white70,
///     activePillColor: Colors.amber.withOpacity(0.2),
///     backgroundColor: Colors.black54,
///   ),
/// )
/// ```
class LensPickerStyle {
  /// Text color for the active (selected) lens pill.
  final Color activeTextColor;

  /// Text color for inactive lens pills.
  final Color inactiveTextColor;

  /// Background color for the active pill circle.
  final Color activePillColor;

  /// Background color for the entire picker container.
  final Color backgroundColor;

  /// Diameter of each pill circle. Default: 32.
  final double pillSize;

  /// Font size for the zoom label text. Default: 11.
  final double fontSize;

  /// Font weight for the active pill label.
  final FontWeight activeFontWeight;

  /// Font weight for inactive pill labels.
  final FontWeight inactiveFontWeight;

  /// Creates a custom [LensPickerStyle] with the given colors and dimensions.
  const LensPickerStyle({
    this.activeTextColor = const Color(0xFFF5F0E8),
    this.inactiveTextColor = const Color(0x99F5F0E8),
    this.activePillColor = const Color(0x40F5F0E8),
    this.backgroundColor = const Color(0x80000000),
    this.pillSize = 32,
    this.fontSize = 11,
    this.activeFontWeight = FontWeight.w600,
    this.inactiveFontWeight = FontWeight.w400,
  });

  /// Translucent dark glass style — ideal for overlaying camera previews.
  ///
  /// Warm cream text on a semi-transparent dark background.
  /// This is the default style.
  static const glass = LensPickerStyle(
    activeTextColor: Color(0xFFF5F0E8),
    inactiveTextColor: Color(0x99F5F0E8),
    activePillColor: Color(0x40F5F0E8),
    backgroundColor: Color(0x80000000),
    pillSize: 32,
    fontSize: 11,
    activeFontWeight: FontWeight.w600,
    inactiveFontWeight: FontWeight.w400,
  );

  /// Apple Camera-inspired style with yellow active indicator.
  ///
  /// Matches the iOS Camera app's lens selector aesthetic.
  static const ios = LensPickerStyle(
    activeTextColor: Color(0xFFFFD60A),
    inactiveTextColor: Color(0xCCFFFFFF),
    activePillColor: Color(0x40333333),
    backgroundColor: Color(0x66000000),
    pillSize: 36,
    fontSize: 12,
    activeFontWeight: FontWeight.w700,
    inactiveFontWeight: FontWeight.w500,
  );
}

class _LensPill extends StatelessWidget {
  final String label;
  final bool isActive;
  final LensPickerStyle style;
  final VoidCallback onTap;

  const _LensPill({
    required this.label,
    required this.isActive,
    required this.style,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 150),
        width: style.pillSize,
        height: style.pillSize,
        decoration: BoxDecoration(
          shape: BoxShape.circle,
          color: isActive ? style.activePillColor : Colors.transparent,
        ),
        child: Center(
          child: Text(
            label,
            style: TextStyle(
              color: isActive ? style.activeTextColor : style.inactiveTextColor,
              fontSize: style.fontSize,
              fontWeight: isActive ? style.activeFontWeight : style.inactiveFontWeight,
            ),
          ),
        ),
      ),
    );
  }
}
