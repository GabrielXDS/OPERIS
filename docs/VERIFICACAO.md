# Verificação da entrega

Projeto Firebase selecionado: `cirene-9d83e`. Não foi feita implantação nem alteração de dados nesse projeto; a CLI não possui sessão autenticada.

## Backend

- 5 testes unitários aprovados: destinatários, cooldown, UUID, payload e expiração.
- 6 testes de integração aprovados no Firestore Emulator, projeto isolado `demo-alerta-equipe`: autenticação exigida, cadastro/sync, concorrência/idempotência, revogação, convite inválido e regras de acesso direto.
- Importação das quatro Functions e checagem de sintaxe JavaScript concluídas.
- `npm audit`: zero vulnerabilidades reportadas após atualização e override restrito de `gaxios@6.7.1 → uuid@11.1.1`. Gaxios usa a API v4 compatível; o override elimina o alerta transitivo de buffer em versões antigas de UUID.
- Testes locais executados com Node 24.14.0; deployment especifica runtime Node 22.
- O comando envoltório do Firebase Emulator encerrou com um erro genérico da CLI após todos os testes passarem e o emulador ser encerrado. O processo de testes registrou 6/6 e código 0; não se trata de aprovação do comando completo da CLI.
- Middleware App Check, FCM real e IAM em produção não foram exercitados pelos testes diretos de handlers.

Para repetir os testes unitários:

```powershell
Set-Location backend
npm ci
npm test
```

Para integração, na raiz, com Firebase CLI e Java compatível com o emulador:

```powershell
firebase emulators:exec --only firestore --project demo-alerta-equipe "node --test backend/test/integration.emulator.js"
```

O teste recusa execução sem FIRESTORE_EMULATOR_HOST ou se o projeto não for exatamente demo-alerta-equipe.

## Android

A validação de código foi executada em cópia isolada de trabalho, com JDK 17, Gradle 8.11.1 e SDK 35. O plugin Google Services foi retirado **somente dessa cópia de validação**, pois o arquivo de configuração Firebase real ainda não está disponível. O projeto entregue mantém o plugin normalmente.

Não foi gerado um APK operacional conectado ao Firebase. Não há testes em aparelhos físicos. A aprovação dos testes de backend não substitui o roteiro obrigatório de dois/três aparelhos.

A tarefa `compileDebugKotlin` concluiu a compilação Kotlin. A execução de `compileDebugJavaWithJavac` foi interrompida por `java.nio.file.AccessDeniedException` ao acessar `core-for-system-modules.jar` e um JAR do cache Firebase neste ambiente Windows. Portanto, a compilação completa/empacotamento não foi aprovada.

Após as correções, `compileDebugKotlin` e `lintDebug` terminaram com **BUILD SUCCESSFUL** na cópia isolada, omitindo a tarefa Java afetada pelo ambiente (`-x :app:compileDebugJavaWithJavac`). Lint ficou sem erros; permanecem avisos de estilo, versões de bibliotecas e persistência síncrona intencional dos IDs. O aviso ImplicitSamInstance em stopService é um falso positivo: Android identifica o serviço pelo componente explícito do Intent, não pela identidade do objeto Intent. Não foi criada uma baseline para ocultar erros.

O SHA-256 do Gradle Wrapper JAR foi conferido com o checksum oficial. JSONs do projeto e cabeçalho/formato do WAV também foram verificados.

A sirene WAV incluída foi sintetizada para este projeto: PCM mono, 22.050 Hz, 16 bits, quatro segundos, frequência oscilante e reprodução em loop pelo serviço.
