import 'package:auto_route/auto_route.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_native_splash/flutter_native_splash.dart';
import 'package:spotube/services/logger/logger.dart';
import 'package:spotube/ui/splash/bombest_splash_screen.dart';
import 'package:spotube/collections/routes.gr.dart';

@RoutePage()
class BombestSplashRoutePage extends StatelessWidget {
  const BombestSplashRoutePage({super.key});

  @override
  Widget build(BuildContext context) {
    AppLogger.log.i('[BombestSplashRoute] Building splash route page');
    
    // Dev mode: skip or shorten splash
    if (kDebugMode && !kProfileMode) {
      // Option 1: Skip entirely (uncomment to enable)
      // WidgetsBinding.instance.addPostFrameCallback((_) {
      //   FlutterNativeSplash.remove();
      //   context.router.replace(const RootAppRoute());
      // });
      // return const SizedBox.shrink();
      
      // Option 2: Shorten to 150ms (modify splash screen widget duration)
      // For now, keep full animation even in debug
    }

    return BombestSplashScreen(
      onComplete: () {
        AppLogger.log.i('[BombestSplashRoute] Animation complete, navigating to main app');
        // Remove native splash overlay
        FlutterNativeSplash.remove();
        // Navigate to main app (RootAppRoute will show LibraryRoute as initial child)
        context.router.replace(const RootAppRoute());
      },
    );
  }
}

