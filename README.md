# OPERIS

**Comunicação, resposta e registro operacional para equipes de campo.**

OPERIS é um aplicativo Android desenvolvido em Kotlin e Jetpack Compose para apoiar equipes de Brigada, Vigilância e AGP durante o plantão. O projeto reúne alerta de emergência, rádio PTT, gestão de plantões, ocorrências, rondas, equipes e relatórios em uma única interface.

> Status atual: **piloto interno / desenvolvimento ativo**. O sistema já é utilizado em testes de campo controlados e continua sendo refinado a partir do uso real.

## Principais funcionalidades

- **Sirene de emergência** com notificação prioritária, tela de alerta e ação rápida para silenciar.
- **Rádio PTT** com canais por função e canal geral, baseado em LiveKit.
- **Plantões** com identificação de função, postos operacionais e histórico.
- **Equipes e cargos** para Brigadista, Vigilante, AGP, administrador e criador.
- **Ocorrências** com descrição, anexos, edição, compartilhamento e auditoria.
- **Rondas** integradas ao contexto da equipe, com irregularidades e anexos.
- **Relatório da Brigada** vinculado aos dados do plantão.
- **Atualização in-app** com validação SHA-256, versão do APK e fallback de instalação.
- **Diagnóstico do aparelho** para permissões, notificações, câmera, microfone e requisitos operacionais.
- **Firebase Auth, Firestore, Functions, FCM e App Check** no backend.

## Stack

- Kotlin + Jetpack Compose
- Android SDK 35 / minSdk 26
- Firebase Authentication
- Cloud Firestore
- Firebase Cloud Messaging
- Cloud Functions v2 / Node.js 22
- Firebase App Check
- WorkManager + Foreground Services
- LiveKit para PTT
- Coil para imagens
## Arquitetura resumida

```text
Android / OPERIS
      |
      +-- Firebase Auth
      +-- Cloud Functions ---- Firestore
      +-- FCM ---------------- Alertas / notificações
      +-- Storage ------------ Anexos / releases
      +-- LiveKit ------------ Rádio PTT
```

O aplicativo não incorpora credenciais administrativas. Chaves privadas, secrets de infraestrutura, keystores e arquivos de ambiente ficam fora do repositório.

### Rádio no ambiente interno

Durante o piloto interno, o servidor LiveKit é self-hosted e acessado por rede privada. Essa configuração reduz custos durante desenvolvimento e testes. A arquitetura permite substituir esse endpoint por infraestrutura pública futuramente sem reescrever o módulo principal de rádio.

## Fluxo operacional

1. O usuário instala o OPERIS e prepara as permissões do aparelho.
2. Entra em uma equipe por convite e recebe sua função operacional.
3. Assume o plantão e, quando aplicável, seleciona ou confirma seu posto.
4. Durante o serviço pode usar Rádio, Sirene, Ocorrências e Rondas.
5. A Brigada acompanha e compartilha o relatório do plantão.
6. Novas versões podem ser distribuídas pelo atualizador interno do próprio app.

## Estado do projeto

A versão atual registrada neste repositório é **4.3.1 (versionCode 36)**. Essa versão reforçou o fluxo de atualização e a preparação do aparelho para uso em campo.
## Build local

Requisitos principais:

- JDK 17
- Android Studio compatível com AGP 8.9.2
- Android SDK 35
- Node.js 22 para o backend
- arquivo `app/google-services.json` do seu próprio projeto Firebase

Build Android de piloto:

```powershell
.\gradlew.bat testPilotUnitTest assemblePilot
```

Testes do backend:

```powershell
Set-Location backend
npm install
npm test
```

## Segurança

Este repositório ignora arquivos sensíveis e artefatos locais, incluindo `google-services.json`, `.env`, keystores, secrets do LiveKit, APKs e backups. Nunca publique chaves privadas, senhas de assinatura ou credenciais administrativas.

O projeto usa autenticação, App Check, validação de participação em equipe e regras de autorização no backend. Mesmo assim, trata-se de um software em desenvolvimento e não deve ser considerado um sistema certificado para operações críticas.
## Próximos passos

- encerramento automático do plantão às 07:00;
- compartilhamento do Relatório da Brigada durante o plantão;
- simplificação das telas a partir do uso em campo;
- melhoria contínua de Ocorrências e Rondas;
- suporte experimental a usuários iOS por interface web/PWA;
- redução gradual de acoplamento e melhoria da cobertura de testes;
- infraestrutura pública para o rádio somente se o projeto exigir no futuro.

## Estrutura do repositório

- `app/` — aplicativo Android.
- `backend/` — Cloud Functions e regras de domínio do servidor.
- `docs/` — documentação técnica e operacional.
- `firestore.rules` / `storage.rules` — regras de segurança.
- `CHANGELOG.md` — histórico resumido das versões.

## Histórico

Consulte [CHANGELOG.md](CHANGELOG.md) para acompanhar as alterações por versão.

---

Projeto desenvolvido como solução interna, laboratório de engenharia de software e peça de portfólio, com evolução orientada por testes em aparelhos reais.