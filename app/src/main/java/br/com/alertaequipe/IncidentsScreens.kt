package br.com.alertaequipe

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun IncidentsListScreen(teamName: String?, records: List<Incident>, loading: Boolean,
    error: String, hasMore: Boolean, deletedOnly: Boolean, canMutate: (Incident) -> Boolean,
    onRegister: () -> Unit, onOpen: (Incident) -> Unit, onRefresh: () -> Unit, onMore: () -> Unit,
    onToggleDeleted: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
Text("OCORRÊNCIAS", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground)
            Text(teamName ?: "Selecione uma equipe em Minhas equipes.", color = AppMuted)
            Button(onClick = onRegister, enabled = teamName != null && !deletedOnly,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("+ REGISTRAR OCORRÊNCIA") }
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
                Text(if (deletedOnly) "Nenhum registro excluído." else "Nenhuma ocorrência registrada.",
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
                        Text("${group.size} ocorrência(s)", color = AppMuted)
                    }
                }
            }
            items(group, key = { it.incidentId }) { incident ->
                Card(onClick = { onOpen(incident) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${incident.status} • ${incident.type.label}", color = AppMuted, modifier = Modifier.weight(1f))
                            if (incident.deleted) Text("EXCLUÍDO", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                        Text(incident.title, fontWeight = FontWeight.Bold)
                        Text(incident.location)
                        Text(incident.authorName, color = AppMuted)
                        Text(incident.formattedTime, color = AppMuted)
                        if (incident.deleted && canMutate(incident)) Text("Edição e exclusão não estão disponíveis.", color = AppMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (hasMore) item { TextButton(onClick = onMore, enabled = !loading) { Text("CARREGAR MAIS") } }
    }
}

@Composable
internal fun NewIncidentScreen(teamName: String, busy: Boolean, error: String,
    onBack: () -> Unit, locked: Boolean, initial: Incident?,
    onSave: (IncidentDraft, List<String>) -> Unit) {
    var type by rememberSaveable(initial?.incidentId) { mutableStateOf(initial?.type?.name ?: IncidentType.ALARM_TRIGGER.name) }
    var title by rememberSaveable(initial?.incidentId) { mutableStateOf(initial?.title ?: IncidentType.ALARM_TRIGGER.label) }
    var titleEdited by rememberSaveable(initial?.incidentId) { mutableStateOf(initial != null) }
    var location by rememberSaveable(initial?.incidentId) { mutableStateOf(initial?.location ?: "") }
    var description by rememberSaveable(initial?.incidentId) { mutableStateOf(initial?.description ?: "") }
    var actions by rememberSaveable(initial?.incidentId) { mutableStateOf(initial?.actionsTaken ?: "") }
    val reference = initial?.reference ?: ""
    var expanded by remember { mutableStateOf(false) }
    var picks by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val editing = initial != null
    val draft = IncidentDraft(IncidentType.valueOf(type), title, location, description, actions, reference)
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
TextButton(onClick = onBack, enabled = !busy) { Text("VOLTAR") }
        Text(if (editing) "EDITAR OCORRÊNCIA" else "NOVA OCORRÊNCIA", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground)
        Text(teamName, color = AppMuted)
        if (editing) Text("Autor original: ${initial?.authorName}", color = AppMuted)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = !busy && !locked) { Text("Tipo: ${draft.type.label} ▾") }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                IncidentType.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(item.label) }, onClick = { type = item.name; if (!titleEdited) title = item.label; expanded = false })
                }
            }
        }
        OutlinedTextField(title, { if (it.length <= 80) { title = it; titleEdited = true } }, label = { Text("Título (3–80 caracteres)") },
            enabled = !busy && !locked, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(location, { if (it.length <= 100) location = it }, label = { Text("Local") },
            enabled = !busy && !locked, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(description, { if (it.length <= 2000) description = it }, label = { Text("Descrição") },
            enabled = !busy && !locked, minLines = 3, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(actions, { if (it.length <= 2000) actions = it }, label = { Text("Providências adotadas (opcional)") },
            enabled = !busy && !locked, minLines = 3, modifier = Modifier.fillMaxWidth())
        if (editing) {
            Text("Anexos já registrados", color = MaterialTheme.colorScheme.onSurface)
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                AttachmentLinks(initial!!.teamId, "incident", initial!!.incidentId, initial!!.attachments)
            }
        } else {
            AttachmentPicker(picks, { picks = it }, enabled = !busy && !locked)
        }
        if (locked) Text("Registro salvo. Conclua o envio dos anexos ou volte à lista.",
            color = MaterialTheme.colorScheme.onSurface)
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Button(onClick = { onSave(draft, picks) }, enabled = !busy && draft.valid, modifier = Modifier.fillMaxWidth()) {
            Text(when { busy -> "SALVANDO…"; editing -> "SALVAR ALTERAÇÕES"; else -> "SALVAR OCORRÊNCIA" })
        }
    }
}

@Composable
internal fun IncidentDetailsScreen(incident: Incident?, loading: Boolean, error: String,
    canEdit: Boolean, canDelete: Boolean, onBack: () -> Unit, onRetry: () -> Unit,
    onEdit: () -> Unit, onShareSector: (OperationalSector) -> Unit, onDelete: () -> Unit) {
    var externalShareOpen by remember { mutableStateOf(false) }
    var forwardOpen by remember { mutableStateOf(false) }
Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("‹ VOLTAR") }
        Text("NOTA DE OCORRÊNCIA", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground)
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, enabled = !loading) { Text("TENTAR NOVAMENTE") }
        }
        incident?.let {
            if (it.deleted) Text("REGISTRO EXCLUÍDO • SOMENTE LEITURA",
                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            OutlinedButton(onClick = { externalShareOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("COMPARTILHAR") }
            if (!it.deleted) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { forwardOpen = true }, modifier = Modifier.weight(1f)) { Text("ENCAMINHAR") }
                    if (canEdit) OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("EDITAR") }
                    if (canDelete) OutlinedButton(onClick = onDelete, modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("EXCLUIR") }
                }
            }
            Text(it.title, style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            StatusChip(incidentStatusLabel(it.status))
            Spacer(Modifier.height(4.dp))
            Text("Registrado por ${it.authorName}", style = MaterialTheme.typography.bodySmall, color = AppMuted)
            it.updatedByName?.let { Text("Atualizado por $it", style = MaterialTheme.typography.bodySmall, color = AppMuted) }
            it.deletedByName?.let { Text("Excluído por $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(8.dp))
            DetailField("Tipo da ocorrência", it.type.label)
            DetailField("Data e hora", it.formattedTime)
            DetailField("Local", it.location)
            DetailField("Descrição", it.description)
            DetailField("Providências adotadas", it.actionsTaken.ifBlank { "Nenhuma providência informada." })
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                AttachmentLinks(it.teamId,"incident",it.incidentId,it.attachments)
            }
            if (it.sharedWithSectors.isNotEmpty()) {
                DetailField("Encaminhado para", it.sharedWithSectors.joinToString { s -> OperationalSector.entries.firstOrNull { it.name == s }?.label ?: s })
            }
            if (it.shareHistory.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("AUDITORIA DE ENCAMINHAMENTO", fontWeight = FontWeight.Bold)
                it.shareHistory.asReversed().take(8).forEach { share ->
                    Text("${share.formattedTime} · ${share.sectorLabel} · ${share.sharedByName}", color = AppMuted)
                }
            }
        }
    }
    if (externalShareOpen && incident != null) {
        NoteShareDialog("Compartilhar ocorrência", incidentShareText(incident)) { externalShareOpen = false }
    }
    if (forwardOpen && incident != null) {
        val source = OperationalSector.fromFunction(incident.authorOperationalFunction)
        val target = if (source == OperationalSector.BRIGADA) OperationalSector.SEGURANCA else OperationalSector.BRIGADA
        val already = incident.sharedWithSectors.contains(target.name)
        AlertDialog(onDismissRequest = { forwardOpen = false },
            title = { Text("Encaminhar ocorrência") },
            text = { Text(if (already) "Esta ocorrência já está disponível para ${target.label}." else "Encaminhar esta ocorrência para ${target.label} e notificar os integrantes desse setor?") },
            dismissButton = { TextButton(onClick = { forwardOpen = false }) { Text("CANCELAR") } },
            confirmButton = { Button(onClick = { forwardOpen = false; onShareSector(target) }, enabled = !already) { Text(if (already) "JÁ ENCAMINHADO" else "ENCAMINHAR") } })
    }
}

private fun incidentStatusLabel(status: String?): String {
    val raw = status?.trim().orEmpty()
    return when (raw.uppercase()) {
        "OPEN" -> "Em aberto"
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
