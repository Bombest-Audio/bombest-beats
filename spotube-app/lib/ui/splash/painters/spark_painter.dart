import 'dart:ui';
import 'dart:math' as math;
import 'package:flutter/material.dart';

/// CustomPainter for fuse spark/starburst effect
/// Draws animated spark rays with glow pulse
class SparkPainter extends CustomPainter {
  final double progress; // 0.0 to 1.0
  final Offset sparkPosition;
  final double baseScale;
  final double rotation;

  SparkPainter({
    required this.progress,
    required this.sparkPosition,
    this.baseScale = 1.0,
    this.rotation = 0.0,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (progress <= 0.0) return;

    // Pulse effect: scale 1.0 → 1.15 → 1.0
    final pulseProgress = (math.sin(progress * math.pi * 4) + 1) / 2; // 0 to 1
    final scale = baseScale * (1.0 + pulseProgress * 0.15);
    
    // Opacity fade
    final opacity = (1.0 - progress * 0.3).clamp(0.3, 1.0);

    canvas.save();
    canvas.translate(sparkPosition.dx, sparkPosition.dy);
    canvas.rotate(rotation);
    canvas.scale(scale);

    // Draw starburst rays
    final rayCount = 12;
    final outerRadius = 30.0;
    final innerRadius = 10.0;

    final paint = Paint()
      ..color = const Color(0xFFFF6B35).withOpacity(opacity) // Orange
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.5;

    for (int i = 0; i < rayCount; i++) {
      final angle = (i * 360 / rayCount) * math.pi / 180;
      final startX = math.cos(angle) * innerRadius;
      final startY = math.sin(angle) * innerRadius;
      final endX = math.cos(angle) * outerRadius;
      final endY = math.sin(angle) * outerRadius;

      canvas.drawLine(
        Offset(startX, startY),
        Offset(endX, endY),
        paint,
      );
    }

    // Draw center glow
    final glowPaint = Paint()
      ..color = Color.fromRGBO(255, 107, 53, opacity * 0.8)
      ..style = PaintingStyle.fill;
    
    canvas.drawCircle(const Offset(0, 0), 6.0, glowPaint);

    canvas.restore();
  }

  @override
  bool shouldRepaint(SparkPainter oldDelegate) {
    return oldDelegate.progress != progress ||
        oldDelegate.sparkPosition != sparkPosition ||
        oldDelegate.baseScale != baseScale ||
        oldDelegate.rotation != rotation;
  }
}

