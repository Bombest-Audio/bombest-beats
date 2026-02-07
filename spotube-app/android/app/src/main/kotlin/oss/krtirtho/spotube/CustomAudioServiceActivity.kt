package oss.krtirtho.spotube

import android.os.Build
import android.os.Bundle
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ryanheise.audioservice.AudioServiceActivity

class CustomAudioServiceActivity : AudioServiceActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen for Android 12+ before calling super
        // The splash will automatically dismiss when Flutter draws its first frame
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val splashScreen = installSplashScreen()
            
            // Add smooth fade-out animation when splash dismisses
            splashScreen.setOnExitAnimationListener { splashScreenView ->
                splashScreenView.view.animate()
                    .alpha(0f)
                    .setDuration(220)
                    .withEndAction {
                        splashScreenView.remove()
                    }
                    .start()
            }
        }
        
        // Call super to initialize Flutter
        super.onCreate(savedInstanceState)
    }
}

