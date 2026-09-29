package br.com.alertaequipe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

private data class PublicEmergencyContact(val name: String, val phone: String, val help: String)

private val publicEmergencyContacts = listOf(
    PublicEmergencyContact("Polícia Militar", "190", "Segurança pública e crime em andamento"),
    PublicEmergencyContact("SAMU", "192", "Urgência e emergência médica"),
    PublicEmergencyContact("Corpo de Bombeiros", "193", "Incêndios, acidentes, resgates e salvamentos"),
    PublicEmergencyContact("Polícia Rodoviária Federal", "191", "Emergências em rodovias federais"),
    PublicEmergencyContact("Defesa Civil", "199", "Desastres, enchentes, deslizamentos e áreas de risco"),
)

@Composable
internal fun EmergencyContactsScreen(
    teamName: String?,
    contacts: List<EmergencyContact>,
    canManage: Boolean,
    saving: Boolean,
    message: String,
    onDial: (String) -> Unit,
    onSave: (List<EmergencyContact>) -> Unit,
) {
    var editing by remember { mutableStateOf<EmergencyContact?>(null) }
    var adding by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("CHAMADAS DE EMERGÊNCIA", fontSize = 21.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Toque em LIGAR para abrir o discador. A chamada só acontece após sua confirmação.",
            color = AppMuted, fontSize = 13.sp)
        Spacer(Modifier.height(22.dp))
        SectionLabel("SERVIÇOS PÚBLICOS")
        publicEmergencyContacts.forEach { c ->
            EmergencyCard(c.name, c.phone, c.help, false, saving, onDial, {}, {})
        }
        Spacer(Modifier.height(20.dp))
        SectionLabel("CONTATOS DA EQUIPE")
        if (teamName == null) {
            Text("Selecione uma equipe para visualizar contatos institucionais.", color = AppMuted)
        } else {
            Text(teamName, color = AppMuted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (contacts.isEmpty()) {
                Text("Nenhum contato institucional cadastrado.", color = AppMuted, fontSize = 13.sp)
            }
            contacts.forEach { c ->
                EmergencyCard(c.name, c.phone, "Contato definido pela equipe", canManage, saving, onDial,
                    { editing = c }, { onSave(contacts.filterNot { it.id == c.id }) })
            }
            if (canManage) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { adding = true }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                    Text("ADICIONAR CONTATO")
                }
                Text("Somente proprietário e administradores podem alterar esta lista.",
                    color = AppMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = if (message.startsWith("Erro")) AppRed else AppMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
    if (adding || editing != null) {
        EmergencyContactDialog(
            initial = editing,
            saving = saving,
            onDismiss = { if (!saving) { adding = false; editing = null } },
            onConfirm = { name, phone ->
                val current = editing
                val item = if (current == null) EmergencyContact(UUID.randomUUID().toString(), name, phone)
                    else current.copy(name = name, phone = phone)
                val next = if (current == null) contacts + item else contacts.map { if (it.id == current.id) item else it }
                onSave(next)
                adding = false
                editing = null
            }
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = AppMuted, fontSize = 11.sp, letterSpacing = 1.3.sp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
}

@Composable
private fun EmergencyCard(
    name: String,
    phone: String,
    help: String,
    canManage: Boolean,
    saving: Boolean,
    onDial: (String) -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Surface(color = AppPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(phone, color = AppRed, fontWeight = FontWeight.Black, fontSize = 23.sp)
                    Text(help, color = AppMuted, fontSize = 12.sp)
                }
                Button(onClick = { onDial(phone) }, enabled = !saving) { Text("LIGAR") }
            }
            if (canManage) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onEdit, enabled = !saving) { Text("EDITAR") }
                    TextButton(onClick = onRemove, enabled = !saving) { Text("REMOVER", color = AppRed) }
                }
            }
        }
    }
}

@Composable
private fun EmergencyContactDialog(
    initial: EmergencyContact?,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initial?.id) { mutableStateOf(initial?.name.orEmpty()) }
    var phone by remember(initial?.id) { mutableStateOf(initial?.phone.orEmpty()) }
    val valid = name.trim().isNotEmpty() && phone.trim().length >= 3
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "NOVO CONTATO" else "EDITAR CONTATO") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(60) }, label = { Text("Nome / setor") }, singleLine = true)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(phone, { phone = it.take(25) }, label = { Text("Telefone") }, singleLine = true)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("CANCELAR") } },
        confirmButton = {
            Button(onClick = { onConfirm(name.trim(), phone.trim()) }, enabled = valid && !saving) {
                Text(if (saving) "SALVANDO…" else "SALVAR")
            }
        }
    )
}
