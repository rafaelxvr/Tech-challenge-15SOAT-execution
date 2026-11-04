#!/bin/sh
# Entrypoint do contêiner. Observabilidade (agente Java do New Relic) só liga com
# OBSERVABILITY_ENABLED=true; nesse caso NEW_RELIC_LICENSE_KEY é obrigatória e o
# processo falha na partida com mensagem clara. O valor da chave nunca é impresso.
set -eu

NR_AGENT="${NEW_RELIC_AGENT_PATH:-/app/newrelic/newrelic.jar}"

if [ "${OBSERVABILITY_ENABLED:-false}" = "true" ]; then
  if [ -z "${NEW_RELIC_LICENSE_KEY:-}" ]; then
    echo "ERRO: OBSERVABILITY_ENABLED=true exige NEW_RELIC_LICENSE_KEY (injetada por Secret em tempo de execução). Defina a variável ou desligue a observabilidade." >&2
    exit 78
  fi
  if [ ! -f "$NR_AGENT" ]; then
    echo "ERRO: agente New Relic não encontrado em $NR_AGENT." >&2
    exit 78
  fi
  export NEW_RELIC_DISTRIBUTED_TRACING_ENABLED=true
  export NEW_RELIC_APPLICATION_LOGGING_FORWARDING_ENABLED=false
  export NEW_RELIC_LABELS="environment:${DEPLOYMENT_ENVIRONMENT:-local}"
  JAVA_OPTS="${JAVA_OPTS:-} -javaagent:${NR_AGENT}"
fi

# shellcheck disable=SC2086
exec ${JAVA_BIN:-java} ${JAVA_OPTS:-} -jar "${APP_JAR:-/app/app.jar}"
