package com.team.vocalink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Boot Receiver: Requests mesh-service recovery after device restart or app update
 * when the user previously enabled the mesh. Android may still deny background starts.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val meshEnabled = context.getSharedPreferences("airhop_settings", Context.MODE_PRIVATE)
            .getBoolean("mesh_enabled", false)
        if ((action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) || !meshEnabled) {
            return
        }

        val serviceIntent = Intent(context, AirHopMeshService::class.java).apply {
            this.action = AirHopMeshService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i(TAG, "Requested AirHop mesh recovery after $action")
        } catch (e: SecurityException) {
            Log.w(TAG, "Platform denied automatic mesh recovery after $action", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "App state denied automatic mesh recovery after $action", e)
        }    }
}
