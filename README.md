# Serviço de Execução — Oficina Mecânica

Serviço Spring Boot independente para diagnóstico, reparo e cancelamento de execução. Este repositório contém o baseline de build; os fluxos de domínio e integrações serão adicionados conforme o plano da Fase 4.

## Contrato local previsto

- Java 17 e Spring Boot 3.2.5.
- HTTP em `8080`, contexto `/execucao`.
- Health check: `/execucao/actuator/health`.
- DynamoDB Local e SQS FIFO pelo LocalStack; nomes de tabelas e filas seguem o contrato da Fase 4.
- Nenhuma etapa deste baseline publica imagens ou faz deploy.

## Build e testes

PowerShell:

```powershell
& (Join-Path (Get-Location) 'mvnw.cmd') -B verify
```

Linux/macOS:

```sh
bash ./mvnw -B verify
```

## Recuperação operacional (runbook D19-R)

Outbox `BLOCKED`, mensagens na DLQ, LocalStack/SQS fora e reconciliação de compensação são tratados no runbook único da Fase 4, mantido no os-service: [saga-recuperacao-d19r.md](https://github.com/rafaelxvr/Tech-challenge-15SOAT/blob/docs/unr-620-runbook-d19r/docs/runbooks/saga-recuperacao-d19r.md) (o link passa a apontar para `develop` depois do merge do PR do os-service). Exercitado em Kind com este serviço no SHA `0669c4bfd1170e75c5726621aee51fcf329a3704`.

## Branches e segredos

`main` é reservado a revisões aprovadas para entrega; `develop` é a branch de integração. O baseline ainda não foi implantado. Não versione credenciais: arquivos `.env` são ignorados e CI deve receber segredos por configuração protegida do GitHub.
