package com.hione.player.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class HioneCarSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return HioneCarPlayerScreen(carContext)
    }
}
