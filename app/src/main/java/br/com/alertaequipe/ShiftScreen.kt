package br.com.alertaequipe

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Calendar
import java.util.TimeZone

@Composable
internal fun ShiftScreen(
    team: TeamMembership?, shift: Shift?, history: List<Shift>, loading: Boolean, message: String,
    onRefresh: () -> Unit,
    onStart: (String, String, String, String, String, String, String) -> Unit,
    onAddIntermediate: (String, Long, Long) -> Unit,
    onConfirmSecurityPosts: (String, String) -> Unit,
    onUpdateAgpPost: (String, AgpPost) -> Unit,
    onStartCoverage: (String, String, CoverageReason) -> Unit,
    onFinishCoverage: (String) -> Unit,
    onOpenReport: (Shift, ShiftReportKind) -> Unit,
    onFinish: (String, String) -> Unit
) {
    var company by remember { mutableStateOf("Universidade Paulista - UNIP") }
    var location by remember { mutableStateOf("Brasília-DF") }
    var label by remember { mutableStateOf("19 às 07 - Noturno") }
    var selectedAgpPost by remember { mutableStateOf("") }
    var next by remember { mutableStateOf("") }
    var closing by remember { mutableStateOf("Plantão entregue com todas as orientações e assinaturas de acordo.") }
    var intermediateName by remember { mutableStateOf("") }
    var intermediateStart by remember { mutableStateOf("19:00") }
    var intermediateEnd by remember { mutableStateOf("22:00") }
    var portariaName by remember { mutableStateOf("") }
    var batalhaoName by remember { mutableStateOf("") }

    val myFunction = team?.operationalFunction?.takeIf { it.isNotBlank() } ?: Local.operationalFunction
    val isAgp = myFunction == OperationalFunction.AGP.name
    val isVigilante = myFunction == OperationalFunction.VIGILANTE.name
    val isAdministrative = team?.role == "owner" || team?.role == "admin"
    val canConfirmSecurityPosts = isVigilante || isAdministrative
    val mustConfirmSecurityPosts = isVigilante && !isAdministrative
    val canManagePosts = isVigilante || isAdministrative
    val activeParticipants = shift?.participants?.filter { it.endedAt == null } ?: emptyList()
    val myParticipant = activeParticipants.firstOrNull { it.uid == Local.uid }

    LaunchedEffect(shift?.shiftId, shift?.securityPosts) {
        portariaName = shift?.securityPosts
            ?.firstOrNull { it.post == AgpPost.PORTARIA_VEICULOS.name }?.name.orEmpty()
        batalhaoName = shift?.securityPosts
            ?.firstOrNull { it.post == AgpPost.BATALHAO.name }?.name.orEmpty()
    }

    fun selectedPost(): String = if (isAgp) selectedAgpPost else ""

    fun clockMillis(value: String, after: Long? = null): Long? {
        val parts = value.trim().split(":"); if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null; val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        val anchor = shift?.officialStartAt ?: shift?.startedAt ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Sao_Paulo")).apply {
            timeInMillis = anchor; set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis < anchor) cal.add(Calendar.DAY_OF_MONTH, 1)
        if (after != null) while (cal.timeInMillis <= after) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("PLANTÃO", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(team?.displayName ?: "Equipe não selecionada", fontWeight = FontWeight.Bold)
        Text("${Local.name} · ${OperationalFunction.labelOf(myFunction)}", color = AppMuted)
        Spacer(Modifier.height(18.dp))

        if (shift == null) {
            Text("INICIAR PLANTÃO DA EQUIPE", fontWeight = FontWeight.Bold)
            Text("No piloto, você pode iniciar e testar a qualquer horário. Fora da escala oficial, o plantão será identificado como manual/teste.", color = AppMuted)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(company, { company = it }, label = { Text("Empresa") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(location, { location = it }, label = { Text("Local") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { label = it }, label = { Text("Horário / identificação") }, modifier = Modifier.fillMaxWidth())
            if (canConfirmSecurityPosts) {
                Spacer(Modifier.height(14.dp))
                Text("CONFIRMAÇÃO DOS POSTOS DA SEGURANÇA", fontWeight = FontWeight.Bold)
                Text(
                    if (mustConfirmSecurityPosts) "Confirme quem ficará em cada posto antes de assumir o plantão."
                    else "Você pode registrar quem ficará em cada posto neste plantão.",
                    color = AppMuted
                )
                OutlinedTextField(
                    portariaName,
                    { if (it.length <= 40) portariaName = it },
                    label = { Text("Portaria de Veículos — nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    batalhaoName,
                    { if (it.length <= 40) batalhaoName = it },
                    label = { Text("Batalhão — nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
            if (isAgp) {
                Spacer(Modifier.height(12.dp))
                AgpPostPicker(selectedAgpPost, enabled = !loading) { selectedAgpPost = it.name }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Atuação no plantão: móvel pelas edificações.", color = AppMuted)
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    onStart(
                        label, company, location, "", selectedPost(),
                        if (canConfirmSecurityPosts) portariaName.trim() else "",
                        if (canConfirmSecurityPosts) batalhaoName.trim() else ""
                    )
                },
                enabled = team != null && !loading &&
                    (!isAgp || selectedAgpPost.isNotBlank()) &&
                    (!mustConfirmSecurityPosts ||
                        (portariaName.trim().length >= 2 && batalhaoName.trim().length >= 2)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
            ) { Text("INICIAR E ASSUMIR PLANTÃO") }
        } else {
            ShiftSummaryCard(shift, activeParticipants)

            if (isAdministrative || myFunction == OperationalFunction.BRIGADISTA.name) {
                Spacer(Modifier.height(16.dp))
                Text("BRIGADISTA INTERMEDIÁRIA", fontWeight = FontWeight.Bold)
                Text("Registro nominal do plantão. Não cria usuário, acesso à equipe, rádio ou alertas.", color = AppMuted)
                OutlinedTextField(intermediateName, { if (it.length <= 40) intermediateName = it },
                    label = { Text("Nome") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(intermediateStart, { if (it.length <= 5) intermediateStart = it },
                        label = { Text("Início") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(intermediateEnd, { if (it.length <= 5) intermediateEnd = it },
                        label = { Text("Fim") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                val guestStart = clockMillis(intermediateStart)
                val guestEnd = guestStart?.let { clockMillis(intermediateEnd, it) }
                OutlinedButton(onClick = { if (guestStart != null && guestEnd != null) onAddIntermediate(intermediateName.trim(), guestStart, guestEnd) },
                    enabled = !loading && intermediateName.trim().length >= 2 && guestStart != null && guestEnd != null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("REGISTRAR PARTICIPAÇÃO INTERMEDIÁRIA") }
            }

            if (canConfirmSecurityPosts) {
                Spacer(Modifier.height(16.dp))
                Text("CONFIRMAÇÃO DOS POSTOS DA SEGURANÇA", fontWeight = FontWeight.Bold)
                Text("Vigilantes, administradores e o criador podem confirmar quem ficará em cada posto.", color = AppMuted)
                OutlinedTextField(
                    portariaName,
                    { if (it.length <= 40) portariaName = it },
                    label = { Text("Portaria de Veículos — nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                OutlinedTextField(
                    batalhaoName,
                    { if (it.length <= 40) batalhaoName = it },
                    label = { Text("Batalhão — nome") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                val lastConfirmation = shift.securityPosts.maxByOrNull { it.confirmedAt }
                lastConfirmation?.let {
                    Text("Última confirmação: ${it.confirmedByName} · ${it.formattedAt}", color = AppMuted)
                }
                OutlinedButton(
                    onClick = { onConfirmSecurityPosts(portariaName.trim(), batalhaoName.trim()) },
                    enabled = !loading && portariaName.trim().length >= 2 && batalhaoName.trim().length >= 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text(if (shift.securityPosts.size >= 2) "ATUALIZAR CONFIRMAÇÃO" else "CONFIRMAR POSTOS") }
            }

            if (canManagePosts) {
                val agps = activeParticipants.filter { it.operationalFunction == OperationalFunction.AGP.name }
                if (agps.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("GESTÃO DOS POSTOS DOS AGPs", fontWeight = FontWeight.Bold)
                    Text("Vigilantes, administradores e o criador da equipe podem redistribuir os postos. Toda mudança fica auditada.", color = AppMuted)
                    agps.forEach { agp ->
                        Surface(color = AppPanel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(agp.name, fontWeight = FontWeight.Bold)
                                Text("Atual: ${agp.postLabel}", color = AppMuted)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    AgpPost.entries.forEach { post ->
                                        OutlinedButton(
                                            onClick = { onUpdateAgpPost(agp.uid, post) },
                                            enabled = !loading && agp.post != post.name,
                                            modifier = Modifier.weight(1f)
                                        ) { Text(post.label) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            val activeCoverages = shift.coverages.filter { it.active }
            val agpsInShift = activeParticipants.filter { it.operationalFunction == OperationalFunction.AGP.name }
            val vigilantesInShift = activeParticipants.filter { it.operationalFunction == OperationalFunction.VIGILANTE.name }
            if (canManagePosts && agpsInShift.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("RENDIÇÃO TEMPORÁRIA DOS AGPs", fontWeight = FontWeight.Bold)
                Text("Use para janta ou necessidade operacional. A cobertura não altera o posto definitivo do AGP.", color = AppMuted)
                agpsInShift.forEach { agp ->
                    val coverage = activeCoverages.firstOrNull { it.targetUid == agp.uid }
                    Surface(color = AppPanel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${agp.name} · ${agp.postLabel}", fontWeight = FontWeight.Bold)
                            if (coverage != null) {
                                Text("Em cobertura por ${coverage.coveredByName} · ${coverage.reasonLabel}", color = AppMuted)
                                Text("Desde ${coverage.formattedStart}", color = AppMuted)
                                if (coverage.coveredByUid == Local.uid || isAdministrative) {
                                    OutlinedButton(onClick = { onFinishCoverage(coverage.coverageId) }, enabled = !loading,
                                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("ENCERRAR COBERTURA") }
                                }
                            } else {
                                val possibleVigilantes = if (isAdministrative) vigilantesInShift
                                    else listOfNotNull(myParticipant?.takeIf { it.operationalFunction == OperationalFunction.VIGILANTE.name })
                                if (possibleVigilantes.isEmpty()) Text("Nenhum Vigilante ativo para realizar a rendição.", color = AppMuted)
                                possibleVigilantes.forEach { vigilante ->
                                    Text("Vigilante: ${vigilante.name}", color = AppMuted)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { onStartCoverage(agp.uid, vigilante.uid, CoverageReason.JANTAR) },
                                            enabled = !loading, modifier = Modifier.weight(1f)) { Text("JANTA") }
                                        OutlinedButton(onClick = { onStartCoverage(agp.uid, vigilante.uid, CoverageReason.NECESSIDADE_OPERACIONAL) },
                                            enabled = !loading, modifier = Modifier.weight(1f)) { Text("NECESSIDADE") }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (shift.coverages.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("AUDITORIA DE RENDIÇÕES", fontWeight = FontWeight.Bold)
                shift.coverages.asReversed().take(12).forEach { coverage ->
                    val end = coverage.formattedEnd?.let { " até $it" } ?: " · em andamento"
                    Text("${coverage.formattedStart}$end · ${coverage.targetName} · ${coverage.postLabel}", color = AppMuted)
                    Text("Cobertura: ${coverage.coveredByName} · ${coverage.reasonLabel}", color = AppMuted)
                    Spacer(Modifier.height(6.dp))
                }
            }

            if (shift.postHistory.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text("AUDITORIA DE TROCAS DE POSTO", fontWeight = FontWeight.Bold)
                shift.postHistory.asReversed().take(12).forEach { item ->
                    Text("${item.formattedAt} · ${item.targetName}: ${item.fromLabel} → ${item.toLabel}", color = AppMuted)
                    Text("Alterado por ${item.editedByName}", color = AppMuted)
                    Spacer(Modifier.height(6.dp))
                }
            }

            Spacer(Modifier.height(18.dp))
            if (myParticipant == null) {
                Text("ENTRAR NESTE PLANTÃO", fontWeight = FontWeight.Bold)
                if (isAgp) {
                    Text("Informe o posto que você está assumindo neste turno.", color = AppMuted)
                    Spacer(Modifier.height(8.dp))
                    AgpPostPicker(selectedAgpPost, enabled = !loading) { selectedAgpPost = it.name }
                } else {
                    Text("Você entrará como ${OperationalFunction.labelOf(myFunction)} móvel.", color = AppMuted)
                }
                val securityConfirmed = shift.securityPosts.size >= 2
                Button(
                    onClick = {
                        onStart(
                            "", "", "", "", selectedPost(),
                            if (mustConfirmSecurityPosts && !securityConfirmed) portariaName.trim() else "",
                            if (mustConfirmSecurityPosts && !securityConfirmed) batalhaoName.trim() else ""
                        )
                    },
                    enabled = !loading &&
                        (!isAgp || selectedAgpPost.isNotBlank()) &&
                        (!mustConfirmSecurityPosts || securityConfirmed ||
                            (portariaName.trim().length >= 2 && batalhaoName.trim().length >= 2)),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 54.dp)
                ) { Text("ENTRAR NO PLANTÃO") }
            } else {
                Text("SEU REGISTRO", fontWeight = FontWeight.Bold)
                Text("${myParticipant.name} · ${myParticipant.functionLabel} · ${myParticipant.postLabel}", color = AppMuted)
                val lastActive = activeParticipants.size == 1
                if (lastActive) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(next, { next = it }, label = { Text("Equipe que receberá o plantão") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(closing, { closing = it }, label = { Text("Observação de encerramento") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                }
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { onFinish(if (lastActive) next else "", if (lastActive) closing else "") },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
                ) { Text(if (lastActive) "ENCERRAR PLANTÃO" else "ENCERRAR MINHA PARTICIPAÇÃO") }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("HISTÓRICO DE PLANTÕES", fontWeight = FontWeight.Bold)
        val closedHistory = history.filter { it.status == "CLOSED" }
        if (closedHistory.isEmpty()) {
            Text("Nenhum plantão encerrado registrado.", color = AppMuted)
        } else {
            closedHistory.take(20).forEach { past ->
                val hasBrigada = past.participants.any { it.operationalFunction == OperationalFunction.BRIGADISTA.name }
                val hasSeguranca = past.participants.any {
                    it.operationalFunction == OperationalFunction.VIGILANTE.name || it.operationalFunction == OperationalFunction.AGP.name
                }
                Surface(color = AppPanel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(past.shiftLabel.ifBlank { "Plantão operacional" }, fontWeight = FontWeight.Bold)
                        Text("${past.formattedStart} → ${past.formattedEnd ?: "encerrado"}", color = AppMuted)
                        if (hasBrigada) {
                            OutlinedButton(
                                onClick = { onOpenReport(past, ShiftReportKind.BRIGADA) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) { Text("RELATÓRIO DA BRIGADA") }
                        }
                        if (hasSeguranca) {
                            OutlinedButton(
                                onClick = { onOpenReport(past, ShiftReportKind.SEGURANCA) },
                                enabled = !loading,
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                            ) { Text("RELATÓRIO DA SEGURANÇA") }
                        }
                    }
                }
            }
        }

        if (loading) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        if (message.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text(message, color = if (message.startsWith("Erro")) AppRed else AppMuted)
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("ATUALIZAR") }
    }
}

@Composable
private fun AgpPostPicker(selected: String, enabled: Boolean, onSelect: (AgpPost) -> Unit) {
    Text("Posto assumido", fontWeight = FontWeight.Bold)
    AgpPost.entries.forEach { post ->
        FilterChip(
            selected = selected == post.name,
            onClick = { if (enabled) onSelect(post) },
            enabled = enabled,
            label = { Text(post.label) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
        )
    }
}

@Composable
private fun ShiftSummaryCard(shift: Shift, activeParticipants: List<ShiftParticipant>) {
    Surface(color = AppPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Text("PLANTÃO ATIVO", fontWeight = FontWeight.Bold, color = AppRadioAccent)
            Text(shift.shiftLabel.ifBlank { "Plantão operacional" }, fontWeight = FontWeight.Bold)
            Text("Início: ${shift.formattedStart}", color = AppMuted)
            Text(
                if (shift.officialStartAt != null) "Plantão oficial • 19:00–07:00"
                else "Plantão manual/teste • fora da escala oficial",
                color = if (shift.officialStartAt != null) AppRadioAccent else AppAmber
            )
            if (shift.company.isNotBlank()) Text(shift.company)
            if (shift.location.isNotBlank()) Text(shift.location, color = AppMuted)
            if (shift.securityPosts.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("Postos da Segurança", fontWeight = FontWeight.Bold)
                shift.securityPosts.forEach { post ->
                    Text("• ${post.postLabel}: ${post.name}")
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Equipe em serviço", fontWeight = FontWeight.Bold)
            if (activeParticipants.isEmpty()) Text("Nenhum integrante ativo.", color = AppMuted)
            activeParticipants.forEach { member ->
                Text("• ${member.name} · ${member.functionLabel} · ${member.postLabel}")
            }
        }
    }
}

@Composable
internal fun ShiftReportScreen(
    report: ShiftReport?, kind: ShiftReportKind, teamName: String, loading: Boolean, message: String, onBack: () -> Unit
) {
    val context = LocalContext.current
    fun shareText(text: String, title: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, title)
        }
        context.startActivity(Intent.createChooser(intent, title))
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        TextButton(onClick = onBack, enabled = !loading) { Text("‹ VOLTAR") }
        Text(kind.label.uppercase(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))

        if (loading && report == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
        if (message.isNotBlank()) Text(message, color = if (message.startsWith("Erro")) AppRed else AppMuted)

        report?.let { data ->
            val shift = data.shift
            val securityFunctions = setOf(OperationalFunction.VIGILANTE.name, OperationalFunction.AGP.name)
            val participants = shift.participants.filter { member ->
                if (kind == ShiftReportKind.BRIGADA) member.operationalFunction == OperationalFunction.BRIGADISTA.name
                else member.operationalFunction in securityFunctions
            }
            // The backend filters by immutable ownerSector and authorizes the selected report.
            val events = data.events
            val incidentCount = events.count { it.eventType == "INCIDENT" }
            val roundCount = events.count { it.eventType == "ROUND" }
            val summaryText = ShiftReportText.summary(data, teamName)
            val fullText = ShiftReportText.full(data, teamName)
            Surface(color = AppPanel, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(shift.shiftLabel.ifBlank { "Plantão operacional" }, fontWeight = FontWeight.Bold)
                    Text("${shift.formattedStart} → ${shift.formattedEnd ?: "em andamento"}", color = AppMuted)
                    if (shift.company.isNotBlank()) Text(shift.company)
                    if (shift.location.isNotBlank()) Text(shift.location, color = AppMuted)
                    Spacer(Modifier.height(8.dp))
                    Text("$incidentCount ocorrência(s) · $roundCount ronda(s)", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("RESUMO PARA COMPARTILHAR", fontWeight = FontWeight.Bold)
            Surface(color = AppPanel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(summaryText, modifier = Modifier.padding(14.dp))
            }
            Button(onClick = { shareText(summaryText, "Resumo do plantão") },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).heightIn(min = 52.dp)) {
                Text("COMPARTILHAR RESUMO")
            }
            OutlinedButton(onClick = { shareText(fullText, "Relatório completo do plantão") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 52.dp)) {
                Text("COMPARTILHAR RELATÓRIO COMPLETO")
            }

            Spacer(Modifier.height(20.dp))
            Text(if(kind==ShiftReportKind.BRIGADA) "EQUIPE DA BRIGADA" else "EQUIPE DA SEGURANÇA", fontWeight = FontWeight.Bold)
            participants.forEach { member ->
                Text("• ${member.name} · ${member.functionLabel} · ${member.postLabel}")
            }
            if(kind==ShiftReportKind.SEGURANCA) {
                val responsible=participants.firstOrNull { it.operationalFunction==OperationalFunction.VIGILANTE.name }
                responsible?.let { Text("Vigilante responsável: ${it.name}", color = AppMuted) }
            }

            if (kind==ShiftReportKind.SEGURANCA && shift.postHistory.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("ALTERAÇÕES DE POSTO", fontWeight = FontWeight.Bold)
                shift.postHistory.forEach { item ->
                    Text("${item.formattedAt} · ${item.targetName}: ${item.fromLabel} → ${item.toLabel}", color = AppMuted)
                    Text("Responsável: ${item.editedByName}", color = AppMuted)
                    Spacer(Modifier.height(5.dp))
                }
            }

            if (kind==ShiftReportKind.SEGURANCA && shift.coverages.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("RENDIÇÕES / COBERTURAS", fontWeight = FontWeight.Bold)
                shift.coverages.forEach { coverage ->
                    val end = coverage.formattedEnd?.let { " → $it" } ?: " → em andamento"
                    Text("${coverage.formattedStart}$end · ${coverage.targetName} · ${coverage.postLabel}", color = AppMuted)
                    Text("Cobertura por ${coverage.coveredByName} · ${coverage.reasonLabel}", color = AppMuted)
                    Spacer(Modifier.height(5.dp))
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(if(kind==ShiftReportKind.BRIGADA) "REGISTROS DA BRIGADA" else "REGISTROS DA SEGURANÇA", fontWeight = FontWeight.Bold)
            if (events.isEmpty()) {
                Text("Nenhum registro operacional neste relatório.", color = AppMuted)
            } else {
                events.forEach { event ->
                    Surface(color = AppPanel, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Text("${event.formattedTime} · ${event.kindLabel}", color = AppRadioAccent, fontWeight = FontWeight.Bold)
                            Text(event.title, fontWeight = FontWeight.Bold)
                            if (event.categoryLabel.isNotBlank()) Text(event.categoryLabel, color = AppMuted)
                            if (event.location.isNotBlank()) Text("Local: ${event.location}")
                            if (event.reference.isNotBlank()) Text("Referência: ${event.reference}", color = AppRadioAccent)
                            if (event.authorName.isNotBlank()) Text("Registrado por ${event.authorName}", color = AppMuted)
                            if (event.description.isNotBlank()) {
                                Spacer(Modifier.height(5.dp)); Text(event.description)
                            }
                            if (event.actionText.isNotBlank()) Text("Ação: ${event.actionText}", color = AppMuted)
                            if (event.attachmentCount > 0) Text("${event.attachmentCount} anexo(s)", color = AppMuted)
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("RELATÓRIO DE ENCERRAMENTO", fontWeight = FontWeight.Bold)
            Text(shift.closingNotes.ifBlank { "Plantão encerrado sem observação adicional." })
            if (shift.nextTeam.isNotBlank()) Text("Equipe que recebeu: ${shift.nextTeam}", color = AppMuted)
            if (shift.openingNotes.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("Registro de abertura", fontWeight = FontWeight.Bold)
                Text(shift.openingNotes, color = AppMuted)
            }
        }
    }
}
