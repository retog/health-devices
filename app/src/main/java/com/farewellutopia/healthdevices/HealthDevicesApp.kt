package com.farewellutopia.healthdevices

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.farewellutopia.healthdevices.service.Notifications

/** Process start: the notification channels exist before anything posts to them. */
class HealthDevicesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    companion object {
        /**
         * For the broadcast receivers, which have no lifecycle of their own
         * but need a suspend call or two (reading the store) before they
         * finish; `goAsync()` keeps them alive meanwhile.
         */
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
