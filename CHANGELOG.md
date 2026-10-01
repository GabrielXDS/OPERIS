# Changelog

Todas as mudanças relevantes do OPERIS são registradas aqui de forma resumida.

## [4.3.2] - 2026-10-01

### Plantão
- início do plantão livre, sem obrigar o funcionário a iniciar no horário-base da escala;
- encerramento automático no próximo horário de término configurado da equipe;
- no piloto atual, a escala padrão 19:00 → 07:00 permanece ativa;
- um novo plantão pode ser aberto imediatamente depois do encerramento para testes;
- encerramento limpa o estado de serviço e desativa o Rádio/PTT.

### Relatório da Brigada
- relatório parcial disponível durante o plantão;
- compartilhamento do conteúdo registrado até o momento;
- após o encerramento, o mesmo plantão passa a gerar relatório final.

### Escala da equipe
- criador e administrador podem informar o horário-base da equipe;
- OPERIS calcula automaticamente 12 horas de serviço e 36 horas de descanso;
- a escala funciona como referência operacional e não bloqueia o início real do plantão.

### Backend e notificações
- fechamento automático notifica os integrantes da equipe;
- aviso de encerramento interrompe imediatamente o serviço local do Rádio;
- removido o aviso automático de início fixo às 19:00.

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