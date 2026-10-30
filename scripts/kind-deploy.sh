#!/usr/bin/env bash
# Implanta o execution no Kind efêmero `oficina-phase4` (infra do UNR-68 já criada: namespaces, LocalStack, filas e tabelas).
# Uso: IMAGE_TAG=<sha-curto> STAFF_JWT_SECRET=<mesmo JWT_SECRET do os-service> bash scripts/kind-deploy.sh
# Nenhum recurso AWS: o único endpoint é o LocalStack do cluster.
# O segredo HMAC do JWT de staff é o MESMO do os-service (emissor): vem de fora do repositório e nunca é gerado aqui.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cluster="${KIND_CLUSTER:-oficina-phase4}"
context="kind-${cluster}"
tag="${IMAGE_TAG:?IMAGE_TAG obrigatório (SHA curto do commit, nunca latest)}"
[ "$tag" != "latest" ] || { echo "tag latest proibida"; exit 1; }
image="oficina-execution:${tag}"
secret="${STAFF_JWT_SECRET:?STAFF_JWT_SECRET obrigatório: use o mesmo JWT_SECRET do os-service}"
[ "${#secret}" -ge 32 ] || { echo "STAFF_JWT_SECRET deve ter ao menos 32 caracteres"; exit 1; }

docker build -t "$image" "$root"
kind load docker-image "$image" --name "$cluster"

# O segredo nunca toca o disco nem os argumentos de processo: o manifesto do Secret segue por stdin (printf é builtin;
# base64 lê o valor do stdin). Evita arquivo temporário, cujas permissões POSIX não valem na DACL NTFS do Git Bash.
secret_b64="$(printf '%s' "$secret" | base64 | tr -d '
')"
printf 'apiVersion: v1
kind: Secret
metadata:
  name: oficina-execution-secrets
  namespace: oficina-execution
type: Opaque
data:
  staff_hmac_secret: %s
' "$secret_b64"   | kubectl --context "$context" apply -f -

kubectl kustomize "$root/k8s/local" | sed "s#image: oficina-execution:local#image: ${image}#" \
  | kubectl --context "$context" apply -f -
kubectl --context "$context" -n oficina-execution rollout status deployment/oficina-execution --timeout=180s
