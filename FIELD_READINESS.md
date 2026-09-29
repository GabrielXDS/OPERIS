# OPERIS — Field Readiness

Atualizado em 26/09/2026.

## Estado: NO-GO (bloqueadores manuais restantes)

### Verde
- Backend: 118/118 testes automatizados passando.
- Android: `testDebugUnitTest` passando.
- Android: `assembleDebug` passando.
- APK debug instalada no Xiaomi: 4.2.2 / versionCode 19.
- Functions `createTeam`, `listMyTeams`, `upsertProfile` ativas.
- `createTeam` publicada com `ALERTA_ENV=pilot`; App Check inválido é registrado, mas não bloqueia a chamada no piloto.
- UID atual do Xiaomi não possui contador de rate-limit `createTeam`.
- Drawer usa o mesmo componente/tamanho/tint para os itens; Emergência mantém semântica própria sem dimensão especial.

### Bloqueadores para campo
- Conceder manualmente no Xiaomi: Notificações, Câmera e Microfone (HyperOS bloqueia `pm grant` via ADB).
- Executar criação de uma equipe nova pelo Xiaomi e confirmar retorno do código de convite.
- Testar entrada de pelo menos um segundo aparelho na equipe.
- Validar sirene emissor/receptor e silenciamento local.
- Validar Rádio/PTT com microfone concedido em dois aparelhos.
