package br.com.alertaequipe

import android.app.Service
import android.content.Intent
import android.media.*
import android.os.*
import android.util.Log
import androidx.core.app.NotificationManagerCompat

class SirenService : Service() {

    companion object {
        private const val TAG = "AlertaEquipeSiren"
        private const val SIREN_TIMEOUT = 120_000L
    }

    private var player: MediaPlayer? = null
    private lateinit var audio: AudioManager
    private lateinit var vibrator: Vibrator
    private var focus: AudioFocusRequest? = null

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopSelf() }

    private var wake: PowerManager.WakeLock? = null

    // Volume do alarme antes do alerta.
    // Será restaurado quando a emergência terminar.
    private var previousAlarmVolume: Int? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()

        audio = getSystemService(AudioManager::class.java)

        vibrator =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                (getSystemService(VIBRATOR_SERVICE) as Vibrator)
            }

        Log.i(TAG, "SirenService criado")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        val alert = Panic.decode(intent?.getStringExtra("panic"))
            ?: return START_NOT_STICKY.also {
                Log.e(TAG, "Alerta inválido recebido")
                stopSelf()
            }

        Log.i(TAG, "Iniciando alerta ${alert.id}")

        Local.current.value = alert

        Local.prefs.edit()
            .putString("active", alert.json())
            .apply()

        startForeground(
            Alerts.ID,
            Alerts.notification(this, alert)
        )

        // Reinicia o temporizador de segurança.
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, SIREN_TIMEOUT)

        acquireWakeLock()

        if (player == null) {
            startSound()
        }

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {

        try {
            if (wake == null) {
                wake = getSystemService(PowerManager::class.java)
                    .newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "AlertaEquipe:siren"
                    )
            }

            if (wake?.isHeld == true) {
                wake?.release()
            }

            wake?.acquire(SIREN_TIMEOUT + 5_000L)

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao adquirir WakeLock", e)
        }
    }

    private fun maximizeAlarmVolume() {

        try {
            val current =
                audio.getStreamVolume(AudioManager.STREAM_ALARM)

            val maximum =
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

            // Guarda apenas uma vez.
            if (previousAlarmVolume == null) {
                previousAlarmVolume = current
            }

            Log.i(
                TAG,
                "Volume de alarme: atual=$current máximo=$maximum"
            )

            if (current < maximum) {
                audio.setStreamVolume(
                    AudioManager.STREAM_ALARM,
                    maximum,
                    0
                )

                Log.i(TAG, "Volume de alarme elevado para $maximum")
            }

        } catch (e: Exception) {

            Log.e(TAG, "Não foi possível aumentar o volume de alarme", e)

            Local.prefs.edit()
                .putBoolean("audioWarning", true)
                .apply()
        }
    }

    private fun restoreAlarmVolume() {

        val previous = previousAlarmVolume ?: return

        try {
            val maximum =
                audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)

            val safeVolume =
                previous.coerceIn(0, maximum)

            audio.setStreamVolume(
                AudioManager.STREAM_ALARM,
                safeVolume,
                0
            )

            Log.i(
                TAG,
                "Volume de alarme restaurado para $safeVolume"
            )

        } catch (e: Exception) {
            Log.e(TAG, "Falha ao restaurar volume de alarme", e)
        } finally {
            previousAlarmVolume = null
        }
    }

    private fun vibrate() {

        try {
            if (!vibrator.hasVibrator()) return

            val pattern =
                longArrayOf(
                    0,
                    600,
                    350,
                    600,
                    350
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        pattern,
                        0
                    )
                )

            } else {

                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    pattern,
                    0
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Falha na vibração", e)
        }
    }

    private fun startSound() {

        Log.i(TAG, "Preparando sirene")

        /*
         * Primeiro aumenta somente o STREAM_ALARM.
         * Música e chamadas não são alteradas.
         */
        maximizeAlarmVolume()

        focus?.let {
            try {
                audio.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao abandonar Audio Focus anterior", e)
            }
        }

        val attrs =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(
                    AudioAttributes.CONTENT_TYPE_SONIFICATION
                )
                .build()

        focus =
            AudioFocusRequest.Builder(
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener { change ->

                    when (change) {

                        AudioManager.AUDIOFOCUS_GAIN -> {
                            Log.i(TAG, "Audio Focus obtido")

                            try {
                                if (player?.isPlaying == false) {
                                    player?.start()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Falha ao retomar sirene", e)
                            }

                            vibrate()
                        }

                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {

                            Log.w(
                                TAG,
                                "Perda temporária de Audio Focus: $change"
                            )

                            Local.prefs.edit()
                                .putBoolean("audioWarning", true)
                                .apply()
                        }

                        AudioManager.AUDIOFOCUS_LOSS -> {

                            Log.w(TAG, "Audio Focus perdido")

                            Local.prefs.edit()
                                .putBoolean("audioWarning", true)
                                .apply()
                        }
                    }
                }
                .build()

        try {
            val focusResult =
                audio.requestAudioFocus(focus!!)

            if (
                focusResult !=
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            ) {

                /*
                 * Não encerramos o alerta.
                 * Ainda tentamos reproduzir a sirene.
                 */
                Log.w(
                    TAG,
                    "Audio Focus não concedido; tentando sirene mesmo assim"
                )

                Local.prefs.edit()
                    .putBoolean("audioWarning", true)
                    .apply()
            }

        } catch (e: Exception) {

            Log.e(TAG, "Erro ao solicitar Audio Focus", e)

            Local.prefs.edit()
                .putBoolean("audioWarning", true)
                .apply()
        }

        try {

            player?.release()

            player =
                MediaPlayer().apply {

                    setAudioAttributes(attrs)

                    resources
                        .openRawResourceFd(R.raw.siren)
                        .use {

                            setDataSource(
                                it.fileDescriptor,
                                it.startOffset,
                                it.length
                            )
                        }

                    isLooping = true

                    prepare()
                    start()
                }

            Log.i(TAG, "Sirene iniciada")

            Local.prefs.edit()
                .putBoolean("audioWarning", false)
                .apply()

        } catch (e: Exception) {

            Log.e(TAG, "Falha ao iniciar MediaPlayer", e)

            player?.release()
            player = null

            Local.prefs.edit()
                .putBoolean("audioWarning", true)
                .apply()
        }

        // Vibração continua mesmo se o áudio tiver algum problema.
        vibrate()
    }

    override fun onDestroy() {

        Log.i(TAG, "Encerrando sirene")

        handler.removeCallbacksAndMessages(null)

        try {
            player?.stop()
        } catch (_: Exception) {
        }

        player?.release()
        player = null

        try {
            vibrator.cancel()
        } catch (_: Exception) {
        }

        focus?.let {
            try {
                audio.abandonAudioFocusRequest(it)
            } catch (e: Exception) {
                Log.w(TAG, "Falha ao liberar Audio Focus", e)
            }
        }

        focus = null

        /*
         * Devolve ao usuário o volume que existia
         * antes do alerta.
         */
        restoreAlarmVolume()

        if (wake?.isHeld == true) {
            try {
                wake?.release()
            } catch (_: Exception) {
            }
        }

        wake = null

        Local.current.value = null

        Local.prefs.edit()
            .remove("active")
            .apply()

        stopForeground(STOP_FOREGROUND_REMOVE)

        NotificationManagerCompat
            .from(this)
            .cancel(Alerts.ID)

        super.onDestroy()
    }
}