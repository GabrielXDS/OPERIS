package br.com.alertaequipe

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

object Alerts {
    const val CHANNEL = "panic_v1"
    const val ID = 100
    fun channel(c: Context) {
        val audio = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        val ch = NotificationChannel(CHANNEL,"Alertas de emergência",NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Sirene e avisos recebidos da sua equipe"
            enableVibration(true)
            vibrationPattern = longArrayOf(0,600,350,600,350)
            setSound(Uri.parse("android.resource://"+c.packageName+"/"+R.raw.siren),audio)
        }
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }
    fun notification(c: Context,a: Panic, fallback: Boolean = false): Notification {
        val requestCode = a.id.hashCode()
        val emergencyIntent = Intent(c,EmergencyAlertActivity::class.java)
            .putExtra("panic",a.json())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val emergency = PendingIntent.getActivity(c,requestCode,emergencyIntent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getBroadcast(c,requestCode,Intent(c,SilenceReceiver::class.java).putExtra("alertId",a.id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(c,CHANNEL)
            .setSmallIcon(R.drawable.ic_alert).setContentTitle("ALERTA DE EMERGÊNCIA")
            .setContentText("Acionado por "+a.name + if (fallback) " · Abra para ativar a sirene" else "")
            .setCategory(NotificationCompat.CATEGORY_ALARM).setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(emergency).setFullScreenIntent(emergency,true)
            .addAction(R.drawable.ic_alert,"SILENCIAR",stop)
            .setOngoing(!fallback).setAutoCancel(false).setOnlyAlertOnce(true).setSilent(!fallback)
            .setTimeoutAfter(120_000).build()
    }
    fun launch(c: Context,a: Panic) {
        Local.prefs.edit().putString("active",a.json()).commit()
        Local.current.value = a
        try {
            ContextCompat.startForegroundService(c,Intent(c,SirenService::class.java).putExtra("panic",a.json()))
        } catch (_: IllegalStateException) { fallback(c,a) }
          catch (_: SecurityException) { fallback(c,a) }
    }
    private fun fallback(c: Context,a: Panic) {
        Local.prefs.edit().putBoolean("audioWarning",true).apply()
        if (NotificationManagerCompat.from(c).areNotificationsEnabled()) {
            try { NotificationManagerCompat.from(c).notify(ID,notification(c,a,true)) } catch (_: SecurityException) {}
        }
    }
    fun silence(c: Context) {
        Local.prefs.edit().remove("active").commit()
        c.stopService(Intent(c,SirenService::class.java))
        Local.current.value = null
        NotificationManagerCompat.from(c).cancel(ID)
    }
}
class SilenceReceiver: BroadcastReceiver() {
    override fun onReceive(c: Context,i: Intent) { Alerts.silence(c) }
}
class PanicMessagingService: FirebaseMessagingService() {
    override fun onNewToken(token: String) { Backend.queueSync(this) }
    override fun onMessageReceived(message: RemoteMessage) {
        if (!Local.registered) return
        val d = message.data
        when {
            d["type"] == "record_created" -> {
                // Criações de Ocorrências/Rondas: contadores e notificação agrupada,
                // sempre acompanhadas do popup interno quando o app estiver em uso.
                Records.ensureChannel(this)
                Records.handle(this, d)
            }
            d["type"] == "record_shared" || d["type"] == "record_updated" -> {
                Records.handleShared(this, d)
            }
            d["type"] == "shift_schedule_boundary" -> {
                OperationalNotifications.handleShiftBoundary(this, d)
            }
            d["type"] == "shift_opened" -> {
                OperationalNotifications.handleShiftOpened(this, d)
            }
            Operational.validAlertPayload(d, Local.deviceId) -> {
                val id = d["alertId"]!!
                val team = d["teamId"]!!
                val sender = d["senderDeviceId"]!!
                val name = d["senderName"]!!.trim()
                val time = d["timestamp"]!!.toLong()
                val panic = Panic(id, team, sender, name, time)
                if (!Local.receive(panic)) return
                Alerts.launch(this, panic)
            }
        }
    }
}
