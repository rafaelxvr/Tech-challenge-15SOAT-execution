#!/bin/sh
# Contrato do entrypoint: falha sem chave quando a observabilidade está ligada e nunca vaza o valor.
set -eu
DIR=$(cd "$(dirname "$0")/../.." && pwd)
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
printf '#!/bin/sh\necho "ARGS: $*"\n' > "$TMP/fakejava"; chmod +x "$TMP/fakejava"
: > "$TMP/newrelic.jar"
run() { env -i PATH="$PATH" JAVA_BIN="$TMP/fakejava" APP_JAR=app.jar NEW_RELIC_AGENT_PATH="$TMP/newrelic.jar" "$@" sh "$DIR/docker/entrypoint.sh"; }
fail() { echo "FALHOU: $1" >&2; exit 1; }

out=$(run 2>&1) || fail "sem observabilidade deveria iniciar"
echo "$out" | grep -q "ARGS: -jar app.jar" || fail "sem agente quando desligado"
echo "$out" | grep -q javaagent && fail "agente não deve entrar desligado"

if out=$(run OBSERVABILITY_ENABLED=true 2>&1); then fail "deveria falhar sem chave"; fi
echo "$out" | grep -q "NEW_RELIC_LICENSE_KEY" || fail "mensagem deve citar a variável"

out=$(run OBSERVABILITY_ENABLED=true NEW_RELIC_LICENSE_KEY=segredo-ficticio-123 2>&1) || fail "com chave deveria iniciar"
echo "$out" | grep -q "javaagent:$TMP/newrelic.jar" || fail "agente deve entrar ligado"
echo "$out" | grep -q "segredo-ficticio-123" && fail "chave vazou na saída"
echo "OK"
