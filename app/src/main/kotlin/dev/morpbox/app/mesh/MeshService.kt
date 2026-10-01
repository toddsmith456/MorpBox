// SPDX-License-Identifier: MIT
package dev.morpbox.app.mesh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dev.morpbox.app.MainActivity
import dev.morpbox.app.MorpApplication
import dev.morpbox.app.R

/** Foreground mesh service — only while the user leaves Mesh on. */
class MeshService : Service() {

    companion object {
        const val CH = "morp_mesh"
        const val NOTIF_ID = 41
        const val EXTRA_ON = "mesh_on"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "MorpBox mesh", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(this, CH)
            .setContentTitle(getString(R.string.mesh_notif_title))
            .setContentText(getString(R.string.mesh_notif_text))
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
        (application as? MorpApplication)?.graph?.session?.onMeshServiceStarted()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        (application as? MorpApplication)?.graph?.session?.onMeshServiceStopped()
        super.onDestroy()
    }
}
