import 'package:flutter/material.dart';
import 'package:spotube/services/logger/logger.dart';
import 'painters/spray_reveal_painter.dart';
import 'painters/drip_painter.dart';
import 'painters/spark_painter.dart';

/// Premium animated splash screen for Bombest Beats
/// Features: spray paint reveal, fuse spark, and drip settle animations
class BombestSplashScreen extends StatefulWidget {
  final VoidCallback onComplete;

  const BombestSplashScreen({
    super.key,
    required this.onComplete,
  });

  @override
  State<BombestSplashScreen> createState() => _BombestSplashScreenState();
}

class _BombestSplashScreenState extends State<BombestSplashScreen>
    with TickerProviderStateMixin {
  late AnimationController _controller;
  late Animation<double> _logoFadeScale;
  late Animation<double> _sparkProgress;
  late Animation<double> _sprayRevealProgress;
  late Animation<double> _dripProgress;
  late Animation<double> _fadeOut;

  ImageProvider? _logoImage;
  ImageProvider? _grainImage;

  @override
  void initState() {
    super.initState();

    // Total animation duration: 1100ms
    _controller = AnimationController(
      duration: const Duration(milliseconds: 1100),
      vsync: this,
    );

    // Logo fade/scale: 0-160ms (0.0 to 0.145)
    // Scale animation: 0.92 → 1.02 → 1.0
    _logoFadeScale = TweenSequence<double>([
      TweenSequenceItem(
        tween: Tween(begin: 0.92, end: 0.92)
            .chain(CurveTween(curve: Curves.easeOut)),
        weight: 10,
      ),
      TweenSequenceItem(
        tween: Tween(begin: 0.92, end: 1.02)
            .chain(CurveTween(curve: Curves.easeInOut)),
        weight: 50,
      ),
      TweenSequenceItem(
        tween: Tween(begin: 1.02, end: 1.0)
            .chain(CurveTween(curve: Curves.easeIn)),
        weight: 40,
      ),
    ]).animate(
      CurvedAnimation(
        parent: _controller,
        curve: const Interval(0.0, 0.145, curve: Curves.easeOut),
      ),
    );

    // Fuse spark pulse: 140-650ms (0.127 to 0.591)
    _sparkProgress = CurvedAnimation(
      parent: _controller,
      curve: const Interval(0.127, 0.591, curve: Curves.easeInOut),
    );

    // Spray reveal sweep: 250-850ms (0.227 to 0.773)
    _sprayRevealProgress = CurvedAnimation(
      parent: _controller,
      curve: const Interval(0.227, 0.773, curve: Curves.easeInOut),
    );

    // Drip settle: 600-950ms (0.545 to 0.864)
    _dripProgress = CurvedAnimation(
      parent: _controller,
      curve: const Interval(0.545, 0.864, curve: Curves.easeOut),
    );

    // Fade out: 900-1100ms (0.818 to 1.0)
    _fadeOut = CurvedAnimation(
      parent: _controller,
      curve: const Interval(0.818, 1.0, curve: Curves.easeIn),
    );

    // Initialize image providers (images will load automatically when used)
    _logoImage = const AssetImage('assets/images/bomb_logo.png');
    _grainImage = const AssetImage('assets/images/grain.png');
    
    // Note: precaching removed - images will load when rendered in build()

    // Start animation
    _controller.forward().then((_) {
      AppLogger.log.i('[BombestSplashScreen] Animation completed, calling onComplete');
      widget.onComplete();
    });
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return RepaintBoundary(
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, child) {
          return Container(
            color: const Color(0xFF0B0E23), // Deep navy background
            child: Stack(
              children: [
                // Grain overlay (optional, low opacity)
                if (_grainImage != null)
                  Positioned.fill(
                    child: Opacity(
                      opacity: 0.08,
                      child: Image(
                        image: _grainImage!,
                        fit: BoxFit.cover,
                        repeat: ImageRepeat.repeat,
                      ),
                    ),
                  ),

                // Main logo layer
                Center(
                  child: RepaintBoundary(
                    child: AnimatedBuilder(
                      animation: _logoFadeScale,
                      builder: (context, child) {
                        // Fade in during first 30% of logo animation
                        final fadeProgress = (_controller.value / 0.145).clamp(0.0, 1.0);
                        final opacity = (fadeProgress * 3.33).clamp(0.0, 1.0); // Quick fade in
                        
                        return Transform.scale(
                          scale: _logoFadeScale.value,
                          child: Opacity(
                            opacity: opacity,
                            child: Image(
                              image: _logoImage!,
                              width: 280,
                              height: 280,
                              fit: BoxFit.contain,
                            ),
                          ),
                        );
                      },
                    ),
                  ),
                ),

                // Spark layer (fuse spark effect)
                if (_sparkProgress.value > 0)
                  RepaintBoundary(
                    child: CustomPaint(
                      painter: SparkPainter(
                        progress: _sparkProgress.value,
                        sparkPosition: Offset(
                          MediaQuery.of(context).size.width / 2 + 80,
                          MediaQuery.of(context).size.height / 2 - 100,
                        ),
                        rotation: _sparkProgress.value * 0.5,
                      ),
                    ),
                  ),

                // Spray reveal layer
                if (_sprayRevealProgress.value > 0)
                  RepaintBoundary(
                    child: CustomPaint(
                      painter: SprayRevealPainter(
                        progress: _sprayRevealProgress.value,
                        logoSize: const Size(280, 280),
                        logoPosition: Offset(
                          (MediaQuery.of(context).size.width - 280) / 2,
                          (MediaQuery.of(context).size.height - 280) / 2,
                        ),
                      ),
                    ),
                  ),

                // Drip settle layer
                if (_dripProgress.value > 0)
                  RepaintBoundary(
                    child: CustomPaint(
                      painter: DripPainter(
                        progress: _dripProgress.value,
                        logoSize: const Size(280, 280),
                        logoPosition: Offset(
                          (MediaQuery.of(context).size.width - 280) / 2,
                          (MediaQuery.of(context).size.height - 280) / 2,
                        ),
                      ),
                    ),
                  ),

                // Fade out overlay
                if (_fadeOut.value > 0)
                  Container(
                    color: Color.fromRGBO(11, 14, 35, _fadeOut.value),
                  ),
              ],
            ),
          );
        },
      ),
    );
  }
}

