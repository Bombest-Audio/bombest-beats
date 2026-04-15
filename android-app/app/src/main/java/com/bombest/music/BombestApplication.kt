package com.bombest.music

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import com.bombest.music.car.CarUiPolish
import com.bombest.music.data.NetworkModule
import com.bombest.music.work.CarLibrarySyncWorker

/**
 * Observes [CarConnection] for the whole process so Android Auto events are handled even when
 * [MainActivity] is not visible (e.g. phone locked while driving). Uses [observeForever] because
 * [ProcessLifecycleOwner] would pause updates while the app is backgrounded.
 */
class BombestApplication : Application() {

    private val mainHandler = Handler(Looper.getMainLooper())

    private val carConnectionObserver = Observer<Int> { type ->
        if (!CarUiPolish.isCarUiActive(type)) return@Observer
        val app = this@BombestApplication
        val prefs = app.getSharedPreferences(PREFS_DOWNLOAD, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_CAR_WIFI_PREPARE, false)) {
            if (CarUiPolish.isUnmeteredNetwork(app)) {
                CarLibrarySyncWorker.enqueueOneTime(app)
            }
        }
        if (!prefs.getBoolean(KEY_CAR_OFFLINE_HINT_SHOWN, false)) {
            prefs.edit().putBoolean(KEY_CAR_OFFLINE_HINT_SHOWN, true).apply()
            mainHandler.post {
                Toast.makeText(
                    app,
                    "Downloaded tracks play without cell service.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Register the JWT Authenticator before any Retrofit calls fire so a 401 on the
        // first authenticated request can refresh transparently (vs. bouncing the user to login).
        NetworkModule.attachJwtAuthenticator(this)
        CarConnection(this).getType().observeForever(carConnectionObserver)
    }

    companion object {
        private const val PREFS_DOWNLOAD = "download_prefs"
        private const val KEY_CAR_WIFI_PREPARE = "car_wifi_prepare_enabled"
        private const val KEY_CAR_OFFLINE_HINT_SHOWN = "car_offline_hint_shown"
    }
}
