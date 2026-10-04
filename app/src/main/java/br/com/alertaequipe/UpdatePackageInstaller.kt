package br.com.alertaequipe

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import java.io.File

internal object UpdatePackageInstaller {
    fun install(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        var pendingSessionId: Int? = null
        return runCatching {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(file.length())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = packageInstaller.createSession(params)
            pendingSessionId = sessionId
            packageInstaller.openSession(sessionId).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("base.apk", 0, file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, UpdateInstallReceiver::class.java).apply {
                    action = UpdateInstallReceiver.ACTION
                    putExtra(UpdateInstallReceiver.EXTRA_SESSION_ID, sessionId)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val pending = PendingIntent.getBroadcast(context, sessionId, callback, flags)
                session.commit(pending.intentSender)
            }
            true
        }.getOrElse {
            android.util.Log.w("OperisUpdater", "stage=installer code=SESSION_FAILED")
            // A failed copy/commit must not leave a staged APK consuming device storage.
            pendingSessionId?.let { id -> runCatching { context.packageManager.packageInstaller.abandonSession(id) } }
            false
        }
    }
}

class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        android.util.Log.i("OperisUpdater", "stage=installer status=$status")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> openConfirmation(context, intent)
            PackageInstaller.STATUS_SUCCESS ->
                Toast.makeText(context, "OPERIS atualizado com sucesso.", Toast.LENGTH_LONG).show()
            else -> {
                val message = when (status) {
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Não há espaço suficiente para instalar a atualização."
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "A assinatura ou versão do APK conflita com a instalação atual."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "O Android bloqueou a instalação. Verifique a autorização para instalar pelo OPERIS."
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Instalação cancelada. Você pode tentar novamente pelo OPERIS."
                    PackageInstaller.STATUS_FAILURE_INVALID -> "O Android rejeitou o pacote de atualização. Baixe novamente."
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "O pacote de atualização não é compatível com este aparelho."
                    else -> "Não foi possível instalar a atualização. Tente novamente pelo OPERIS."
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }
        }
    }
    private fun openConfirmation(context: Context, intent: Intent) {
        val confirmation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        if (confirmation != null) {
            confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirmation) }
                .onFailure { Toast.makeText(context, "Abra o instalador novamente pelo OPERIS.", Toast.LENGTH_LONG).show() }
        } else {
            Toast.makeText(context, "Confirmação de instalação indisponível.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION = "br.com.alertaequipe.UPDATE_INSTALL_STATUS"
        const val EXTRA_SESSION_ID = "sessionId"
    }
}
