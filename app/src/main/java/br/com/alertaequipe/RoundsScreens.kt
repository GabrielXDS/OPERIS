package br.com.alertaequipe

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun RoundsListScreen(teamName: String?, records: List<RoundReport>, loading: Boolean,
    error: String, hasMore: Boolean, deletedOnly: Boolean, canMutate: (RoundReport) -> Boolean,
    onRegister: () -> Unit, onOpen: (RoundReport) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    onToggleDeleted: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Text("RONDAS", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground)
            Text(teamName ?: "Selecione uma equipe em Minhas equipes.", color = AppMuted)
            Button(onClick = onRegister, enabled = teamName != null && !deletedOnly,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("+ REGISTRAR RONDA") }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRefresh, enabled = !loading && teamName != null) { Text("ATUALIZAR") }
                Spacer(Modifier.weight(1f))
                Text(if (deletedOnly) "REGISTROS EXCLUÍDOS" else "EXCLUÍDOS", color = AppMuted)
                Switch(checked = deletedOnly, onCheckedChange = { onToggleDeleted() })
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            if (deletedOnly) Text("Registros excluídos ficam disponíveis somente para leitura.",
                color = AppMuted, style = MaterialTheme.typography.bodySmall)
            if (!loading && error.isBlank() && records.isEmpty() && teamName != null)
                Text(if (deletedOnly) "Nenhum registro excluído." else "Nenhum achado registrado.",
                    color = MaterialTheme.colorScheme.onSurface)
        }
        records.groupBy { it.shiftId ?: "sem_plantao" }.forEach { (shiftKey, group) ->
            item(key = "shift:$shiftKey") {
                Surface(color = AppPanel, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(group.firstOrNull()?.shiftLabel?.takeIf { it.isNotBlank() } ?: "Plantão", fontWeight = FontWeight.Bold)
                        group.firstOrNull()?.shiftStartedAt?.let { started ->
                            Text("Início: ${java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt","BR")).format(java.util.Date(started))}", color = AppMuted)
                        }
                        Text("${group.size} ronda(s)", color = AppMuted)
                    }
                }
            }
            items(group, key = { it.reportId }) { report ->
                Card(onClick = { onOpen(report) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${report.status} • ${report.findingType.label}", color = AppMuted, modifier = Modifier.weight(1f))
                            if (report.deleted) Text("EXCLUÍDO", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                        Text(report.title, fontWeight = FontWeight.Bold)
                        Text(report.location)
                        Text(report.authorName, color = AppMuted)
                        Text(report.formattedTime, color = AppMuted)
                    }
                }
            }
        }
        if (hasMore) item { TextButton(onClick = onMore, enabled = !loading) { Text("CARREGAR MAIS") } }
    }
}

@Composable
internal fun NewRoundScreen(teamName: String, busy: Boolean, error: String,
    onBack: () -> Unit, locked: Boolean, initial: RoundReport?,
    onSave: (RoundDraft, List<String>) -> Unit) {
    var type by rememberSaveable(initial?.reportId) { mutableStateOf(initial?.findingType?.name ?: RoundType.OPEN_DOOR.name) }
    var title by rememberSaveable(initial?.reportId) { mutableStateOf(initial?.title ?: RoundType.OPEN_DOOR.label) }
    var titleEdited by rememberSaveable(initial?.reportId) { mutableStateOf(initial != null) }
    var location by rememberSaveable(initial?.reportId) { mutableStateOf(initial?.location ?: "") }
    var description by rememberSaveable(initial?.reportId) { mutableStateOf(initial?.description ?: "") }
    val legacyImmediateAction = initial?.immediateAction ?: ""
    var expanded by remember { mutableStateOf(false) }
    var picks by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val editing = initial != null
    val draft = RoundDraft(RoundType.valueOf(type), title, location, description, legacyImmediateAction)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack, enabled = !busy) { Text("VOLTAR") }
        Text(if (editing) "EDITAR RONDA" else "NOVA RONDA", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground)
        Text(teamName, color = AppMuted)
        if (editing) Text("Autor original: ${initial?.authorName}", color = AppMuted)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !busy && !locked) { Text("Tipo da irregularidade: ${draft.type.label} ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                RoundType.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(item.label) }, onClick = { type = item.name; if (!titleEdited) title = item.label; expanded = false })
                }
            }
        }
        OutlinedTextField(title, { if (it.length <= 80) { title = it; titleEdited = true } }, label = { Text("Título (3–80 caracteres)") },
            enabled = !busy && !locked, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(location, { if (it.length <= 100) location = it }, label = { Text("Local") },
            enabled = !busy && !locked, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { if (it.length <= 2000) description = it }, label = { Text("Descrição do achado") },
            enabled = !busy && !locked, minLines = 3, modifier = Modifier.fillMaxWidth())
        if (editing) {
            Text("Anexos já registrados", color = MaterialTheme.colorScheme.onSurface)
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                AttachmentLinks(initial!!.teamId, "round", initial!!.reportId, initial!!.attachments)
            }
        } else {
            AttachmentPicker(picks, { picks = it }, enabled = !busy && !locked)
        }
        if (locked) Text("Registro salvo. Conclua o envio dos anexos ou volte à lista.",
            color = MaterialTheme.colorScheme.onSurface)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = { onSave(draft, picks) }, enabled = !busy && draft.valid,
            modifier = Modifier.fillMaxWidth()) {
            Text(when { busy -> "SALVANDO…"; editing -> "SALVAR ALTERAÇÕES"; else -> "SALVAR RONDA" })
        }
    }
}

@Composable
internal fun RoundDetailsScreen(report: RoundReport?, loading: Boolean, error: String,
    canEdit: Boolean, canDelete: Boolean, onBack: () -> Unit, onRetry: () -> Unit,
    onEdit: () -> Unit, onDelete: () -> Unit) {
    var shareOpen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ VOLTAR") }
        Text("NOTA DE RONDA", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("TENTAR NOVAMENTE") }
        }
        report?.let {
            if (it.deleted) Text("REGISTRO EXCLUÍDO • SOMENTE LEITURA", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { shareOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("COMPARTILHAR") }
            if (!it.deleted && (canEdit || canDelete)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (canEdit) OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("EDITAR") }
                    if (canDelete) OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("EXCLUIR") }
                }
            }
            Text(it.title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp)); StatusChip(roundStatusLabel(it.status)); Spacer(Modifier.height(4.dp))
            Text("Registrado por ${it.authorName}", style = MaterialTheme.typography.bodySmall, color = AppMuted)
            it.updatedByName?.let { n -> Text("Atualizado por $n", style = MaterialTheme.typography.bodySmall, color = AppMuted) }
            it.deletedByName?.let { n -> Text("Excluído por $n", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            DetailField("Tipo da irregularidade", it.findingType.label); DetailField("Data e hora", it.formattedTime)
            DetailField("Local", it.location); DetailField("Descrição do achado", it.description)
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { AttachmentLinks(it.teamId,"round",it.reportId,it.attachments) }
        }
    }
    if (shareOpen && report != null) {
        NoteShareDialog("Compartilhar ronda", roundShareText(report)) { shareOpen = false }
    }
}

private fun roundStatusLabel(status: String?): String {
    val raw = status?.trim().orEmpty()
    return when (raw.uppercase()) {
        "IDENTIFIED" -> "Irregularidade identificada"
        else -> raw
    }
}

@Composable
private fun StatusChip(label: String) {
    Surface(color = AppPanel, shape = RoundedCornerShape(8.dp)) {
        Text(label, color = AppAmber, style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    }
}

@Composable
private fun DetailField(label: String, value: String) {
    val v = value.trim()
    if (v.isBlank()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = AppMuted)
        Text(v, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface, lineHeight = 22.sp)
    }
}
