package com.bombest.music.car.game

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Android Auto **template** entry (parked companion): minimal game + now-playing art.
 *
 * Manual QA (Desktop Head Unit or vehicle):
 * - Confirm two entries: existing **media** app + **parking** template app.
 * - Start playback in the media app, open this screen: large art + title/artist update.
 * - **Beat tap** increments score only when parked (host may hide app while driving).
 * - If connection fails, follow on-screen hint to start music on the phone first.
 */
class BombestCarGameService : CarAppService() {

    override fun createHostValidator(): HostValidator {
        return HostValidator.Builder(this)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
    }

    override fun onCreateSession(): Session = BombestCarGameSession()
}
