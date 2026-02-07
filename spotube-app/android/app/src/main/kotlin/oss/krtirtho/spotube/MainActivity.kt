package oss.krtirtho.spotube

import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import io.flutter.embedding.android.FlutterActivity

class MainActivity: FlutterActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install and configure splash screen for Android 12+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val splashScreen = installSplashScreen()
            
            // Keep splash screen visible until Flutter is ready
            splashScreen.setKeepOnScreenCondition { false }
            
            // Add exit animation
            splashScreen.setOnExitAnimationListener { splashScreenView ->
                splashScreenView.view.animate()
                    .alpha(0f)
                    .scaleX(0.98f)
                    .scaleY(0.98f)
                    .setDuration(220)
                    .withEndAction {
                        splashScreenView.remove()
                    }
                    .start()
            }
        }
        
        super.onCreate(savedInstanceState)
        Log.i("SpotubeIcon", "MainActivity onCreate - checking icon resources")
        
        try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            Log.i("SpotubeIcon", "Activity sees icon resource ID: ${appInfo.icon}")
            Log.i("SpotubeIcon", "Activity sees icon name: ${resources.getResourceName(appInfo.icon)}")
        } catch (e: Exception) {
            Log.e("SpotubeIcon", "Error in MainActivity icon check: ${e.message}")
        }
    }
}
