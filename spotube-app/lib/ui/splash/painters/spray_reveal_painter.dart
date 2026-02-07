import 'package:flutter/material.dart';

/// CustomPainter for spray paint reveal effect
/// Creates a sweeping gradient wipe across the logo
class SprayRevealPainter extends CustomPainter {
  final double progress; // 0.0 to 1.0
  final Size logoSize;
  final Offset logoPosition;

  SprayRevealPainter({
    required this.progress,
    required this.logoSize,
    required this.logoPosition,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (progress <= 0.0) return;

    // Create gradient: orange → magenta → purple
    const gradient = LinearGradient(
      begin: Alignment.topLeft,
      end: Alignment.bottomRight,
      colors: [
        Color(0xFFFF6B35), // Orange
        Color(0xFFFF4081), // Magenta/Pink
        Color(0xFFC855FF), // Purple
      ],
      stops: [0.0, 0.5, 1.0],
    );

    // Calculate reveal path - sweeping arc from top-left to bottom-right
    final centerX = logoPosition.dx + logoSize.width / 2;
    final centerY = logoPosition.dy + logoSize.height / 2;
    final radius = (logoSize.width + logoSize.height) / 2;

    // Create sweeping path
    final path = Path();

    // Create arc path
    path.moveTo(centerX, centerY);
    path.lineTo(
      centerX + radius * 1.5 * (progress > 0.5 ? 1.0 : progress * 2),
      centerY - radius * 1.5 * (progress > 0.5 ? 1.0 : progress * 2),
    );
    
    // Create sweeping rect mask
    final sweepPath = Path();
    final sweepWidth = logoSize.width * progress * 1.2;
    final sweepHeight = logoSize.height * progress * 1.2;
    
    sweepPath.addRect(Rect.fromLTWH(
      logoPosition.dx - sweepWidth * 0.3,
      logoPosition.dy - sweepHeight * 0.3,
      sweepWidth,
      sweepHeight,
    ));

    // Draw gradient overlay with clipping
    final paint = Paint()
      ..shader = gradient.createShader(
        Rect.fromLTWH(
          logoPosition.dx,
          logoPosition.dy,
          logoSize.width,
          logoSize.height,
        ),
      )
      ..style = PaintingStyle.fill
      ..blendMode = BlendMode.overlay;

    canvas.save();
    canvas.clipPath(sweepPath);
    canvas.drawRect(
      Rect.fromLTWH(
        logoPosition.dx,
        logoPosition.dy,
        logoSize.width,
        logoSize.height,
      ),
      paint,
    );
    canvas.restore();
  }

  @override
  bool shouldRepaint(SprayRevealPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.logoSize != logoSize ||
        oldDelegate.logoPosition != logoPosition;
  }
}

