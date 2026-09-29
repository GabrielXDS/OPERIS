package br.com.alertaequipe

import android.app.DownloadManager
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.SubcomposeAsyncImage
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

internal data class AttachmentPick(val uri: Uri, val name: String, val type: String, val size: Long)
private const val MAX_BYTES = 10 * 1024 * 1024
private val acceptedTypes = setOf("image/jpeg", "image/png", "application/pdf")

internal suspend fun describeAttachment(context: Context, uri: Uri): AttachmentPick = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name = "anexo"
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val s = c.getColumnIndex(OpenableColumns.SIZE)
            if (n >= 0) name = c.getString(n) ?: name
            if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
        }
    }
    val type = resolver.getType(uri) ?: when (name.substringAfterLast('.').lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "pdf" -> "application/pdf"; else -> ""
    }
    require(type in acceptedTypes) { "Use JPG, PNG ou PDF." }
    require(size <= MAX_BYTES) { "Cada anexo deve ter no máximo 10 MB." }
    if (!name.contains('.')) name += when(type) { "image/jpeg" -> ".jpg"; "image/png" -> ".png"; else -> ".pdf" }
    AttachmentPick(uri, name.takeLast(120), type, size)
}

@Composable
internal fun AttachmentPicker(picks: List<String>, onChange: (List<String>) -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf("") }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var names by remember(picks) { mutableStateOf<Map<String,String>>(emptyMap()) }
    LaunchedEffect(picks) {
        names = picks.associateWith { runCatching { describeAttachment(context, Uri.parse(it)).name }.getOrDefault("Anexo selecionado") }
    }
    fun accept(uri: Uri, persist: Boolean) {
        scope.launch {
            try {
                describeAttachment(context, uri)
                if (persist) runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                if (picks.size < 3 && uri.toString() !in picks) onChange(picks + uri.toString())
                error = ""
            } catch(e: Exception) {
                if(e is kotlinx.coroutines.CancellationException) throw e
                error = e.message ?: "Não foi possível abrir o anexo."
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) accept(uri, true)
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) cameraUri?.let { accept(Uri.parse(it), false) }
        else if (cameraUri != null) error = "A captura foi cancelada ou não pôde ser concluída."
    }
    fun launchCameraCapture() {
        try {
            val dir = File(context.cacheDir, "attachment-camera").apply { mkdirs() }
            val file = File.createTempFile("foto-", ".jpg", dir)
            val uri = FileProvider.getUriForFile(context, context.packageName + ".attachments", file)
            cameraUri = uri.toString()
            camera.launch(uri)
            error = ""
        } catch (e: Exception) {
            android.util.Log.e("OperisCamera", "Falha ao abrir câmera", e)
            error = "Não foi possível abrir a câmera. Tente novamente."
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCameraCapture()
        else error = "Permissão da câmera negada. Autorize a câmera para tirar fotos."
    }
    Text("Anexos (${picks.size}/3)", color = MaterialTheme.colorScheme.onSurface)
    Text("JPG, PNG ou PDF • até 10 MB por arquivo", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    picks.forEach { uri ->
        Row(Modifier.fillMaxWidth()) {
            Text(names[uri] ?: "Anexo selecionado", Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick={onChange(picks-uri)}, enabled=enabled) { Text("REMOVER") }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=enabled && picks.size<3, onClick={picker.launch(arrayOf("image/jpeg","image/png","application/pdf"))}) { Text("GALERIA / ARQUIVO") }
    }
    OutlinedButton(enabled=enabled && picks.size<3, onClick={
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }) { Text("TIRAR FOTO") }
    if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
}

internal class AttachmentUploadState(val data: JSONObject = JSONObject()) {
    var recordId: String?
        get() = data.optString("recordId").takeIf { it.isNotBlank() }
        set(value) { if(value==null)data.remove("recordId") else data.put("recordId",value) }
    fun clear() { data.keys().asSequence().toList().forEach { data.remove(it) } }
    companion object {
        val saver = Saver<AttachmentUploadState,String>(save={it.data.toString()},restore={AttachmentUploadState(JSONObject(it))})
    }
}

internal suspend fun uploadAttachments(context: Context, teamId: String, recordType: String,
    state: AttachmentUploadState, picks: List<String>, progress: (String) -> Unit) {
    require(picks.size<=3)
    val recordId=checkNotNull(state.recordId)
    picks.forEachIndexed { index, raw ->
        progress("Enviando anexo ${index+1}/${picks.size}…")
        if(state.data.optJSONObject(raw)?.optBoolean("done")==true)return@forEachIndexed
        val pick=describeAttachment(context,Uri.parse(raw))
        val saved=state.data.optJSONObject(raw)
        var ticket=saved?.takeIf{it.optLong("expiresAt")>System.currentTimeMillis()}?.let {
            val headers=it.getJSONObject("headers")
            UploadTicket(it.getString("ticketId"),it.getString("storagePath"),it.getString("uploadUrl"),headers.keys().asSequence().associateWith{key->headers.getString(key)},it.getLong("expiresAt"))
        }
        if(ticket==null) {
            ticket=Backend.requestAttachmentUpload(teamId,recordType,recordId,pick.name,pick.type)
            state.data.put(raw,JSONObject().put("ticketId",ticket.ticketId).put("storagePath",ticket.storagePath)
                .put("uploadUrl",ticket.uploadUrl).put("headers",JSONObject(ticket.headers)).put("expiresAt",ticket.expiresAt))
        }
        // Recover a successful PUT/finalize whose response was lost without uploading a duplicate.
        if(saved!=null) {
            try { Backend.finalizeAttachment(ticket.ticketId);state.data.getJSONObject(raw).put("done",true);return@forEachIndexed }
            catch(e:com.google.firebase.functions.FirebaseFunctionsException) {
                if(e.code!=com.google.firebase.functions.FirebaseFunctionsException.Code.NOT_FOUND) throw e
            }
        }
        val bytes=withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(pick.uri)?.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { val n=input.read(buffer);if(n<0)break;require(out.size()+n<=MAX_BYTES){"Arquivo maior que 10 MB."};out.write(buffer,0,n) }
                out.toByteArray()
            } ?: error("Arquivo indisponível. Selecione-o novamente.")
        }
        require(bytes.isNotEmpty()) { "Arquivo vazio." }
        Backend.uploadBytes(ticket.uploadUrl,ticket.headers,bytes)
        Backend.finalizeAttachment(ticket.ticketId)
        state.data.getJSONObject(raw).put("done",true)
    }
}

@Composable
internal fun AttachmentLinks(teamId:String, recordType:String, recordId:String, attachments:List<Attachment>) {
    val scope=rememberCoroutineScope();val context=LocalContext.current
    var error by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<Attachment?>(null) }
    val imageAttachments = remember(attachments) {
        attachments.filter { it.contentType=="image/jpeg" || it.contentType=="image/png" }
    }
    // URLs assinadas buscadas uma única vez por anexo enquanto a tela estiver aberta.
    // Ausência na chave = carregando; chave presente com null = falha na obtenção da URL.
    val urls = remember(imageAttachments, teamId, recordType, recordId) {
        mutableStateOf<Map<String,String?>>(emptyMap())
    }
    val urlState = urls.value
    LaunchedEffect(imageAttachments, teamId, recordType, recordId) {
        if (imageAttachments.all { it.attachmentId in urlState }) return@LaunchedEffect
        val result = urls.value.toMutableMap()
        var changed = false
        for (att in imageAttachments) {
            if (att.attachmentId in result) continue
            val url = runCatching {
                Backend.requestAttachmentDownload(teamId, recordType, recordId, att.attachmentId).downloadUrl
            }.getOrNull()
            result[att.attachmentId] = url
            changed = true
        }
        if (changed) urls.value = result
    }
    Text("Anexos (${attachments.size})", color = MaterialTheme.colorScheme.onSurface)
    attachments.forEach { attachment ->
        if (attachment.contentType=="image/jpeg" || attachment.contentType=="image/png") {
            val url = urlState[attachment.attachmentId]
            val loaded = url != null
            val failed = attachment.attachmentId in urlState && !loaded
            AttachmentThumbnail(
                attachment = attachment,
                loading = urlState[attachment.attachmentId] == null && !failed,
                failed = failed,
                url = url,
                enabled = loaded,
                onClick = { viewing = attachment })
        } else {
            TextButton(onClick={
                error=""
                scope.launch {
                    try {
                        val ref=Backend.requestAttachmentDownload(teamId,recordType,recordId,attachment.attachmentId)
                        require(Uri.parse(ref.downloadUrl).scheme=="https")
                        context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(ref.downloadUrl)))
                    } catch(e:Exception) {if(e is kotlinx.coroutines.CancellationException)throw e;error="Não foi possível abrir o anexo. Tente novamente."}
                }
            }) { Text("${attachment.fileName} • ${attachment.formattedSize}", maxLines=1, overflow=TextOverflow.Ellipsis) }
        }
    }
    if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error)
    viewing?.let { attachment ->
        val url = urls.value[attachment.attachmentId]
        if (url != null) AttachmentImageViewer(url, attachment.fileName, attachment.contentType) { viewing = null }
    }
}

@Composable
private fun AttachmentThumbnail(attachment: Attachment, loading: Boolean, failed: Boolean,
    url: String?, enabled: Boolean, onClick: () -> Unit) {
    Surface(onClick=onClick, enabled=enabled, color=AppPanel, shape=RoundedCornerShape(12.dp),
        modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.size(width=64.dp, height=56.dp).clip(RoundedCornerShape(8.dp)).background(AppInk),
                contentAlignment=Alignment.Center) {
                when {
                    failed -> Text("INDISPONÍVEL", color=AppMuted, fontSize=9.sp)
                    loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth=2.dp, color=AppMuted)
                    url != null -> SubcomposeAsyncImage(
                        model=url, contentDescription=attachment.fileName,
                        modifier=Modifier.fillMaxSize(), contentScale=ContentScale.Crop,
                        loading={ CircularProgressIndicator(Modifier.align(Alignment.Center).size(20.dp), strokeWidth=2.dp, color=AppMuted) },
                        error={ Text("SEM IMAGEM", color=AppMuted, fontSize=9.sp) })
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(attachment.fileName, color=MaterialTheme.colorScheme.onSurface,
                    fontWeight=FontWeight.Medium, fontSize=14.sp, maxLines=1, overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height(3.dp))
                Text(attachment.formattedSize, color=AppMuted, fontSize=12.sp)
            }
        }
    }
}

@Composable
private fun AttachmentImageViewer(url: String, fileName: String, contentType: String, onClose: () -> Unit) {
    val context = LocalContext.current
    var downloadMessage by remember { mutableStateOf<String?>(null) }
    fun startDownload() {
        try {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val safeName = fileName.substringAfterLast('/').ifBlank { "anexo" }
            DownloadManager.Request(Uri.parse(url))
                .setTitle(safeName)
                .setDescription("Anexo do OPERIS")
                .setMimeType(contentType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
                .also { dm.enqueue(it) }
            downloadMessage = "Download iniciado: $safeName"
            Toast.makeText(context, "Download iniciado", Toast.LENGTH_SHORT).show()
        } catch(e:Exception) {
            downloadMessage = "Não foi possível iniciar o download."
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)
    ) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 8.dp, top = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onClose) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Fechar")
                    Text(" VOLTAR")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { startDownload() }) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(" BAIXAR")
                }
            }
            SubcomposeAsyncImage(
                model = url,
                contentDescription = "Anexo",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = { CircularProgressIndicator(Modifier.align(Alignment.Center)) },
                error = {
                    Column(Modifier.align(Alignment.Center).padding(24.dp)) {
                        Text("Não foi possível carregar a imagem.",
                            color = Color.White, textAlign = TextAlign.Center)
                    }
                }
            )
            if (downloadMessage != null) {
                Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp)) {
                    Text(downloadMessage!!, color = Color.White, fontSize = 13.sp)
                }
            }
        }
    }
}
