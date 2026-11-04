package com.farewellutopia.healthdevices

import android.app.Application
import android.content.Context
import android.util.Log
import com.farewellutopia.healthdevices.service.Notifications
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Process start: the notification channels exist before anything posts to them. */
class HealthDevicesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = this
        Notifications.createChannels(this)
    }

    companion object {
        private const val TAG = "HealthDevicesApp"

        @Volatile
        private var appContext: Context? = null

        /**
         * For the broadcast receivers, which have no lifecycle of their own
         * but need a suspend call or two (reading the store) before they
         * finish; `goAsync()` keeps them alive meanwhile. An exception in one
         * of them is logged and shown as a problem instead of killing the
         * process, which would happen in silence with no handler.
         */
        val receiverScope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "receiver work failed", e)
                appContext?.let { Notifications.problem(it, R.string.problem_unexpected) }
            },
        )
    }
}
