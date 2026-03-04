/// A physical camera lens discovered by the empirical probe.
///
/// Each [Lens] represents a physical camera on the device (ultra-wide, main,
/// telephoto) along with the exact CameraX zoom ratio at which it activates.
///
/// To switch to this lens, pass [zoomRatio] to [LensSwitcher.setZoomRatio].
class Lens {
  /// Human-readable label for the lens.
  ///
  /// Format: ".5x" (ultra-wide), "1x" (main), "5x" (telephoto), etc.
  /// The label is generated from the zoom ratio — sub-1x ratios drop
  /// the leading zero for a compact display.
  final String label;

  /// The CameraX zoom ratio at which this physical camera activates.
  ///
  /// Pass this value to [LensSwitcher.setZoomRatio] to switch to this lens.
  /// Common values: 0.5 (ultra-wide), 1.0 (main), 2.0-5.0 (telephoto).
  final double zoomRatio;

  /// The Camera2 physical camera ID.
  ///
  /// This is the internal identifier CameraX reports for the physical camera
  /// behind this lens. Useful for debugging but not needed for lens switching.
  final String physicalId;

  /// Creates a [Lens] with the given [label], [zoomRatio], and [physicalId].
  const Lens({
    required this.label,
    required this.zoomRatio,
    required this.physicalId,
  });

  @override
  String toString() => 'Lens($label, zoom: $zoomRatio, phys: $physicalId)';

  @override
  bool operator ==(Object other) =>
      identical(this, other) ||
      other is Lens &&
          runtimeType == other.runtimeType &&
          zoomRatio == other.zoomRatio &&
          physicalId == other.physicalId;

  @override
  int get hashCode => zoomRatio.hashCode ^ physicalId.hashCode;
}
