# Etapa 1 — interface e preparação local para múltiplas equipes

## Entrega

Tela principal simplificada com status, identificação, equipe selecionada e botão ACIONAR ALERTA em destaque. Minhas Equipes usa uma lista de memberships, mas o adaptador atual apresenta somente a equipe realmente cadastrada. Criar/entrar em outra equipe exibem aviso de funcionalidade em preparação e não chamam o Firebase.

Configurações reúne permissões, notificações, som, Não Perturbe, teste local da sirene e diagnóstico. Os textos explicam a elevação temporária do volume de alarme e sua restauração ao silenciar/encerrar normalmente. A confirmação de disparo identifica a equipe. A emergência mostra a equipe do alerta recebido e o emissor.

## Arquivos criados

- app/src/main/java/br/com/alertaequipe/AppScreens.kt
- app/src/main/java/br/com/alertaequipe/Teams.kt
- app/src/main/java/br/com/alertaequipe/TeamPreferences.kt
- app/src/test/java/br/com/alertaequipe/TeamSelectionTest.kt
- app/src/test/java/br/com/alertaequipe/AlertDiagnosticsTest.kt
- docs/ETAPA_1_INTERFACE.md (este relatório)

## Arquivos existentes modificados

- app/src/main/java/br/com/alertaequipe/MainActivity.kt
- app/build.gradle.kts: somente inclusão de JUnit 4.13.2 para testes.

## Compatibilidade

A comparação SHA-256 de 23 arquivos originais auditados confirmou apenas MainActivity.kt e app/build.gradle.kts modificados. Permanecem preservados SirenService.kt, Backend.kt, Alerts.kt, Local.kt, AlertaApp.kt, AndroidManifest.xml, Cloud Functions, regras e arquivos de backend auditados.

Nenhuma migração ou alteração do Firebase publicado foi executada. UID, token, cadastro e teamId legado continuam sob o contrato existente. A sincronização de 30 segundos foi preservada para não alterar manutenção de cadastro/token nesta etapa.

selectedTeamId fica em SharedPreferences separadas, arquivo team_selection. A lista de memberships é uma projeção do cadastro legado, não uma nova autorização. A seleção inicial usa a equipe real e uma seleção inexistente volta para uma membership válida. Local.teamId não é sobrescrito. Enquanto o backend infere o destino legado, o botão só permite envio quando a seleção corresponde a esse destino.

O recebimento e a deduplicação não consultam selectedTeamId. O nome exibido na emergência vem de alert.team. A estrutura visual aceita listas, mas ainda não existe suporte efetivo de backend para várias equipes.

A assinatura do novo APK foi verificada e corresponde à do APK anterior. Isso preserva a compatibilidade de assinatura para atualização; a atualização física ainda não foi executada.

## Validação executada

- Backend: 5 testes unitários existentes aprovados.
- Backend: 6 testes de integração existentes aprovados no emulador local do Firestore, projeto demo-alerta-equipe. Nenhum acesso de teste ao Firestore publicado.
- Verificação de sintaxe de backend/src/index.js aprovada.
- Android: 10 testes de seleção/equipe e 3 testes de diagnóstico aprovados; zero falhas.
- :app:lintDebug: 0 erros e 33 avisos.
- assembleDebug: BUILD SUCCESSFUL.
- Execução final conjunta: :app:testDebugUnitTest :app:lintDebug assembleDebug, BUILD SUCCESSFUL em 2m 7s.
- JDK usado: C:\Users\Gabriel Ximenes\.jdks\jbr-21.0.11.
- Nenhum installDebug, deploy, presença, ACK ou backend multi-equipe executado.

APK: app/build/outputs/apk/debug/app-debug.apk.
Relatórios: app/build/reports/lint-results-debug.html e app/build/reports/tests/testDebugUnitTest/index.html.

## Avisos e ambiente

Os 33 avisos de Lint são: 5 ApplySharedPref, 1 ImplicitSamInstance, 7 GradleDependency, 1 DataExtractionRules, 1 ObsoleteSdkInt e 18 UseKtx. Incluem recomendações de estilo, versões de dependências e pontos em arquivos preservados. O alerta ImplicitSamInstance refere-se a stopService(Intent(...)) no fluxo existente; não houve alteração nesse código. Os commits síncronos do fluxo existente foram preservados.

A primeira compilação registrou dificuldades de acesso ao cache do daemon Kotlin e ao core-for-system-modules.jar do SDK, mas terminou com sucesso. A execução final usou compilação Kotlin no processo e diretórios de cache/home isolados, concluindo sem esses erros. A chave debug existente foi reutilizada para preservar assinatura, sem modificar código/configurações do Firebase. Uma tentativa intermediária falhou por interpretação de argumento -P pelo PowerShell; foi corrigida colocando o argumento entre aspas.

## Testes físicos pendentes

O fluxo anterior Renato → Gabriel é considerado funcional e validado pelo usuário. É necessário verificar a regressão desta nova interface quando a instalação for autorizada:

1. Atualizar por cima da instalação atual e verificar preservação de nome/cadastro/equipe nos dois aparelhos.
2. Conferir Home, Minhas Equipes e Configurações em ambos, com tamanhos de fonte maiores, rolagem, rotação e retorno.
3. Reiniciar o aplicativo e conferir seleção persistida; criar/entrar devem apenas apresentar aviso.
4. Confirmar que o diálogo de envio mostra Brigada 01 e cancelar não envia.
5. Renato aciona: Renato não toca; Gabriel recebe a emergência com equipe/emissor corretos, sirene e vibração.
6. Repetir com Gabriel em segundo plano, tela bloqueada e modo silencioso. Conferir elevação temporária do volume de alarme e restauração após SILENCIAR/encerramento normal.
7. Receber enquanto navega em Configurações/Minhas Equipes ou com diálogo aberto; emergência deve ter prioridade.
8. Teste local da sirene, permissões bloqueadas/restauradas e mensagens de configuração.
9. Perda/retorno da internet: status coerente, retomada da sincronização e envio existente sem duplicidade.

Os testes JVM e o build não substituem essa validação de hardware e interface.

