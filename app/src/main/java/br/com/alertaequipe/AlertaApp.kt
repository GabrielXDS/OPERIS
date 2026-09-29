package br.com.alertaequipe

import android.app.Application
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import androidx.work.*
import java.util.concurrent.TimeUnit

class AlertaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
        // Pilot deliberately has no App Check provider: no debug token or attestation setup.
        // The server deployment policy, never this client value, controls enforcement.
        val factory = when (BuildConfig.APP_CHECK_MODE) {
            "debug" -> Class.forName("com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory")
                .getMethod("getInstance").invoke(null) as AppCheckProviderFactory
            "pilot" -> null
            else -> PlayIntegrityAppCheckProviderFactory.getInstance()
        }
        factory?.let { FirebaseAppCheck.getInstance().installAppCheckProviderFactory(it) }
        Local.init(this)
        Alerts.channel(this)
        Records.ensureChannel(this)
        val work = PeriodicWorkRequestBuilder<TokenWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("token-heartbeat", ExistingPeriodicWorkPolicy.KEEP, work)
    }
}
