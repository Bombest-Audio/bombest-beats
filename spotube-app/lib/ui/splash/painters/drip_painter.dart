import 'package:flutter/material.dart';

/// CustomPainter for drip settle effect
/// Draws 2-4 small drip shapes that settle downward
class DripPainter extends CustomPainter {
  final double progress; // 0.0 to 1.0
  final Size logoSize;
  final Offset logoPosition;

  DripPainter({
    required this.progress,
    required this.logoSize,
    required this.logoPosition,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (progress <= 0.0) return;

    // Create 3 drip shapes positioned around bottom of logo
    final drips = [
      _DripInfo(
        startX: logoPosition.dx + logoSize.width * 0.3,
        startY: logoPosition.dy + logoSize.height * 0.85,
        width: 8.0,
        height: 12.0,
        delay: 0.0,
      ),
      _DripInfo(
        startX: logoPosition.dx + logoSize.width * 0.6,
        startY: logoPosition.dy + logoSize.height * 0.9,
        width: 6.0,
        height: 10.0,
        delay: 0.2,
      ),
      _DripInfo(
        startX: logoPosition.dx + logoSize.width * 0.75,
        startY: logoPosition.dy + logoSize.height * 0.88,
        width: 7.0,
        height: 11.0,
        delay: 0.4,
      ),
    ];

    final paint = Paint()
      ..color = Color.fromRGBO(200, 85, 255, 0.7) // Purple drip
      ..style = PaintingStyle.fill;

    for (final drip in drips) {
      final dripProgress = ((progress - drip.delay).clamp(0.0, 1.0) / (1.0 - drip.delay)).clamp(0.0, 1.0);
      if (dripProgress <= 0.0) continue;

      // Ease out curve for settling motion
      final easedProgress = Curves.easeOut.transform(dripProgress);
      final translateY = easedProgress * 4.0; // Max 4px downward

      final dripPath = Path();
      final x = drip.startX;
      final y = drip.startY + translateY;

      // Draw teardrop shape
      dripPath.moveTo(x, y);
      dripPath.quadraticBezierTo(
        x - drip.width / 2,
        y + drip.height * 0.3,
        x,
        y + drip.height,
      );
      dripPath.quadraticBezierTo(
        x + drip.width / 2,
        y + drip.height * 0.3,
        x,
        y,
      );
      dripPath.close();

      canvas.drawPath(dripPath, paint);
    }
  }

  @override
  bool shouldRepaint(DripPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.logoSize != logoSize ||
        oldDelegate.logoPosition != logoPosition;
  }
}

class _DripInfo {
  final double startX;
  final double startY;
  final double width;
  final double height;
  final double delay;

  _DripInfo({
    required this.startX,
    required this.startY,
    required this.width,
    required this.height,
    required this.delay,
  });
}

