package com.team.vocalink.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Boot Receiver: Automatically relaunches the AirHop disaster mesh service
 * upon device restart in an active crisis area without requiring user intervention.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if ((action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) &&\n            context.getSharedPreferences("airhop_settings", Context.MODE_PRIVATE)\n                .getBoolean("mesh_enabled", false)) {
            Log.i(TAG, "Boot or package update detected ($action). Resuming disaster mesh service.")

            val serviceIntent = Intent(context, AirHopMeshService::class.java).apply {
                this.action = AirHopMeshService.ACTION_START
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
