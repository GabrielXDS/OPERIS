package br.com.alertaequipe

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import java.io.File

/** Snapshot of the ongoing APK download/install state shown by both update UIs. */
data class UpdateUiState(
    val downloading: Boolean = false,
    val downloaded: Boolean = false,
    val verified: Boolean = false,
    val error: String? = null,
    val preparing: Boolean = false
)

/** Identidade lida do próprio APK baixado (pacote e versão, sem instalar). */
data class ApkInspect(val packageName: String, val versionCode: Int, val certificates: Set<String> = emptySet())

/** Download, SHA-256 validation and install plumbing (pure logic kept testable). */
object UpdateFlow {
    fun apkFileName(versionName: String): String = "OPERIS-${versionName}.apk"
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    fun sha256(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
    fun verified(expected: String?, file: File): Boolean {
        if (expected == null || expected.length != 64) return false
        return expected.equals(sha256(file), ignoreCase = true)
    }
    /** Triple check do pacote candidato: mesmo app, versão NOVA (maior que a instalada). */
    fun installable(pkg: String?, apkVersionCode: Int, expectedPackage: String, installedVersionCode: Int,
        advertisedVersionCode: Int = apkVersionCode): Boolean =
        pkg == expectedPackage && apkVersionCode > installedVersionCode && apkVersionCode == advertisedVersionCode

    fun checkMessage(status: ReleaseStatus, versionName: String): String = when (status) {
        ReleaseStatus.UP_TO_DATE -> "Você está na versão mais recente."
        ReleaseStatus.NEWER_THAN_PUBLISHED -> "Você está em uma versão mais recente que a versão atualmente publicada."
        ReleaseStatus.UPDATE_AVAILABLE, ReleaseStatus.REQUIRED -> "Nova versão disponível: $versionName."
        ReleaseStatus.UNKNOWN -> "Não foi possível verificar agora. Tente novamente."
    }
    /** Lê packageName/versionCode do APK baixado através do PackageManager (sem instalar). */
    @Suppress("DEPRECATION")
    fun inspect(context: Context, file: File): ApkInspect? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val pkg = info.applicationInfo?.packageName ?: info.packageName
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
        ApkInspect(pkg, version, certificates(info))
    }.getOrNull()
    @Suppress("DEPRECATION")
    private fun certificates(info: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { signature ->
            java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }
    fun signaturesMatch(candidate: Set<String>, installed: Set<String>): Boolean = candidate.isNotEmpty() && candidate == installed
    @Suppress("DEPRECATION")
    fun sameSignature(context: Context, candidate: ApkInspect): Boolean = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        signaturesMatch(candidate.certificates, certificates(context.packageManager.getPackageInfo(context.packageName, flags)))
    }.getOrDefault(false)
    fun formatApkSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
    fun enqueue(context: Context, url: String, versionName: String): Long {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        // Only the previous APK candidate is removed; application data is never touched.
        val destination = downloadedFile(context, versionName)
        check(!destination.exists() || destination.delete()) { "Não foi possível substituir o download anterior." }
        val request = DownloadManager.Request(Uri.parse(url))
            .setAllowedOverMetered(true)
            .setTitle("OPERIS $versionName")
            .setDescription("Baixando a atualização do OPERIS")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalFilesDir(context, null, apkFileName(versionName))
        return manager.enqueue(request)
    }
    fun downloadedFile(context: Context, versionName: String): File =
        File(context.getExternalFilesDir(null), apkFileName(versionName))
    fun queryStatus(context: Context, downloadId: Long): Int {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId))
        return cursor.use {
            if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            else DownloadManager.STATUS_FAILED
        }
    }
    fun queryFailure(context: Context, downloadId: Long): UpdateFailure {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return manager.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
            val reason = if (cursor.moveToFirst()) cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)) else 0
            UpdateDiagnostics.download(reason)
        }
    }
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()
    fun openUnknownSources(context: Context): Boolean =
        runCatching {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName)))
        }.isSuccess
    fun fallback(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".update", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("Atualização OPERIS", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }.isSuccess
    fun install(context: Context, file: File): Boolean =
        canInstall(context) && UpdatePackageInstaller.install(context, file)
}

@Composable
internal fun RequiredUpdateScreen(
    installedVersionName: String,
    policy: ReleasePolicy,
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onFallback: () -> Unit
) {
    val published = policy.canDownload()
    Column(
        Modifier.fillMaxSize().background(AppInk).statusBarsPadding().navigationBarsPadding().widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(64.dp))
        Text("ATUALIZAÇÃO\nNECESSÁRIA", fontSize = 26.sp, fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center, color = AppRed, letterSpacing = 1.sp)
        Spacer(Modifier.height(18.dp))
        Text("Esta versão do OPERIS não é mais suportada pelo serviço.",
            fontSize = 15.sp, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth()) {
            ReleaseLine("Instalada", installedVersionName)
            ReleaseLine("Mínima necessária", "Código de versão ${policy.minSupportedVersionCode}")
            ReleaseLine("Disponível", policy.latestVersionName)
            policy.apkSize?.let { ReleaseLine("Tamanho", UpdateFlow.formatApkSize(it)) }
        }
        Spacer(Modifier.height(20.dp))
        Text("NOVIDADES", color = AppMuted, fontSize = 11.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            policy.releaseNotes.forEach { note ->
                Text("• $note", color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        if (!published) {
            Text("O pacote de atualização ainda não foi publicado. Tente novamente mais tarde.",
                color = AppMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        } else if (state.error != null) {
            Text(state.error, color = AppRed, fontSize = 13.sp, textAlign = TextAlign.Center)
        } else if (state.downloading) {
            Text(if (state.preparing) "Validando e preparando a instalação…" else "Baixando a nova versão…", color = AppMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        } else if (state.downloaded && state.verified) {
            Text("Download concluído. Confirme a instalação.", color = AppMuted, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onUpdate,
            enabled = if (state.downloading) false else published && !(state.downloaded && !state.verified),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                when {
                    state.preparing -> "PREPARANDO…"
                    state.downloading -> "BAIXANDO…"
                    state.downloaded && state.verified -> "INSTALAR"
                    else -> "ATUALIZAR AGORA"
                },
                fontSize = 17.sp, fontWeight = FontWeight.Bold
            )
        }
        if (published) {
            Spacer(Modifier.height(10.dp))
            Text("O Android pode solicitar autorização para instalar a partir do OPERIS.",
                color = AppMuted, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        if (state.downloaded && state.verified && !state.downloading) {
            TextButton(onClick = onFallback) { Text("ABRIR INSTALADOR ALTERNATIVO") }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
internal fun OptionalUpdateDialog(
    installedVersionName: String,
    policy: ReleasePolicy,
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onFallback: () -> Unit,
    onLater: () -> Unit
) {
    val published = policy.canDownload()
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("NOVA VERSÃO DISPONÍVEL") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Você usa o OPERIS $installedVersionName. A versão ${policy.latestVersionName} já está disponível.",
                    color = MaterialTheme.colorScheme.onSurface)
                policy.apkSize?.let {
                    Text("Tamanho do pacote: ${UpdateFlow.formatApkSize(it)}", color = AppMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 4.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("NOVIDADES", color = AppMuted, fontSize = 11.sp, letterSpacing = 1.4.sp)
                policy.releaseNotes.forEach { note ->
                    Text("• $note", fontSize = 14.sp, modifier = Modifier.padding(vertical = 2.dp))
                }
                if (!published) Text("O pacote de atualização ainda não foi publicado.",
                    color = AppMuted, fontSize = 13.sp)
                if (state.error != null) Text(state.error, color = AppRed, fontSize = 13.sp)
                if (state.downloading) Text(if (state.preparing) "Validando e preparando a instalação…" else "Baixando a nova versão…", color = AppMuted, fontSize = 13.sp)
                if (state.downloaded && state.verified) Text("Download concluído. Confirme a instalação.",
                    color = AppMuted, fontSize = 13.sp)
                if (published) Text("O Android pode solicitar autorização para instalar a partir do OPERIS.",
                    color = AppMuted, fontSize = 12.sp)
                if (state.downloaded && state.verified && !state.downloading) {
                    TextButton(onClick = onFallback) { Text("ABRIR INSTALADOR ALTERNATIVO") }
                }
            }
        },
        dismissButton = { TextButton(onClick = { if (!state.downloading) onLater() }) { Text("AGORA NÃO") } },
        confirmButton = {
            Button(
                onClick = onUpdate,
                enabled = !state.downloading && published && !(state.downloaded && !state.verified)
            ) {
                Text(
                    when {
                        state.preparing -> "PREPARANDO…"
                        state.downloading -> "BAIXANDO…"
                        state.downloaded && state.verified -> "INSTALAR"
                        else -> "ATUALIZAR"
                    }
                )
            }
        }
    )
}

@Composable
private fun ReleaseLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = AppMuted, fontSize = 14.sp, modifier = Modifier.width(140.dp))
        Spacer(Modifier.width(8.dp))
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Registers a one-shot download-complete bridge per pending download id. */
@Composable
internal fun DownloadCompletionMonitor(
    downloadId: Long?,
    onComplete: (id: Long) -> Unit
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentComplete by rememberUpdatedState(onComplete)
    DisposableEffect(downloadId, lifecycle) {
        if (downloadId == null) return@DisposableEffect onDispose {}
        fun reconcile() {
            val status = runCatching { UpdateFlow.queryStatus(context, downloadId) }.getOrNull()
            if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED)
                currentComplete(downloadId)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE &&
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) == downloadId
                ) reconcile()
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) reconcile() }
        lifecycle.addObserver(observer)
        reconcile()
        onDispose {
            lifecycle.removeObserver(observer)
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
