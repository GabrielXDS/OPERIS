package br.com.alertaequipe

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ShiftReportText {
    private val ptBr = Locale("pt", "BR")
    private fun dateLong(ms: Long): String {
        val raw = SimpleDateFormat("dd 'de' MMMM 'de' yyyy", ptBr).format(Date(ms))
        return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ptBr) else it.toString() }
    }
    private fun time(ms: Long): String = SimpleDateFormat("HH:mm", ptBr).format(Date(ms))

    private fun header(report: ShiftReport, teamName: String): List<String> {
        val s = report.shift
        val place = s.location.ifBlank { "Brasília-DF" }
        val brigade = s.participants.filter { it.operationalFunction == OperationalFunction.BRIGADISTA.name }
        val regular = brigade.filterNot { it.intermediate }
        val intermediate = brigade.filter { it.intermediate }
        return buildList {
            add("*$place, ${dateLong(s.startedAt)}*")
            if (s.company.isNotBlank()) add("Empresa: ${s.company}")
            add("Plantão: ${s.shiftLabel.ifBlank { "Plantão operacional" }}")
            if (teamName.isNotBlank()) {
                add("*$teamName:* " + regular.joinToString(" X ") { it.name }.ifBlank { "Equipe não informada" })
            }
            intermediate.forEach {
                val end = it.endedAt?.let(::time) ?: "em andamento"
                add("Brigadista intermediária: ${it.name} ${time(it.startedAt)} às $end")
            }
        }
    }

    private fun eventText(event: ShiftReportEvent): String {
        val base = when {
            event.description.isNotBlank() -> event.description
            event.title.isNotBlank() -> event.title
            else -> event.categoryLabel
        }.trim().trimEnd('.')
        val ref = event.reference.trim()
        val prefixed = if (ref.isNotBlank()) "De acordo com a $ref, $base" else base
        val action = event.actionText.trim()
        return buildString {
            append(prefixed)
            if (action.isNotBlank() && !prefixed.contains(action, ignoreCase = true)) {
                append(". ")
                append(action.trimEnd('.'))
            }
            if (isNotBlank() && !endsWith(".")) append(".")
        }.ifBlank { "Sem alterações" }
    }

    private fun section(number: Int, title: String, events: List<ShiftReportEvent>): List<String> {
        if (events.isEmpty()) return listOf("*$number° $title:* Sem alterações")
        return events.mapIndexed { index, event ->
            if (index == 0) "*$number° $title:* ${eventText(event)}"
            else "$number.$index ${eventText(event)}"
        }
    }

    private fun reportHeading(report: ShiftReport): String = if (report.shift.status == "ACTIVE" || report.shift.endedAt == null)
        "*RELATÓRIO PARCIAL — ATÉ O MOMENTO*" else "*RELATÓRIO FINAL DO PLANTÃO*"

    fun summary(report: ShiftReport, teamName: String): String {
        val rounds = report.events.filter { it.eventType == "ROUND" }
        val incidents = report.events.filter { it.eventType == "INCIDENT" }
        val h = header(report, teamName)
        return buildList {
            add(reportHeading(report))
            add(h.first())
            add("")
            addAll(section(3, "Rondas", rounds))
            addAll(section(4, "Ocorrências", incidents))
        }.joinToString("\n")
    }

    fun full(report: ShiftReport, teamName: String): String {
        val s = report.shift
        val rounds = report.events.filter { it.eventType == "ROUND" }
        val incidents = report.events.filter { it.eventType == "INCIDENT" }
        val opening = s.openingNotes.ifBlank {
            "Plantão recebido com todas as alterações e ordens em vigor."
        }
        val equipment = s.equipmentNotes.ifBlank { "Sem alterações" }
        val closing = s.closingNotes.ifBlank {
            if (s.endedAt != null) "Plantão encerrado às ${time(s.endedAt)}."
            else "Plantão em andamento."
        }
        return buildList {
            add(reportHeading(report))
            addAll(header(report, teamName))
            add("")
            add("*1° Início de Plantão:* $opening")
            add("*2° Equipamentos e acessórios:* $equipment")
            addAll(section(3, "Rondas", rounds))
            addAll(section(4, "Ocorrências", incidents))
            add("*5° Término do Plantão:* $closing")
            if (s.nextTeam.isNotBlank()) add("Equipe que recebeu: ${s.nextTeam}")
        }.joinToString("\n")
    }
}
