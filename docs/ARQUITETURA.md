# Arquitetura

## Fluxo

```text
A confirma → callable triggerAlert (Auth + App Check)
           → transação: valida membership/equipe/dispositivo + cooldown
           → alerts/{UUID} + lastAlertAt
           → trigger Firestore distributeAlert, com retry
           → consulta somente devices da equipe, ativos, exceto A
           → FCM data high, TTL 60 s
B/C recebem → validam equipe, origem, timestamp e alertId
            → deduplicam persistindo localmente
            → notificação + foreground mediaPlayback + sirene/vibração
SILENCIAR B → stopService local + cancel notification
             C permanece ativo
```

O dispositivo não escolhe teamId nem destinatários no disparo. Nome, equipe, UID e horário oficiais são definidos pelo servidor. O cliente envia somente UUID v4 para idempotência.

O UUID pendente é mantido localmente em caso de erro/timeout para uma repetição não criar outro alerta. O trigger de distribuição pode executar mais de uma vez; repetição do mesmo alertId é ignorada pelo receptor. Deduplicação mantém IDs recebidos nos últimos dois minutos; payloads expiram antes.

## Firestore

- `teams/{teamId}`: enabled, deviceCount.
- `teams/{teamId}/devices/{uid}`: deviceId, name, fcmToken, enabled, lastSeen, lastAlertAt.
- `members/{uid}`: teamId, enabled — vínculo confiável, somente backend/admin.
- `invites/{sha256(code)}`: teamId, enabled, expiresAt.
- `enrollmentAttempts/{uid}`: at — cinco segundos entre tentativas.
- `alerts/{alertId}`: alertId, teamId, senderDeviceId, senderName, createdAt, status, acceptedByFcm, failed.

Status: queued, submitted, retrying, expired, cancelled ou no-recipients.
Os contadores FCM representam a última tentativa. Não se equiparam a entrega/áudio confirmado.

Rules negam toda leitura/escrita de clientes. Admin SDK nas Functions usa identidade do serviço. Acesso administrativo deve ser protegido via IAM. Não exponha callable com App Check desativado em produção.

## Confiabilidade e falhas

A criação do alerta é transacional. O envio é desacoplado por evento persistente: uma falha depois da resposta HTTP pode ser repetida pelo trigger. Erros transitórios do FCM causam retry até a validade; tokens inválidos são removidos apenas se ainda forem iguais aos tokens usados no envio.

Existe uma janela inevitável entre backend/FCM/receptor: não há entrega exatamente uma vez garantida. O receptor deduplica e evita alertas atrasados. O app mantém um único serviço local, e um alerta novo atualiza a origem visível. Silenciar não realiza chamada ao servidor.

O limite de 100 dispositivos permite uma única operação multicast (limite FCM 500). Não ultrapasse a capacidade por edições administrativas sem implementar batches. Quando não há receptores, o registro fica no-recipients; o cliente ainda recebeu aceitação, não entrega.

## Operação

Monitore logs/erros das Functions, status dos alertas e registros sem destinatários. Defina retenção/limpeza de alerts e enrollmentAttempts conforme sua política; o MVP não apaga esses registros automaticamente. Revogue dispositivos perdidos e convites expostos. O script de revogação desativa membership e dispositivo em uma transação.

App Check reduz abuso de clientes não autorizados, mas não substitui o segredo do convite nem controles de IAM. Reinstalações não devem ser usadas para contornar limites: a equipe possui teto e código forte.

