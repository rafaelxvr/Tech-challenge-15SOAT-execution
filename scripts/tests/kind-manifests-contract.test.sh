#!/usr/bin/env bash
# Contrato offline dos manifests locais: só LocalStack, sem segredo versionado, sem objetos de plataforma.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
rendered="$(kubectl kustomize "$root/k8s/local")"
for expected in \
  'namespace: oficina-execution' \
  'name: oficina-execution' \
  'EXECUCAO_AWS_MODO: LOCALSTACK' \
  'http://localstack.infra.svc.cluster.local:4566' \
  'execution-commands.fifo' \
  'os-saga-replies.fifo' \
  'oficina-phase4-execucoes' \
  'oficina-phase4-execucao-outbox' \
  'oficina-phase4-execucao-inbox' \
  'name: oficina-execution-secrets' \
  '/execucao/actuator/health/readiness' \
  'port: 8080' \
  'readOnlyRootFilesystem: true'; do
  grep -qF "$expected" <<<"$rendered" || { echo "faltando: $expected"; exit 1; }
done
if grep -Eq 'amazonaws\.com|eks\.amazonaws|arn:aws|JWT_SECRET: ' <<<"$rendered"; then
  echo "referência AWS ou segredo literal encontrada"; exit 1
fi
if grep -Eq '^kind: (Secret|Namespace|HorizontalPodAutoscaler)$' <<<"$rendered"; then
  echo "objeto fora do escopo do serviço"; exit 1
fi
if grep -qE 'image: .*:latest' <<<"$rendered"; then echo "tag latest"; exit 1; fi
deploy="$root/scripts/kind-deploy.sh"
grep -qF 'STAFF_JWT_SECRET:?' "$deploy" || { echo "deploy deve exigir STAFF_JWT_SECRET externo"; exit 1; }
if grep -Eq 'urandom|openssl rand|--from-literal' "$deploy"; then
  echo "deploy não pode gerar chave própria nem passar segredo em argumento"; exit 1
fi
if grep -Eq 'echo[^#]*\$\{?(secret|STAFF_JWT_SECRET)' "$deploy"; then
  echo "deploy não pode imprimir o segredo"; exit 1
fi
if grep -Fq '<(' "$deploy"; then
  echo "deploy não pode usar process substitution (kubectl.exe não abre /proc/<pid>/fd)"; exit 1
fi
grep -qF 'mktemp -d' "$deploy" || { echo "deploy deve usar arquivo temporário regular para o segredo"; exit 1; }
grep -qF "trap 'rm -rf" "$deploy" || { echo "deploy deve limpar o arquivo temporário do segredo via trap"; exit 1; }
echo "kind manifests contract OK"
