@file:OptIn(ExperimentalAnimationApi::class, ExperimentalMaterialApi::class)

package com.bombest.musify.ui.activities

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.Surface
import androidx.compose.ui.Modifier
import com.bombest.musify.ui.components.SplashScreen
import com.bombest.musify.ui.theme.BombestBeatsTheme

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BombestBeatsTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val activity = this@SplashActivity
                    SplashScreen(
                        onAnimationComplete = {
                            val intent = Intent().apply {
                                setClassName(activity, "com.bombest.musify.ui.activities.MainActivity")
                            }
                            activity.startActivity(intent)
                            activity.finish()
                        }
                    )
                }
            }
        }
    }
}
