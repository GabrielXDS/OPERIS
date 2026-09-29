package br.com.alertaequipe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun NoteShareDialog(title: String, text: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Button(onClick = { shareWhatsApp(context, text); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("WHATSAPP")
                }
                OutlinedButton(onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, text))
                    Toast.makeText(context, "Texto copiado.", Toast.LENGTH_SHORT).show()
                    onDismiss()
                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("COPIAR TEXTO") }
                OutlinedButton(onClick = { shareGeneral(context, text, title); onDismiss() },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("MAIS OPÇÕES") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("FECHAR") } }
    )
}

private fun shareWhatsApp(context: Context, text: String) {
    val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
    val target = packages.firstOrNull { pkg ->
        runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }
    if (target == null) {
        shareGeneral(context, text, "Compartilhar nota")
        return
    }
    context.startActivity(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        setPackage(target)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    })
}
private fun shareGeneral(context: Context, text: String, title: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, title)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

internal fun incidentShareText(item: Incident): String = buildString {
    appendLine("OPERIS — NOTA DE OCORRÊNCIA")
    appendLine(item.title)
    appendLine("Tipo: ${item.type.label}")
    appendLine("Data/hora: ${item.formattedTime}")
    appendLine("Local: ${item.location}")
    appendLine()
    appendLine("Descrição:")
    appendLine(item.description)
    if (item.actionsTaken.isNotBlank()) {
        appendLine()
        appendLine("Providências adotadas:")
        appendLine(item.actionsTaken)
    }
    appendLine()
    append("Registrado por ${item.authorName}")
}

internal fun roundShareText(item: RoundReport): String = buildString {
    appendLine("OPERIS — NOTA DE RONDA")
    appendLine(item.title)
    appendLine("Tipo: ${item.findingType.label}")
    appendLine("Data/hora: ${item.formattedTime}")
    appendLine("Local: ${item.location}")
    appendLine()
    appendLine("Descrição do achado:")
    appendLine(item.description)
    appendLine()
    append("Registrado por ${item.authorName}")
}
