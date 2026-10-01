package br.com.alertaequipe

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ProfileScreen(busy: Boolean, message: String,
    onContinue: (String) -> Unit, onRecover: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(Local.name) }
    var recovery by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    ScreenColumn {
        Spacer(Modifier.height(32.dp))
        SectionHeader("OPERIS", BRAND_TAGLINE)
        Text(if(recovery) "Recuperar conta" else "Como você se chama?", fontSize = 24.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(20.dp))
        if(recovery) {
            OutlinedTextField(email,{email=it},label={Text("E-mail")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(password,{password=it},label={Text("Senha")},singleLine=true,modifier=Modifier.fillMaxWidth(),visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation())
            Button(onClick={onRecover(email,password)},enabled=!busy && email.isNotBlank() && password.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(top=20.dp)) { Text(if(busy) "ENTRANDO…" else "ENTRAR") }
        } else {
            OutlinedTextField(name,{name=it.take(40)},label={Text("Seu nome")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("O cargo será escolhido ao entrar ou criar uma equipe.", color = AppMuted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp))
            Button(onClick={onContinue(name.trim())}, enabled=!busy && name.isNotBlank(),
                modifier=Modifier.fillMaxWidth().padding(top=20.dp)) { Text(if(busy) "SALVANDO…" else "CONTINUAR") }
        }
        TextButton(enabled=!busy,onClick={recovery=!recovery}) { Text(if(recovery) "CRIAR PERFIL NOVO" else "RECUPERAR CONTA") }
        Text(message,color=AppMuted)
    }
}

@Composable
internal fun TeamFormScreen(create: Boolean, busy: Boolean, message: String,
    preview: InvitePreview?, onSubmit: (String, OperationalFunction?) -> Unit, onConfirm: (OperationalFunction) -> Unit,
    onEdit: () -> Unit, onBack: () -> Unit) {
    var input by rememberSaveable(create) { mutableStateOf("") }
    var selectedFunction by rememberSaveable(create, preview?.teamId) { mutableStateOf("") }
    fun selected(): OperationalFunction? = runCatching { OperationalFunction.valueOf(selectedFunction) }.getOrNull()
    @Composable fun RolePicker() {
        Text("Seu cargo nesta equipe", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 14.dp))
        Text("Escolha agora. Depois, somente o proprietário da equipe poderá alterar seu cargo.", color = AppMuted, fontSize = 12.sp)
        OperationalFunction.entries.forEach { option ->
            FilterChip(selected=selectedFunction==option.name,onClick={if(!busy)selectedFunction=option.name},
                label={Text(option.label)},modifier=Modifier.fillMaxWidth().padding(vertical=2.dp))
        }
    }
    ScreenColumn {
        TextButton(onClick=onBack) { Text("VOLTAR") }
        SectionHeader(if(create) "CRIAR EQUIPE" else "ENTRAR EM EQUIPE")
        if(preview != null && !create) {
            Text("Equipe encontrada",color=AppMuted)
            Text(preview.teamName,fontSize=25.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onBackground)
            Text("Convite válido até "+date(preview.expiresAt),color=AppMuted)
            Text("Sua entrada depende da aprovação da administração.",color=AppMuted)
            RolePicker()
            Button(onClick={selected()?.let(onConfirm)},enabled=!busy && selected()!=null,
                modifier=Modifier.fillMaxWidth().padding(top=20.dp)) { Text("SOLICITAR ENTRADA") }
            TextButton(onClick=onEdit,enabled=!busy) { Text("ALTERAR CÓDIGO") }
        } else {
            OutlinedTextField(input,{input=it.take(if(create)60 else 32)},singleLine=true,
                label={Text(if(create) "Nome da equipe" else "Código de convite")},modifier=Modifier.fillMaxWidth())
            if(create) RolePicker()
            Button(onClick={onSubmit(input, if(create) selected() else null)},
                enabled=!busy && (if(create) input.isNotBlank() && selected()!=null else Operational.validInvite(input)),
                modifier=Modifier.fillMaxWidth().padding(top=20.dp)) {
                Text(if(busy) "AGUARDE…" else if(create) "CRIAR EQUIPE" else "BUSCAR EQUIPE")
            }
        }
        Text(message,color=AppMuted)
    }
}
@Composable
internal fun TeamsScreen(memberships: List<TeamMembership>, selectedTeamId: String?,
    details: Map<String,TeamDetails>, refreshing: Boolean, message: String, requests: List<MyRequest>,
    onSelect: (String)->Unit, onOpen:(String)->Unit, onCreate:()->Unit, onJoin:()->Unit, onRefresh:()->Unit) {
    ScreenColumn {
        SectionHeader("MINHAS EQUIPES","Selecione o destino do alerta. Você recebe alertas de todas as suas equipes.")
        Text("Online significa comunicação recente do aplicativo, não garantia de entrega.",color=AppMuted,fontSize=12.sp)
        TextButton(onClick=onRefresh,enabled=!refreshing) { Text(if(refreshing) "ATUALIZANDO…" else "ATUALIZAR") }
        if(message.isNotBlank())Text(message,color=AppMuted)
        memberships.forEach { team ->
            Surface(color=AppPanel,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(team.displayName,fontSize=21.sp,fontWeight=FontWeight.Bold)
                    Text(if(team.role=="owner") "Proprietário" else "Membro",color=AppMuted)
                    details[team.teamId]?.let { Summary(it) } ?: Text("Dados operacionais ainda não confirmados",color=AppMuted)
                    if(team.teamId==selectedTeamId)Text("✓ Selecionada",color=Color(0xFF69DAB1))
                    Row {
                        TextButton(onClick={onOpen(team.teamId)}) { Text("ABRIR EQUIPE") }
                        if(team.teamId!=selectedTeamId)TextButton(onClick={onSelect(team.teamId)}) { Text("SELECIONAR") }
                    }
                }
            }
        }
        requests.forEach { request ->
            Surface(color=AppPanel,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text(request.teamName,fontWeight=FontWeight.Bold)
                    Text(if(request.status=="pending") "Aguardando aprovação" else "Solicitação recusada",color=AppMuted)
                    Text("Solicitada em "+date(request.requestedAt),color=AppMuted,fontSize=12.sp)
                }
            }
        }
        if(memberships.isEmpty())Text("Crie uma equipe ou solicite entrada. Após a aprovação, a equipe aparecerá aqui.",color=AppMuted)
        OutlinedButton(onClick=onCreate,modifier=Modifier.fillMaxWidth().padding(top=16.dp)) { Text("CRIAR UMA EQUIPE") }
        OutlinedButton(onClick=onJoin,modifier=Modifier.fillMaxWidth()) { Text("ENTRAR COM CÓDIGO") }
    }
}
@Composable
private fun Summary(team: TeamDetails) {
    Spacer(Modifier.height(8.dp))
    Text(team.total.toString()+" integrantes",fontWeight=FontWeight.Bold,
        color=MaterialTheme.colorScheme.onSurface)
    Text(team.online.toString()+" online • "+team.paused+" em pausa • "+team.offline+" offline",color=AppMuted,fontSize=13.sp)
    Text(team.ready.toString()+" dispositivos prontos com comunicação recente",color=AppMuted,fontSize=13.sp)
}
@Composable
internal fun TeamDetailsScreen(team: TeamDetails?, created: Boolean, message:String, refreshing:Boolean,
    onRefresh:()->Unit,onBack:()->Unit,onCopy:(String)->Unit,onShare:(String,String)->Unit,onInvite:(String)->Unit,
    busy:Boolean,onReview:(String,Boolean)->Unit,onEditFunction:(MemberStatus,OperationalFunction)->Unit,
    onSaveSchedule:(String)->Unit,onRemoveMember:(MemberStatus)->Unit,onDissolve:()->Unit) {
    var dissolveOpen by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<MemberStatus?>(null) }
    var editFunctionTarget by remember { mutableStateOf<MemberStatus?>(null) }
    var editFunctionChoice by remember { mutableStateOf("") }
    var scheduleStart by remember(team?.teamId,team?.shiftSchedule?.startTime) { mutableStateOf(team?.shiftSchedule?.startTime ?: "19:00") }
    ScreenColumn {
        TextButton(onClick=onBack) { Text("VOLTAR") }
        if(created)Text("EQUIPE CRIADA",color=Color(0xFF69DAB1),fontWeight=FontWeight.Bold)
        if(created)TextButton(onClick=onRefresh) { Text("VER EQUIPE") }
        SectionHeader(team?.name ?: "DETALHES DA EQUIPE")
        TextButton(onClick=onRefresh,enabled=!refreshing) { Text(if(refreshing) "ATUALIZANDO…" else "ATUALIZAR") }
        if(message.isNotBlank())Text(message,color=AppMuted)
        if(team==null) { Text("Aguardando dados da equipe.",color=AppMuted); return@ScreenColumn }
        Summary(team)
        Text("Seu papel: "+(if(team.myRole=="owner") "Proprietário" else if(team.myRole=="admin") "Administrador" else "Membro"),color=AppMuted)
        Text("Escala da equipe: ${team.shiftSchedule.startTime} - ${team.shiftSchedule.endTime} | 12x36",color=AppMuted)
        if(team.myRole=="owner" || team.myRole=="admin") {
            Spacer(Modifier.height(20.dp))
            SectionHeader("ESCALA DA EQUIPE")
            Text("Referência 12x36. O horário real de início do plantão continua livre; o OPERIS usa esta configuração para calcular o encerramento automático.",color=AppMuted,fontSize=12.sp)
            OutlinedTextField(scheduleStart,{if(it.length<=5)scheduleStart=it},label={Text("Horário-base de início (HH:mm)")},singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=8.dp))
            val calculatedEnd=TeamShiftSchedule.calculatedEnd(scheduleStart)
            Text("12h de serviço | 36h de descanso"+(calculatedEnd?.let{" | término calculado: $it"}?:""),color=AppMuted,fontSize=12.sp)
            Button(onClick={onSaveSchedule(scheduleStart)},enabled=!busy && calculatedEnd!=null && scheduleStart!=team.shiftSchedule.startTime,modifier=Modifier.fillMaxWidth().padding(top=10.dp)) { Text("SALVAR ESCALA 12X36") }
        }
        if(team.myRole=="owner") {
            Spacer(Modifier.height(20.dp))
            Text("Código de convite",fontWeight=FontWeight.Bold,
            color=MaterialTheme.colorScheme.onSurface)
            val invite=team.invite
            if(invite!=null) {
                Text(Operational.formatInvite(invite.code),fontSize=26.sp,fontWeight=FontWeight.Bold,
                    color=MaterialTheme.colorScheme.onBackground)
                Text(if(invite.active) "Válido até "+date(invite.expiresAt) else "Convite expirado ou revogado",color=AppMuted)
                OutlinedButton(onClick={onCopy(invite.code)},enabled=invite.active) { Text("COPIAR CÓDIGO") }
                OutlinedButton(onClick={onShare(team.name,invite.code)},enabled=invite.active) { Text("COMPARTILHAR CONVITE") }
            }
            TextButton(onClick={onInvite("rotate")}) { Text("GERAR NOVO CONVITE") }
            if(invite?.active==true)TextButton(onClick={onInvite("revoke")}) { Text("REVOGAR CONVITE") }
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick={dissolveOpen=true},enabled=!busy,modifier=Modifier.fillMaxWidth(),
                colors=ButtonDefaults.outlinedButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("DESFAZER EQUIPE") }
        }
        Spacer(Modifier.height(24.dp))
        if(team.myRole=="owner" || team.myRole=="admin") {
            SectionHeader("SOLICITAÇÕES PENDENTES")
            if(team.requests.isEmpty())Text("Nenhuma solicitação pendente.",color=AppMuted)
            team.requests.forEach { request ->
                Surface(color=AppPanel,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(vertical=6.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(request.name,fontWeight=FontWeight.Bold)
                        Text("Cargo solicitado: "+OperationalFunction.labelOf(request.operationalFunction),color=AppMuted)
                        Text(date(request.requestedAt),color=AppMuted)
                        Row {
                            TextButton(enabled=!busy,onClick={onReview(request.uid,true)}) { Text("APROVAR") }
                            TextButton(enabled=!busy,onClick={onReview(request.uid,false)}) { Text("RECUSAR") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        SectionHeader("INTEGRANTES")
        Text("Offline significa ausência de comunicação recente: o app pode estar fechado, restrito ou sem conexão. Pausa não bloqueia emergências.",
            color=AppMuted,fontSize=12.sp)
        team.members.forEach { member ->
            val color = memberStatusColor(member.status, member.pauseReason)
            Surface(color=AppPanel,shape=RoundedCornerShape(14.dp),modifier=Modifier.fillMaxWidth().padding(top=10.dp)) {
                Row(Modifier.padding(16.dp)) {
                    Box(Modifier.padding(top=5.dp).size(10.dp).background(color,CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(member.name,fontWeight=FontWeight.Bold)
                        Text("Cargo: "+OperationalFunction.labelOf(member.operationalFunction),color=AppMuted,fontSize=12.sp)
                        Text(memberStatusText(member.status, member.pauseReason),color=color)
                        Text(if(member.appReady) "App pronto na última comunicação" else "Preparação não confirmada",color=AppMuted,fontSize=12.sp)
                        if(Operational.canViewLastCommunication(team.myRole))
                            Text("Última comunicação: "+date(member.lastSeenAt),color=AppMuted,fontSize=12.sp)
                        val canRemove = member.uid != Local.uid && when(team.myRole) {
                            "owner" -> member.role != "owner"
                            "admin" -> member.role == "member"
                            else -> false
                        }
                        if(team.myRole=="owner" && member.uid!=Local.uid && member.role!="owner") {
                            TextButton(onClick={editFunctionTarget=member;editFunctionChoice=member.operationalFunction},enabled=!busy) {
                                Text("EDITAR CARGO")
                            }
                        }
                        if(canRemove) TextButton(onClick={removeTarget=member},enabled=!busy) {
                            Text("REMOVER DA EQUIPE",color=MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Text("Dados obtidos em "+date(team.serverNow)+". Prontidão não garante entrega.",color=AppMuted,fontSize=12.sp)
    }
    editFunctionTarget?.let { target ->
        AlertDialog(
            onDismissRequest={if(!busy)editFunctionTarget=null},
            title={Text("Editar cargo")},
            text={Column {
                Text(target.name,fontWeight=FontWeight.Bold)
                Text("Somente o proprietário pode alterar o cargo depois da entrada na equipe.",color=AppMuted,fontSize=12.sp)
                OperationalFunction.entries.forEach { option ->
                    FilterChip(selected=editFunctionChoice==option.name,onClick={if(!busy)editFunctionChoice=option.name},
                        label={Text(option.label)},modifier=Modifier.fillMaxWidth().padding(vertical=2.dp))
                }
            }},
            dismissButton={TextButton(enabled=!busy,onClick={editFunctionTarget=null}){Text("CANCELAR")}},
            confirmButton={Button(enabled=!busy && editFunctionChoice.isNotBlank() && editFunctionChoice!=target.operationalFunction,
                onClick={val chosen=OperationalFunction.valueOf(editFunctionChoice);editFunctionTarget=null;onEditFunction(target,chosen)}){Text("SALVAR")}}
        )
    }
    removeTarget?.let { target ->
        AlertDialog(onDismissRequest={if(!busy)removeTarget=null},
            title={Text("Remover integrante")},
            text={Text("Remover ${target.name} da equipe? O acesso a alertas, rádio, rondas e ocorrências desta equipe será encerrado. O histórico já registrado será preservado.")},
            dismissButton={TextButton(enabled=!busy,onClick={removeTarget=null}){Text("CANCELAR")}},
            confirmButton={Button(enabled=!busy,onClick={removeTarget=null;onRemoveMember(target)},colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("REMOVER")}})
    }
    if(dissolveOpen) AlertDialog(
        onDismissRequest={dissolveOpen=false},
        title={Text("Desfazer equipe")},
        text={Text("Esta ação desativa a equipe, revoga o convite e remove os vínculos dos integrantes. O histórico operacional será preservado. Deseja continuar?")},
        dismissButton={TextButton(onClick={dissolveOpen=false}){Text("CANCELAR")}},
        confirmButton={Button(onClick={dissolveOpen=false;onDissolve()},enabled=!busy,colors=ButtonDefaults.buttonColors(containerColor=MaterialTheme.colorScheme.error)){Text("DESFAZER EQUIPE")}}
    )
}
private fun date(value:Long?):String = value?.let {SimpleDateFormat("dd/MM HH:mm:ss",Locale("pt","BR")).format(Date(it))} ?: "Não informada"

@Composable
internal fun StatusDialog(busy:Boolean,message:String,onDismiss:()->Unit,onStatus:(String,String?)->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text("MEU STATUS")},text={
        Column {
            Text("Pausa é informação operacional. Você continua recebendo emergências.",color=AppMuted)
            if(message.isNotBlank())Text(message,color=AppRed)
            StatusOption(Color(0xFF69DAB1),"Disponível",{onStatus("available",null)},busy)
            StatusOption(Color(0xFFFFCC80),"Em pausa",{onStatus("paused","break")},busy)
            StatusOption(AppRed,"Indisponível",{onStatus("paused","other")},busy)
        }
    },confirmButton={TextButton(onClick=onDismiss){Text("FECHAR")}})
}

@Composable
private fun StatusOption(color: Color, label: String, onClick: () -> Unit, enabled: Boolean) {
    TextButton(onClick = onClick, enabled = enabled) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

private fun memberStatusColor(status: String?, reason: String?): Color = when(status) {
    "online" -> Color(0xFF69DAB1)
    "paused" -> if (reason == "other") AppRed else Color(0xFFFFCC80)
    else -> AppRed
}

private fun memberStatusText(status: String?, reason: String?): String = when(status) {
    "online" -> "Disponível"
    "paused" -> Operational.statusLabel("paused", reason)
    else -> "Offline • Sem comunicação recente"
}
