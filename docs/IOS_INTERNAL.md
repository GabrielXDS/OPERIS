# OPERIS no iPhone — estratégia de uso interno

## Objetivo

Permitir que integrantes com iPhone participem do OPERIS sem criar, neste estágio, uma despesa recorrente com distribuição nativa pela Apple.

## Primeira etapa: OPERIS Web/PWA

A alternativa inicial será uma versão web instalável pela opção **Adicionar à Tela de Início** do Safari.

O objetivo da primeira versão é oferecer:
- identificação e acesso à equipe;
- início e acompanhamento do plantão;
- seleção do posto do AGP;
- avisos e notificações operacionais;
- consulta dos integrantes em serviço;
- acesso experimental ao Rádio/PTT.

A PWA utilizará o mesmo backend Firebase e os mesmos conceitos de equipe, cargo e plantão do aplicativo Android.

## Rádio/PTT

O Rádio no navegador será integrado ao LiveKit por WebRTC. Enquanto a infraestrutura de áudio continuar hospedada na rede privada interna, o iPhone também precisará ter acesso a essa rede por Tailscale.

O cliente web nunca deve receber o segredo administrativo do LiveKit. Tokens de acesso continuam sendo emitidos pelo backend.

### Limitação conhecida

O PTT em uma PWA no iOS não deve ser considerado equivalente ao serviço foreground usado no Android até ser validado em aparelho real. O navegador pode suspender execução e áudio quando a tela é bloqueada ou a aplicação perde atividade.

Por isso, os testes precisam separar:
- PTT com a PWA aberta;
- PTT em segundo plano;
- PTT com tela bloqueada;
- reconexão depois de suspensão do iOS.

## Alertas e sirene

A PWA poderá usar notificações Web Push quando instalada na Tela de Início e suportada pela versão do iOS.

A primeira implementação no iPhone será tratada como **alerta operacional**, não como equivalência garantida da sirene Android. O iOS impõe restrições adicionais a áudio em segundo plano, modo silencioso e notificações críticas.

Portanto, inicialmente:
- Android: sirene operacional completa já existente;
- iPhone: notificação de emergência e abertura do OPERIS;
- sirene equivalente ao Android: somente após validação técnica específica.
