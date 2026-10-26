# Contratos canônicos da saga (Plan 0, versão v1)

Conjunto congelado pelo gate G1.5 (UNR-64). Os três repositórios (os-service, billing, execution) mantêm
cópias **idênticas byte a byte** de todos os arquivos listados em `CONTRACTS.sha256`.

- `<Tipo>/v1.json`: JSON Schema 2020-12 do envelope (`envelope`), dos comandos e das respostas. Todo payload é
  fechado (`additionalProperties: false`).
- `fixtures/<Tipo>.json`: envelope completo válido de cada tipo (comando ou resposta), usado por provider e consumer.
- `ownership.json`: dono canônico de cada schema, tipo de mensagem, comando respondido e `passo`.
- `CONTRACTS.sha256`: `sha256` (hex, LF) de cada arquivo acima, ordenado por caminho. O `sha256` deste manifesto
  é o identificador do conjunto e deve ser igual nos três CIs (`CONTRACT_SET_SHA256`).

## Ownership

- Comando: o consumidor é o dono (`billing`: GerarOrcamento, RegistrarDecisao, CancelarOrcamento, EstornarPagamento;
  `execution`: IniciarDiagnostico, IniciarReparo, CancelarExecucao).
- Resposta: o produtor é o dono. `CompensacaoFalhou` é emitida por billing e execution; o dono canônico é billing.
- Envelope: dono os-service (orquestrador da saga).

## Regra de mudança

Alterar um schema v1 exige mudança aditiva e compatível; mudança incompatível cria `v2/` ao lado. Edite primeiro o
repositório dono, copie os arquivos para os outros dois, regenere o manifesto e atualize `CONTRACT_SET_SHA256` nos
três workflows no mesmo conjunto de PRs. Os arquivos usam `eol=lf` (`.gitattributes`) para o checksum ser estável.
