#!/usr/bin/env bash
# Kind efêmero do CI do execution, independente dos outros serviços e sem AWS.
# Uso: IMAGE_TAG=<sha> bash scripts/kind-ci.sh up|verify|down
#   up     cria o cluster, sobe LocalStack, cria filas/tabelas e implanta a imagem do SHA (via scripts/kind-deploy.sh)
#   verify confere a imagem do SHA no pod, readiness/liveness por port-forward e grava a evidência em $EVIDENCE_DIR
#   down   remove cluster, contêineres e imagem e PROVA o cleanup (falha se sobrar algo)
# Credenciais: só as fictícias do LocalStack. O segredo HMAC do JWT é efêmero (gerado aqui, nunca impresso nem gravado).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
action="${1:?uso: kind-ci.sh up|verify|down}"
cluster="${KIND_CLUSTER:-oficina-exec-ci}"
context="kind-${cluster}"
tag="${IMAGE_TAG:?IMAGE_TAG obrigatório (SHA do commit, nunca latest)}"
[ "$tag" != "latest" ] || { echo "tag latest proibida"; exit 1; }
evidence="${EVIDENCE_DIR:-$root/target/kind-ci-evidence}"
ns=oficina-execution
# shellcheck source=scripts/lib/kind-ci-guards.sh
source "$root/scripts/lib/kind-ci-guards.sh"
local_port="${KIND_CI_LOCAL_PORT:-18080}"

up() {
  guard_no_aws
  kind create cluster --name "$cluster" --config "$root/infra/kind-ci/kind-config.yaml" --wait 120s
  kubectl --context "$context" apply -f "$root/infra/kind-ci/manifests.yaml"
  kubectl --context "$context" -n infra rollout status deployment/localstack --timeout=240s
  kubectl --context "$context" -n infra exec -i deploy/localstack -- sh -s < "$root/infra/kind-ci/localstack-init.sh"
  STAFF_JWT_SECRET="${STAFF_JWT_SECRET:-$(openssl rand -hex 32)}" KIND_CLUSTER="$cluster" IMAGE_TAG="$tag" \
    bash "$root/scripts/kind-deploy.sh"
}

verify() {
  mkdir -p "$evidence"
  local expected="oficina-execution:${tag}" actual image_id pf_pid=""
  actual="$(kubectl --context "$context" -n "$ns" get deploy oficina-execution -o jsonpath='{.spec.template.spec.containers[0].image}')"
  [ "$actual" = "$expected" ] || { echo "imagem do deployment é '$actual', esperado '$expected'"; exit 1; }
  kubectl --context "$context" -n "$ns" rollout status deployment/oficina-execution --timeout=180s
  kubectl --context "$context" -n "$ns" wait --for=condition=Ready pod -l app.kubernetes.io/name=oficina-execution --timeout=120s
  image_id="$(kubectl --context "$context" -n "$ns" get pod -l app.kubernetes.io/name=oficina-execution -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')"

  kubectl --context "$context" -n "$ns" port-forward --address 127.0.0.1 svc/oficina-execution "${local_port}:8080" \
    </dev/null >"$evidence/port-forward.log" 2>&1 &
  pf_pid=$!
  trap 'kill "$pf_pid" 2>/dev/null || true' RETURN
  local base="http://127.0.0.1:${local_port}/execucao/actuator/health" probe body
  for probe in liveness readiness; do
    body=""
    for _ in $(seq 1 30); do
      body="$(curl -fsS --max-time 5 "$base/$probe" 2>/dev/null || true)"
      grep -q '"status":"UP"' <<<"$body" && break
      sleep 2
    done
    grep -q '"status":"UP"' <<<"$body" || { echo "health/$probe não ficou UP"; exit 1; }
    echo "health/$probe: UP"
    printf '%s\n' "$body" >"$evidence/health-$probe.json"
  done
  kill "$pf_pid" 2>/dev/null || true; wait "$pf_pid" 2>/dev/null || true

  {
    echo "# Evidência do Kind independente do execution"
    echo "- SHA: ${tag}"
    echo "- Imagem no deployment: ${actual}"
    echo "- imageID no pod: ${image_id}"
    echo "- Cluster: ${cluster} (efêmero)"
    echo "- Dependência: LocalStack com credenciais fictícias (sem AWS)"
    echo "- health/liveness: UP"
    echo "- health/readiness: UP"
    [ -z "${GITHUB_SERVER_URL:-}" ] || echo "- Run: ${GITHUB_SERVER_URL}/${GITHUB_REPOSITORY}/actions/runs/${GITHUB_RUN_ID}"
  } | tee "$evidence/evidence.md"
}

down() {
  mkdir -p "$evidence"
  kind delete cluster --name "$cluster" || true
  docker rmi -f "oficina-execution:${tag}" >/dev/null 2>&1 || true
  local problems=()
  kind get clusters 2>/dev/null | grep -qx "$cluster" && problems+=("cluster ainda existe")
  [ -z "$(docker ps -a --filter "name=^${cluster}-" --format '{{.Names}}')" ] || problems+=("contêineres do cluster restantes")
  [ -z "$(docker images -q "oficina-execution:${tag}")" ] || problems+=("imagem local restante")
  [ -z "$(residual_port_forwards "$context")" ] || problems+=("port-forward residual")
  if [ "${#problems[@]}" -gt 0 ]; then echo "cleanup incompleto: ${problems[*]}"; exit 1; fi
  echo "cleanup verificado: cluster=0 contêineres=0 imagem=0 port-forwards=0" | tee "$evidence/teardown.txt"
}

case "$action" in
  up) up ;;
  verify) verify ;;
  down) down ;;
  *) echo "ação inválida: $action"; exit 1 ;;
esac
