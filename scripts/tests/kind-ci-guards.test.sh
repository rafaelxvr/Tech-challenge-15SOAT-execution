#!/usr/bin/env bash
# Regressões do Kind do CI (UNR-960): guard anti-AWS, prova de port-forward residual e sanitização fail-closed.
# Só valores FICTÍCIOS. Não toca em rede, AWS nem cluster.
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
pids=()
cleanup() { for p in "${pids[@]:-}"; do [ -z "$p" ] || kill "$p" 2>/dev/null || true; done; rm -rf "$work"; }
trap cleanup EXIT
fail() { echo "FALHOU: $*"; exit 1; }

# shellcheck source=scripts/lib/kind-ci-guards.sh
source "$root/scripts/lib/kind-ci-guards.sh"

# ---------- 1. guard anti-AWS ----------
fake_home="$work/home"; mkdir -p "$fake_home"
run_guard() { ( unset "${KIND_CI_FORBIDDEN_AWS_VARS[@]}"; HOME="$fake_home" env "$@" bash -c 'source "$0"; guard_no_aws' "$root/scripts/lib/kind-ci-guards.sh" ); }

out="$(run_guard)" || fail "guard rejeitou ambiente limpo"
grep -q 'guard-aws: ok' <<<"$out" || fail "guard limpo não confirmou ok"

for v in AWS_PROFILE AWS_DEFAULT_PROFILE AWS_ACCESS_KEY_ID AWS_SECRET_ACCESS_KEY AWS_SESSION_TOKEN \
         AWS_ROLE_ARN AWS_WEB_IDENTITY_TOKEN_FILE AWS_SHARED_CREDENTIALS_FILE AWS_CONFIG_FILE; do
  if out="$(run_guard "$v=valor-ficticio-nao-real" 2>&1)"; then fail "guard aceitou $v"; fi
  grep -q "$v" <<<"$out" || fail "mensagem não cita $v"
  grep -q 'valor-ficticio-nao-real' <<<"$out" && fail "guard vazou o VALOR de $v"
  grep -q 'guard-aws: ok' <<<"$out" && fail "guard disse 'ok' com $v definido"
done

for f in credentials config; do
  mkdir -p "$fake_home/.aws"; : >"$fake_home/.aws/$f"
  if out="$(run_guard 2>&1)"; then fail "guard aceitou ~/.aws/$f"; fi
  grep -q "~/.aws/$f" <<<"$out" || fail "mensagem não cita ~/.aws/$f"
  rm -rf "$fake_home/.aws"
done
echo "ok: guard anti-AWS"

# ---------- 2. port-forward residual ----------
if command -v pgrep >/dev/null 2>&1; then
  ctx="kind-oficina-exec-ci-teste-$$"
  # kubectl falso: um script chamado 'kubectl' cuja linha de comando é igual à do processo real
  # (kubectl --context <ctx> -n <ns> port-forward --address 127.0.0.1 svc/x 18080:8080).
  mkdir -p "$work/bin"; printf '#!/usr/bin/env bash\nexec sleep 60\n' >"$work/bin/kubectl"; chmod +x "$work/bin/kubectl"

  [ -z "$(residual_port_forwards "$ctx")" ] || fail "residual detectado sem processo"

  "$work/bin/kubectl" --context "$ctx" -n oficina-execution port-forward --address 127.0.0.1 svc/oficina-execution 18080:8080 \
    </dev/null >/dev/null 2>&1 &
  pids+=("$!"); sleep 1
  [ -n "$(residual_port_forwards "$ctx")" ] || fail "port-forward residual NÃO detectado (matcher errado)"

  [ -z "$(residual_port_forwards "${ctx}-outro")" ] || fail "matcher pegou contexto de outro cluster"

  kill "${pids[0]}" 2>/dev/null || true; wait "${pids[0]}" 2>/dev/null || true; sleep 1
  [ -z "$(residual_port_forwards "$ctx")" ] || fail "residual persiste após kill"
  echo "ok: prova de port-forward residual"
else
  [ -z "${CI:-}" ] || fail "pgrep ausente no CI"
  echo "pulado: pgrep ausente (roda no CI)"
fi

# ---------- 3. sanitização fail-closed ----------
SAN="$root/scripts/kind-ci-sanitize.sh"
ev="$work/ev"; mkdir -p "$ev"
jwt='eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmaWN0aWNpbyJ9.assinaturaficticia123'
printf 'GET /x\nAuthorization: Bearer abcdef123456\n"authorization":"Bearer xyz987654321"\nheader bearer tok.en-123456 fim\ntoken=%s\n' "$jwt" >"$ev/diagnostico.txt"
printf '{"status":"UP"}\n' >"$ev/health-liveness.json"
bash "$SAN" "$ev" >/dev/null || fail "sanitizador falhou em entrada mascarável"
for secret in abcdef123456 xyz987654321 tok.en-123456 "$jwt" assinaturaficticia123; do
  grep -rqF "$secret" "$ev" && fail "segredo fictício sobrou: ${secret:0:6}..."
done
grep -q '"status":"UP"' "$ev/health-liveness.json" || fail "sanitização alterou conteúdo legítimo"
echo "ok: sanitização mascara Authorization/Bearer/JWT"

# fail-closed: mascarador quebrado (no-op) NÃO pode deixar nada publicável
mkdir -p "$work/ev2"; printf 'Authorization: Bearer abcdef123456\n' >"$work/ev2/a.txt"; printf '{"status":"UP"}\n' >"$work/ev2/b.json"
if SANITIZE_MASK_CMD='cat' bash "$SAN" "$work/ev2" >/dev/null 2>&1; then fail "sanitizador aceitou resíduo"; fi
[ -z "$(find "$work/ev2" -type f)" ] || fail "fail-closed deixou arquivos publicáveis"
# fail-closed: mascarador que falha (exit != 0)
mkdir -p "$work/ev3"; printf 'x\n' >"$work/ev3/a.txt"
if SANITIZE_MASK_CMD='false' bash "$SAN" "$work/ev3" >/dev/null 2>&1; then fail "sanitizador aceitou falha do mascarador"; fi
[ -z "$(find "$work/ev3" -type f)" ] || fail "fail-closed (falha do mascarador) deixou arquivos"
echo "ok: sanitização fail-closed"
echo "kind-ci-guards: todos os testes passaram"
