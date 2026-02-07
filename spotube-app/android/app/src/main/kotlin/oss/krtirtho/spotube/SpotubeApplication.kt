package oss.krtirtho.spotube

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log

class SpotubeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            
            Log.i("SpotubeIcon", "========== ICON DEBUG INFO ==========")
            Log.i("SpotubeIcon", "Package name: $packageName")
            Log.i("SpotubeIcon", "Icon resource ID: ${appInfo.icon}")
            Log.i("SpotubeIcon", "Icon resource name: ${resources.getResourceName(appInfo.icon)}")
            
            // Try to get the actual drawable
            val drawable = pm.getApplicationIcon(packageName)
            Log.i("SpotubeIcon", "Drawable class: ${drawable.javaClass.simpleName}")
            Log.i("SpotubeIcon", "Drawable dimensions: ${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
            
            // Check if it's an adaptive icon
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                if (drawable is android.graphics.drawable.AdaptiveIconDrawable) {
                    Log.i("SpotubeIcon", "Using AdaptiveIconDrawable")
                    Log.i("SpotubeIcon", "Background: ${drawable.background?.javaClass?.simpleName}")
                    Log.i("SpotubeIcon", "Foreground: ${drawable.foreground?.javaClass?.simpleName}")
                    
                    // Check foreground bitmap details
                    val foreground = drawable.foreground
                    if (foreground is android.graphics.drawable.BitmapDrawable) {
                        val bitmap = foreground.bitmap
                        Log.i("SpotubeIcon", "Foreground bitmap size: ${bitmap.width}x${bitmap.height}")
                        Log.i("SpotubeIcon", "Foreground bitmap config: ${bitmap.config}")
                        Log.i("SpotubeIcon", "Foreground bitmap hasAlpha: ${bitmap.hasAlpha()}")
                        
                        // Sample many pixels to see the color distribution
                        val centerPixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                        val topLeftPixel = bitmap.getPixel(10, 10)
                        Log.i("SpotubeIcon", "Center pixel color: #${Integer.toHexString(centerPixel)}")
                        Log.i("SpotubeIcon", "Top-left pixel color: #${Integer.toHexString(topLeftPixel)}")
                        
                        // Sample more points
                        val points = listOf(
                            Pair(50, 50), Pair(100, 100), Pair(150, 150),
                            Pair(bitmap.width / 3, bitmap.height / 3),
                            Pair(bitmap.width * 2 / 3, bitmap.height * 2 / 3)
                        )
                        points.forEach { (x, y) ->
                            if (x < bitmap.width && y < bitmap.height) {
                                val pixel = bitmap.getPixel(x, y)
                                Log.i("SpotubeIcon", "Pixel at ($x,$y): #${Integer.toHexString(pixel)}")
                            }
                        }
                        
                        // Try to save bitmap to external storage for inspection
                        try {
                            val file = java.io.File(getExternalFilesDir(null), "icon_debug.png")
                            val out = java.io.FileOutputStream(file)
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                            out.flush()
                            out.close()
                            Log.i("SpotubeIcon", "Saved bitmap to: ${file.absolutePath}")
                        } catch (e: Exception) {
                            Log.e("SpotubeIcon", "Failed to save bitmap: ${e.message}")
                        }
                    }
                }
            }
            
            // List all mipmap resources
            Log.i("SpotubeIcon", "========== MIPMAP RESOURCES ==========")
            val mipmapIds = arrayOf(
                "ic_launcher",
                "ic_launcher_round"
            )
            
            for (name in mipmapIds) {
                try {
                    val resId = resources.getIdentifier(name, "mipmap", packageName)
                    if (resId != 0) {
                        val resName = resources.getResourceName(resId)
                        val drawable = resources.getDrawable(resId, null)
                        Log.i("SpotubeIcon", "$name -> ID:$resId Name:$resName Type:${drawable.javaClass.simpleName} Size:${drawable.intrinsicWidth}x${drawable.intrinsicHeight}")
                    } else {
                        Log.w("SpotubeIcon", "$name -> NOT FOUND")
                    }
                } catch (e: Exception) {
                    Log.e("SpotubeIcon", "$name -> ERROR: ${e.message}")
                }
            }
            
            Log.i("SpotubeIcon", "====================================")
            
        } catch (e: Exception) {
            Log.e("SpotubeIcon", "Error debugging icon: ${e.message}", e)
        }
    }
}


