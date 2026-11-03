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

## Branches e segredos

`main` é reservado a revisões aprovadas para entrega; `develop` é a branch de integração. O baseline ainda não foi implantado. Não versione credenciais: arquivos `.env` são ignorados e CI deve receber segredos por configuração protegida do GitHub.
