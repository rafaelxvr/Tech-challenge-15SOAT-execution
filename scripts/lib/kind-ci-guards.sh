#!/usr/bin/env bash
# Funções puras do Kind do CI (sem efeito colateral ao carregar): guard anti-AWS e detecção de port-forward residual.
# Carregue com `source`; testes em scripts/tests/kind-ci-guards.test.sh.

# Variáveis que dão ao processo um caminho até a AWS (credencial, sessão, role/OIDC, perfil ou override de arquivo).
KIND_CI_FORBIDDEN_AWS_VARS=(
  AWS_PROFILE AWS_DEFAULT_PROFILE
  AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN
  AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE
  AWS_SHARED_CREDENTIALS_FILE AWS_CONFIG_FILE
)

# Falha (return 1) se houver qualquer caminho até a AWS. Imprime só NOMES de variáveis/arquivos, nunca valores.
# Só diz "sem credencial AWS" quando nada foi encontrado.
guard_no_aws() {
  local found=() v
  for v in "${KIND_CI_FORBIDDEN_AWS_VARS[@]}"; do
    [ -z "${!v:-}" ] || found+=("$v")
  done
  [ ! -e "${HOME:-/nonexistent}/.aws/credentials" ] || found+=("~/.aws/credentials")
  [ ! -e "${HOME:-/nonexistent}/.aws/config" ] || found+=("~/.aws/config")
  if [ "${#found[@]}" -gt 0 ]; then
    echo "::error::guard-aws: o Kind do CI não pode alcançar a AWS; encontrado: ${found[*]}"
    return 1
  fi
  echo "guard-aws: ok (sem AWS_PROFILE, sem chave/sessão/role, sem overrides de arquivo, sem ~/.aws)"
}

# PIDs de `kubectl --context <ctx> ... port-forward` (o contexto vem ANTES de port-forward na linha de comando).
residual_port_forwards() {
  local context="${1:?contexto obrigatório}"
  pgrep -f -- "kubectl.*--context[ =]${context}( |$).*port-forward" || true
}
