# Changelog

Todas as mudanças relevantes do OPERIS são registradas aqui de forma resumida.

## [4.3.1] - 2026-09-29

### Adicionado
- câmera no diagnóstico de preparação do aparelho;
- resumo de prontidão operacional;
- atalhos para permissões e configurações relevantes;
- exibição mais clara de versão, notas e tamanho da atualização.

### Melhorado
- fluxo de atualização in-app;
- recuperação do download ao retornar ao aplicativo;
- tratamento de estados e erros do atualizador;
- fallback para instalação alternativa.

### Segurança e validação
- validação SHA-256 do APK;
- validação de `packageName` e `versionCode` antes da instalação;
- preservação dos dados do aplicativo durante atualização.

### Testes de campo
- atualização 4.3.0 → 4.3.1 validada em aparelho real sem perda de equipe, usuário ou configurações.
## [4.3.0] - 2026-09-29

### Baseline estável
- versão consolidada para testes de campo;
- plantões com funções operacionais e postos;
- Rádio/PTT com canais funcionais e canal geral;
- Sirene de emergência;
- Ocorrências e Rondas;
- gestão de equipes e cargos;
- relatório da Brigada;
- infraestrutura Firebase e LiveKit integrada.

Essa versão foi registrada como baseline Git para permitir comparação e recuperação durante refatorações futuras.