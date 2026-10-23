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
if grep -Eq '<\(|mktemp|--from-file|>[[:space:]]*"?\$' "$deploy"; then
  echo "deploy não pode gravar o segredo em disco nem usar process substitution"; exit 1
fi
grep -qF "apply -f -" "$deploy" || { echo "deploy deve entregar o Secret ao kubectl por stdin"; exit 1; }
# Comportamental: com kubectl/docker/kind falsos, o segredo não pode aparecer em argumentos nem em arquivos.
fake="$(mktemp -d)"; trap 'rm -rf "$fake"' EXIT
for c in docker kind; do printf '#!/usr/bin/env bash
exit 0
' > "$fake/$c"; done
cat > "$fake/kubectl" <<'FAKE'
#!/usr/bin/env bash
printf '%s
' "$*" >> "$FAKE_ARGS"
if [ "$*" = "kustomize" ] || [[ "$*" == kustomize* ]]; then echo "image: oficina-execution:local"; exit 0; fi
if [[ "$*" == *"apply -f -"* ]]; then cat >> "$FAKE_STDIN"; fi
FAKE
chmod +x "$fake"/*
probe='0123456789abcdef0123456789abcdef-PROBE'
FAKE_ARGS="$fake/args" FAKE_STDIN="$fake/stdin" PATH="$fake:$PATH" IMAGE_TAG=abc1234 STAFF_JWT_SECRET="$probe" bash "$deploy" >"$fake/out" 2>&1   || { cat "$fake/out"; echo "deploy falhou com ferramentas falsas"; exit 1; }
if grep -rqF "$probe" "$fake/args" "$fake/out"; then echo "segredo vazou em argumento ou saída"; exit 1; fi
b64="$(printf '%s' "$probe" | base64 | tr -d '
')"
grep -qF "$b64" "$fake/stdin" || { echo "Secret não chegou ao kubectl por stdin"; exit 1; }
echo "kind manifests contract OK"
