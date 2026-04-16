package com.bombest.music.car.game

import android.content.Intent
import androidx.car.app.Session

class BombestCarGameSession : Session() {
    override fun onCreateScreen(intent: Intent) = ParkedVisualizerScreen(carContext)
}
