package br.com.alertaequipe

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object OperationalNotifications {
    private const val CHANNEL = "operational_v1"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            "Avisos operacionais",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Início de plantões e outros avisos operacionais da equipe"
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun handleShiftBoundary(context: Context, data: Map<String, String>) {
        val boundary = data["boundary"] ?: return
        val teamName = data["teamName"]?.trim().orEmpty().ifBlank { "Equipe" }
        val date = data["date"]?.trim().orEmpty()
        val boundaryKey = data["boundaryKey"] ?: (boundary + ":" + teamName + ":" + date)
        val title = if (boundary == "START") "Início do plantão" else "Término do plantão"
        val text = buildString {
            append(teamName)
            if (date.isNotBlank()) append(" · " + date)
        }
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, boundaryKey.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(boundaryKey.hashCode(), notification)
        } catch (_: SecurityException) { }
    }

    fun handleShiftOpened(context: Context, data: Map<String, String>) {
        val teamName = data["teamName"]?.trim().orEmpty().ifBlank { "Equipe" }
        val openedBy = data["openedBy"]?.trim().orEmpty()
        val shiftId = data["shiftId"] ?: return
        val key = data["boundaryKey"] ?: ("shift_opened:" + shiftId)
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, key.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("operis_module", "schedules")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (openedBy.isBlank())
            teamName + " \u00b7 Toque para assumir o plant\u00e3o."
        else teamName + " \u00b7 Aberto por " + openedBy + ". Toque para assumir o plant\u00e3o."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_radio_custom)
            .setContentTitle("Plant\u00e3o aberto \u2014 assuma o turno")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(context).notify(key.hashCode(), notification) }
        catch (_: SecurityException) { }
    }

}
