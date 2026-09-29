# Alerta Equipe — MVP Android

Aplicativo nativo Kotlin + Jetpack Compose e backend Firebase para equipes privadas de segurança e brigadistas.

**Situação da entrega:** código-fonte do MVP. Para usar entre celulares, é necessário configurar um projeto Firebase, publicar as Functions e as regras, gerar o APK e executar o teste físico descrito em `docs/ACEITACAO.md`. Não há credenciais administrativas no aplicativo. A configuração Firebase real não acompanha este projeto.

## O que está implementado

- Cadastro anônimo por instalação, nome amigável e código de ingresso.
- Identificador `deviceId` igual ao UID do Firebase Authentication. Permanece na instalação; limpar dados/reinstalar cria outra identidade.
- Tela principal com botão grande e confirmação de acionamento.
- Backend determina a equipe, identifica o emissor e exclui seu dispositivo.
- FCM de dados com prioridade alta e validade de 60 segundos.
- Sirene original incluída em `app/src/main/res/raw/siren.wav`, loop, vibração, AudioFocus e serviço foreground.
- Notificação de emergência com ação SILENCIAR e tela acessível ao tocar na notificação.
- Silenciamento estritamente local e limite de dois minutos por alerta.
- Deduplicação persistente, registro do último alerta, isolamento de equipes.
- Intervalo de cinco segundos no servidor, envio idempotente e outbox Firestore com repetição em falhas transitórias.
- Atualização de token com WorkManager, sincronização ao reconectar e verificação periódica.
- Aviso de notificações/canal/volume/Não Perturbe, configurações e teste somente local.
- Código de equipe aleatório com expiração, App Check obrigatório, tokens inválidos removidos com proteção contra corrida.

Não há GPS, mapa, câmera, microfone, chat ou painel administrativo.

## 1. Preparar Firebase

1. O projeto informado é **cirene-9d83e** e já está selecionado em `.firebaserc`. Confira faturamento compatível com Cloud Functions; acompanhe custos e configure alertas de orçamento.
2. Cadastre um app Android com pacote **br.com.alertaequipe**.
3. Nas [configurações de cirene-9d83e](https://console.firebase.google.com/project/cirene-9d83e/settings/general), baixe `google-services.json` do aplicativo Android e coloque em `app/google-services.json`. Esse arquivo contém configuração do cliente, não uma credencial administrativa.
4. Em Authentication, habilite o provedor **Anônimo**.
5. Crie o Cloud Firestore; escolha a localização antes de criar. As Functions usam **southamerica-east1**.
6. Configure App Check. Debug usa Debug Provider; obtenha o token de depuração no Logcat e cadastre-o no console. Nunca use debug tokens em release.
7. Release usa Play Integrity: configure o app, certificado SHA-256 e as políticas adequadas à distribuição. Para APK instalado fora do Google Play, revise os requisitos de reconhecimento/licenciamento do App Check; valide o APK assinado em aparelhos reais. Não remova App Check para contornar uma falha.
8. Instale Node.js 22 e Firebase CLI, faça `firebase login`.

Na raiz do projeto:

```powershell
Set-Location backend
npm install
npm test
Set-Location ..
firebase use cirene-9d83e
firebase deploy --only firestore:rules,firestore:indexes,functions
```

A implantação ocorre somente quando você executar esses comandos em seu projeto.

## 2. Criar uma equipe

O código de ingresso não é o teamId. Ele é um segredo aleatório de 48 caracteres hexadecimais, válido por sete dias. Somente seu hash é salvo no Firestore. Compartilhe pelo canal privado da equipe.

Em ambiente administrativo com Application Default Credentials, por exemplo após `gcloud auth application-default login`, configure seu projeto no ambiente e execute:

```powershell
$env:GOOGLE_CLOUD_PROJECT="cirene-9d83e"
Set-Location backend
node scripts/create-team.mjs equipe_unip_01
```

O script imprime o código. Execute novamente para gerar outro convite. Desative o convite antigo em `invites/{hash}` quando necessário. A equipe tem limite de 100 aparelhos neste MVP. Convites são reutilizáveis dentro do prazo; não são códigos de uso único.

Não copie chaves de conta de serviço para o aplicativo, repositório ou APK. Credenciais ADC são utilizadas somente nos scripts administrativos.

Para revogar um dispositivo:

```powershell
node scripts/revoke-device.mjs UID_DO_DISPOSITIVO
```

## 3. Abrir e gerar APK

Requisitos: Android Studio com suporte a AGP 8.9.2, **JDK 17**, Android SDK Platform 35 e Build Tools 35.0.0. O Gradle Wrapper é 8.11.1 e verifica o SHA-256 da distribuição. Android mínimo: 8.0/API 26, com Google Play services.

1. Abra esta pasta no Android Studio.
2. Selecione JDK 17 em Settings > Build Tools > Gradle.
3. Instale SDK 35 pelo SDK Manager.
4. Adicione o `app/google-services.json` real.
5. Aguarde o Gradle Sync e execute em um aparelho, ou:

```powershell
.\gradlew.bat :app:assembleDebug
```

Saída: `app/build/outputs/apk/debug/app-debug.apk`.

Para distribuição, use **Build > Generate Signed App Bundle / APK > APK**, crie/guarde um keystore particular e configure SHA-256/App Check para essa assinatura. O build release não contém debug provider. Não distribua debug APK como versão operacional.

No primeiro acesso, digite nome e código, conceda notificações e execute TESTAR SIRENE. Faça isso em cada aparelho.

## 4. Comportamento e limites

**“Alerta enviado” significa que o backend aceitou a solicitação. Não confirma que todos os celulares tocaram.** `acceptedByFcm` também não é comprovante de entrega. O MVP não implementa confirmação individual de recepção.

- “Sistema conectado” exige internet validada pelo Android e uma sincronização recente bem-sucedida com o backend; não é garantia de entrega futura.
- FCM não garante entrega instantânea. Rede, Doze, restrições do fabricante, bateria, notificações bloqueadas e Não Perturbe podem impedir/atrasar a recepção.
- Alertas com mais de 60 segundos são descartados para não produzir uma emergência antiga ao reconectar. Relógios dos aparelhos devem estar sincronizados.
- O Android pode reduzir a prioridade do FCM e impedir iniciar foreground service em background. Nesse caso há uma notificação sonora de fallback, quando permitida, e opção de ativar a sirene ao abrir.
- A Activity não é aberta à força. Não se pede permissão de tela cheia: este app não deve se fazer passar por um discador ou despertador. A tela de emergência aparece quando o app está aberto ou o usuário toca na notificação.
- O áudio usa o volume de **alarme**, respeita AudioFocus e não altera volume/Não Perturbe. O acesso a Não Perturbe é apresentado nas configurações, sem modificar a política do sistema.
- Perda de foco de áudio pode pausar a sirene. Teste chamadas e outros apps de áudio em cada modelo.
- Após reiniciar, FCM/WorkManager seguem seus mecanismos oficiais; não se inicia sirene no boot. Desbloqueie o aparelho antes de esperar recepção. Não há suporte a Direct Boot.
- Depois de “Forçar parada”, o usuário precisa abrir o app novamente. Não há tentativa de contornar isso.
- Um novo alerta substitui a identificação exibida e renova o limite de dois minutos. SILENCIAR interrompe todos os sons de emergência ativos somente naquele aparelho.
- Limpar dados/reinstalar cria nova identidade. Revogue o cadastro anterior.
- Não há histórico completo no app: mantém apenas último alerta e IDs recentes para deduplicação.

Use o roteiro físico antes de uso operacional; os testes de código não validam áudio/entrega em celulares.

## Organização

- `app/`: aplicativo Android.
- `backend/src/index.js`: cadastro, sincronização, disparo e distribuição.
- `backend/src/policy.js`: validação, destinatários, cooldown e validade.
- `backend/scripts/`: provisionamento/revogação.
- `firestore.rules`: clientes sem acesso direto; operações via backend.
- `docs/ARQUITETURA.md`: fluxo, dados e decisões.
- `docs/ACEITACAO.md`: testes físicos e segurança.
- `docs/VERIFICACAO.md`: verificações realizadas nesta entrega.

## Referências oficiais

- [Prioridade e possíveis restrições FCM](https://firebase.google.com/docs/cloud-messaging/android-message-priority)
- [Início de foreground services](https://developer.android.com/develop/background-work/services/fgs/launch)
- [Tipos de foreground service](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [App Check com Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
