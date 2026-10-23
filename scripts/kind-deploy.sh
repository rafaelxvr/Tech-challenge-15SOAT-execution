#!/usr/bin/env bash
# Implanta o execution no Kind efêmero `oficina-phase4` (infra do UNR-68 já criada: namespaces, LocalStack, filas e tabelas).
# Uso: IMAGE_TAG=<sha-curto> bash scripts/kind-deploy.sh
# Nenhum recurso AWS: o único endpoint é o LocalStack do cluster; o segredo HMAC é aleatório por implantação.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cluster="${KIND_CLUSTER:-oficina-phase4}"
context="kind-${cluster}"
tag="${IMAGE_TAG:?IMAGE_TAG obrigatório (SHA curto do commit, nunca latest)}"
[ "$tag" != "latest" ] || { echo "tag latest proibida"; exit 1; }
image="oficina-execution:${tag}"

docker build -t "$image" "$root"
kind load docker-image "$image" --name "$cluster"

kubectl --context "$context" -n oficina-execution create secret generic oficina-execution-secrets \
  --from-literal=staff_hmac_secret="$(head -c 48 /dev/urandom | base64 | tr -d '\n')" \
  --dry-run=client -o yaml | kubectl --context "$context" apply -f -

kubectl kustomize "$root/k8s/local" | sed "s#image: oficina-execution:local#image: ${image}#" \
  | kubectl --context "$context" apply -f -
kubectl --context "$context" -n oficina-execution rollout status deployment/oficina-execution --timeout=180s
