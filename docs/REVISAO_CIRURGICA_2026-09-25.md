# OPERIS — revisão cirúrgica de 25/09/2026

Projeto: outputs/AlertaEquipe-v0.3-codex. Versão mantida: 4.2.1 / 18. Sem deploy ou release de produção.

## Correções

- Setores: registros sem ownerSector deixavam leitura aberta; agora o servidor usa o cargo de autoria salvo quando disponível e, sem setor identificável, permite somente autor/admin/owner. Criação sem cargo válido é recusada.
- Contadores: endpoints legados ignoravam setor, e cargo ausente abria a contagem global. Ambos usam a mesma autorização das fichas.
- Anexos: download/upload/finalização verificavam apenas equipe; passaram a exigir acesso à ficha por setor/autor/admin.
- Paginação: a última consulta podia conter mais itens autorizados que o limite e retornar cursor nulo, descartando a continuação. O cursor agora considera os itens excedentes.
- Plantão: entrada rejeita posto ocupado; primeiras entradas concorrentes serializam no documento da equipe. Postos de Brigadista/Vigilante permanecem vazios.
- Trocas: preservação do histórico completo, sem corte silencioso após 200 alterações. Permuta existente entre AGPs mantém unicidade e auditoria dos dois alvos. Troca durante cobertura é recusada.
- Cobertura: um Vigilante não pode cobrir simultaneamente dois postos; histórico não é truncado. Não altera o posto permanente. Repetir encerramento de participação já encerrada é recusado.
- Relatório: separação por setor e autorização realizadas no servidor, inclusive participantes, eventos, totais, trocas e coberturas. Compartilhamento não transfere registros entre relatórios. Android envia o setor escolhido.

## Verificações sem mudança de regra

Cargo permanente já era validado transacionalmente nos dois caminhos de cadastro. Testados todos os pares BRIGADISTA/VIGILANTE/AGP e payload inválido. Criar ocorrência/ronda já exigia shiftId, plantão ACTIVE e participação ativa; adicionada regressão. Compartilhar já preservava autoria, era idempotente e notificava; edição mantém setores compartilhados e updatedBy. Validados com mensageria simulada e Firestore Emulator, sem FCM real.

## Arquivos

Código de produção alterado:
- backend/src/incidents.js
- backend/src/records.js
- backend/src/shifts.js
- backend/src/attachments.js (necessário para fechar acesso indireto às fichas)
- app/src/main/java/br/com/alertaequipe/Backend.kt
- app/src/main/java/br/com/alertaequipe/MainActivity.kt
- app/src/main/java/br/com/alertaequipe/ShiftScreen.kt

Testes/execução:
- backend/test/incidents.test.js
- backend/test/attachments.test.js
- backend/test/records.test.js
- backend/test/operational.emulator.js (novo)
- backend/package.json: comando test:operational

Fixtures existentes passaram a declarar cargo/setor/autor válidos; suas verificações originais foram preservadas. A injeção opcional de messaging em incidentOperations permite testar notificações sem enviá-las. Em produção continua usando getMessaging.

## Testes

Baseline anterior às mudanças: 113/113 aprovados.
Após as mudanças: 118/118 testes unitários/contratos + 12/12 integrações operacionais = 130/130 backend.

Os 12 testes adicionais de integração usam exclusivamente demo-alerta-equipe em 127.0.0.1:18080; incluem concorrência real de transações, cargos, plantão obrigatório, postos, auditoria, cobertura, setores, paginação, contadores, anexos, compartilhamento/edição e relatórios. Os dados criados e removidos nesses testes são somente fixtures do emulador local.

Comandos reproduzíveis no diretório backend:

```powershell
node --test test/*.test.js
$env:GCLOUD_PROJECT='demo-alerta-equipe'
$env:GOOGLE_CLOUD_PROJECT='demo-alerta-equipe'
$env:FIRESTORE_EMULATOR_HOST='127.0.0.1:18080'
$env:GCE_METADATA_HOST='127.0.0.1:1'
npm run test:operational
```

O emulador deve estar iniciado com firestore.rules locais. Nenhuma regra ou índice foi alterado.

## Limites restantes

- Validar em aparelhos o acesso aos relatórios selecionados e as notificações de compartilhamento/edição. Os testes não enviaram FCM real.
- As notificações de compartilhar/editar continuam no mecanismo preexistente de melhor esforço: falha FCM é registrada, sem outbox/retry durável. Alterar esse mecanismo exigiria trabalho além desta revisão cirúrgica.
- Registros históricos sem setor nem cargo confiável ficam restritos ao autor/admin/owner; não houve migração.
- Mantidos o limite preexistente de 500 ocorrências/500 rondas por relatório e o armazenamento do histórico no documento de plantão. Histórico muito grande permanece sujeito ao limite de tamanho do Firestore; não houve mudança de arquitetura.
