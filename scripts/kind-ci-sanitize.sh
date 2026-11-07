#!/usr/bin/env bash
# Sanitiza TODOS os arquivos do diretório de evidência e falha FECHADO: se sobrar Authorization/Bearer/JWT,
# apaga todos os arquivos (nada é publicado) e sai com erro. Imprime só a classe do achado, nunca o conteúdo.
# Uso: bash scripts/kind-ci-sanitize.sh <dir>
set -euo pipefail
dir="${1:?uso: kind-ci-sanitize.sh <dir>}"
[ -d "$dir" ] || { echo "sanitize: diretório inexistente: $dir"; exit 0; }

read -r -d '' SED_RULES <<'SED' || true
s/([Aa]uthorization)["']?[[:space:]]*[:=].*/\1: ***/I
s/([Bb]earer)[[:space:]]+[^[:space:]"']+/\1 ***/Ig
s/eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]*)?/***jwt***/g
s/([Pp]assword|[Ss]ecret|[Tt]oken|[Aa]pi[_-]?[Kk]ey)(["':=[:space:]]+)[^[:space:]*]+/\1\2***/g
SED
mask() { sed -E -e "$SED_RULES"; }
# Substituível nos testes para simular falha do mascaramento (o verificador abaixo tem de pegar).
if [ -n "${SANITIZE_MASK_CMD:-}" ]; then mask() { eval "$SANITIZE_MASK_CMD"; }; fi

leaks() { # $1 arquivo -> imprime a classe de cada vazamento residual
  if grep -Eiq 'bearer[[:space:]]+[^*[:space:]]' "$1"; then echo bearer; fi
  # Sem -q no 2º grep: com pipefail, SIGPIPE no 1º grep mascararia um achado.
  if grep -Ei 'authorization["'"'"']?[[:space:]]*[:=]' "$1" | grep -Eiv 'authorization: \*\*\*$' >/dev/null; then echo authorization; fi
  if grep -Eq 'eyJ[A-Za-z0-9_-]{10,}' "$1"; then echo jwt; fi
}

fail_closed() {
  find "$dir" -type f -delete
  echo "::error::sanitização falhou ($1): artefatos de evidência removidos, nada será publicado"
  exit 1
}

while IFS= read -r -d '' f; do
  tmp="$(mktemp)"
  if ! mask <"$f" >"$tmp"; then rm -f "$tmp"; fail_closed "mascaramento falhou em $(basename "$f")"; fi
  mv "$tmp" "$f"
  found="$(leaks "$f" | sort -u | tr '\n' ' ')"
  [ -z "$found" ] || fail_closed "resíduo [${found% }] em $(basename "$f")"
done < <(find "$dir" -type f -print0)
echo "sanitize: ok ($(find "$dir" -type f | wc -l | tr -d ' ') arquivos)"
