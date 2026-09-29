# Aceitação — executar em celulares reais

**Estado inicial: não executado nesta entrega.** Registre modelo, Android, build/assinatura, rede, horário e resultado. Use pelo menos dois aparelhos com Google Play services e um projeto Firebase configurado.

## Cenário obrigatório

1. Cadastre A e B na mesma equipe, permita notificações, ajuste volume de alarme e teste a sirene local.
2. A toca ACIONAR ALERTA e CANCELAR: nenhum aparelho toca.
3. A confirma ACIONAR: A não toca; B mostra origem A e toca/vibra.
4. B toca SILENCIAR: som/vibração param imediatamente.
5. B confirma ACIONAR: B não toca; A recebe e toca.
6. A silencia: continua apto a receber novo alerta.

Registre latência observada; não declare uma latência garantida com base em um único ensaio.

## Três aparelhos

1. A aciona, B e C tocam, A permanece sem som.
2. B silencia; C deve continuar tocando.
3. C silencia.
4. Novo alerta volta a tocar em B e C.
5. Sem silenciar, confirme parada automática em até dois minutos após recepção (um novo alerta pode renovar o prazo).

## Estados Android

Repetir recepção com Activity aberta, app em segundo plano, tela bloqueada, após reboot/desbloqueio, economia de bateria e Doze.
Verificar Android 8, 12, 13, 14 e 15 ou superior quando disponíveis.
Testar permissões negadas/revogadas, canal desativado, volume zero, Não Perturbe e áudio em chamada.
Forçar parada: confirmar limitação documentada e recuperação após abrir o app.
Quando foreground service for restringido, verificar fallback/notificação e botão ATIVAR SIRENE.
Testar fontes grandes, TalkBack, tela pequena e orientação paisagem.

## Isolamento/segurança

- Cadastre D em outra equipe: alerta de A não chega em D.
- Tentativa sem Auth/App Check é rejeitada.
- Código inválido, expirado ou desativado não cadastra.
- Membership/dispositivo/equipe revogados não disparam.
- Payload com teamId/tokens arbitrários não altera os destinatários.
- Escritas/leitura diretas de clientes no Firestore são negadas.
- Chamadas concorrentes do mesmo UID com IDs diferentes em menos de cinco segundos: uma é aceita.
- Repetir o mesmo UUID: um único documento e nenhuma nova sirene.
- Simular token inválido e rotação simultânea: token novo não deve ser apagado.

## Rede e falhas

- Sem internet: status desconectado, acionamento desabilitado.
- Retorno da internet: sincronização/token e status restaurados.
- B offline por mais de 60 segundos: alerta antigo não toca ao voltar.
- Timeout depois da aceitação: repetir com UUID pendente não duplica alerta.
- Reentrega do evento Firestore/FCM: receptor toca somente uma vez.
- TESTAR SIRENE em A: nenhum documento de alerta/push em B ou C.

## Registro

| Ensaio | Aparelhos / Android | Resultado | Latência | Observação |
| --- | --- | --- | --- | --- |
| A → B, silêncio local | | Pendente | | |
| B → A, silêncio local | | Pendente | | |
| A → B/C, independência | | Pendente | | |
| Tela bloqueada / background | | Pendente | | |
| Reboot e recuperação | | Pendente | | |
| Permissões / DND / volume | | Pendente | | |
| Isolamento de equipes | | Pendente | | |

