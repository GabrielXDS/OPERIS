package br.com.alertaequipe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

internal fun incidentNote(note: Incident): String = buildString {
    appendLine("NOTA DE OCORRÊNCIA")
    append("Data/hora: ")
    appendLine(note.formattedTime)
    append("Status: ")
    appendLine(note.status)
    append("Tipo: ")
    appendLine(note.type.label)
    append("Título: ")
    appendLine(note.title)
    append("Local: ")
    appendLine(note.location)
    appendLine("Descrição:")
    appendLine(note.description)
    append("Providências adotadas: ")
    appendLine(note.actionsTaken.ifBlank { "Nenhuma providência informada." })
    append("Registrado por: ")
    appendLine(note.authorName)
    note.updatedByName?.let {
        append("Atualizado por: ")
        appendLine(it)
    }
    if (note.deleted) appendLine("Registro excluído (somente leitura)")
}

internal fun roundNote(note: RoundReport): String = buildString {
    appendLine("NOTA DE RONDA")
    append("Data/hora: ")
    appendLine(note.formattedTime)
    append("Status: ")
    appendLine(note.status)
    append("Tipo da irregularidade: ")
    appendLine(note.findingType.label)
    append("Título: ")
    appendLine(note.title)
    append("Local: ")
    appendLine(note.location)
    appendLine("Descrição do achado:")
    appendLine(note.description)
    append("Providência imediata: ")
    appendLine(note.immediateAction.ifBlank { "Nenhuma ação informada." })
    append("Registrado por: ")
    appendLine(note.authorName)
    note.updatedByName?.let {
        append("Atualizado por: ")
        appendLine(it)
    }
    if (note.deleted) appendLine("Registro excluído (somente leitura)")
}

// Compartilha somente texto, nunca URLs assinadas de anexos (por design).
@Composable
internal fun NoteTextDialog(initialText: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
    var message by remember { mutableStateOf<String?>(null) }
    fun copy() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Nota OPERIS", text))
        message = "Texto copiado para a área de transferência."
    }
    fun whatsapp() {
        val base = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        var opened = false
        for (pkg in listOf("com.whatsapp", "com.whatsapp.w4b")) {
            val target = Intent(base).apply { setPackage(pkg) }
            if (target.resolveActivity(context.packageManager) != null) {
                runCatching { context.startActivity(target) }.onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                }
                opened = true
                break
            }
        }
        if (!opened) {
            context.startActivity(Intent.createChooser(base, "Compartilhar"))
            message = "WhatsApp não encontrado. Escolha outro aplicativo de mensagens."
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("NOTA PARA COMPARTILHAMENTO") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(text, { text = it }, minLines = 7,
                    modifier = Modifier.fillMaxWidth())
                Text("O texto pode ser ajustado antes do envio.", color = AppMuted,
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { copy() }, modifier = Modifier.fillMaxWidth()) { Text("COPIAR TEXTO") }
                Button(onClick = { whatsapp() }, modifier = Modifier.fillMaxWidth()) { Text("WHATSAPP") }
                message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("FECHAR") } }
    )
}