#!/usr/bin/env bash
# Verificações estruturais do controle S-10 (endurecimento de contêiner e imagens fixadas por digest).
# Funções puras: recebem o YAML renderizado (ou o caminho do Dockerfile) e retornam 0/1 com mensagem em stderr.

# Imprime o documento YAML renderizado cujo `kind` de topo é $1.
s10_doc() {
  awk -v want="kind: $1" '
    function flush() { if (hit) printf "%s", buf; buf = ""; hit = 0 }
    { sub(/[[:cntrl:]]$/, "") }
    /^---$/ { flush(); next }
    { buf = buf $0 "\n"; if ($0 == want) hit = 1 }
    END { flush() }' <<<"$2"
}

# Valida cada contêiner do Deployment (e o pod) por escopo, não por texto solto no YAML.
s10_check_deployment() {
  local deployment
  deployment="$(s10_doc Deployment "$1")"
  [ -n "$deployment" ] || { echo "S-10: Deployment ausente" >&2; return 1; }
  awk '
    function indent(s) { match(s, /^ */); return RLENGTH }
    function fail(msg) { print "S-10: " msg > "/dev/stderr"; bad = 1 }
    function finish_container(   eff_nonroot, eff_seccomp) {
      if (!in_item) return
      name = (cname == "" ? "#" n : cname)
      if (sec !~ /\nallowPrivilegeEscalation: false\n/) fail("contêiner " name " sem allowPrivilegeEscalation: false")
      if (sec !~ /\ndrop:\n- ALL\n/) fail("contêiner " name " sem capabilities.drop: [ALL]")
      if (sec !~ /\nreadOnlyRootFilesystem: true\n/) fail("contêiner " name " sem readOnlyRootFilesystem: true")
      eff_nonroot = (sec ~ /\nrunAsNonRoot: true\n/) || (podsec ~ /\nrunAsNonRoot: true\n/)
      eff_seccomp = (sec ~ /\nseccompProfile:\ntype: RuntimeDefault\n/) || (podsec ~ /\nseccompProfile:\ntype: RuntimeDefault\n/)
      if (!eff_nonroot) fail("contêiner " name " sem runAsNonRoot: true (contêiner ou pod)")
      if (!eff_seccomp) fail("contêiner " name " sem seccomp RuntimeDefault (contêiner ou pod)")
      if (res !~ /\nlimits:\n(cpu|memory): [^\n]*\n(cpu|memory): /) fail("contêiner " name " sem limits de cpu e memory")
      in_item = 0
    }
    { lines[NR] = $0 }
    END {
      # Passo 1: contêiner(es) em containers:/initContainers:; guarda indentação do pod.
      for (i = 1; i <= NR; i++) {
        if (lines[i] ~ /^ *(containers|initContainers):$/ && !cind) { cind = indent(lines[i]) }
      }
      if (!cind) { fail("sem containers"); exit 1 }
      # kustomize alinha a lista ao nível da chave (itens em cind); YAML indentado põe itens em cind+2.
      pind = cind
      for (i = 1; i <= NR; i++) if (lines[i] ~ /^ *(containers|initContainers):$/) { if (indent(lines[i + 1]) > cind) pind = cind - 2; break }
      # Passo 2: securityContext e flags do pod (chaves na indentação do pod).
      for (i = 1; i <= NR; i++) {
        l = lines[i]; ind = indent(l)
        if (ind == pind && l ~ /^ *securityContext:$/) { insec = 1; continue }
        if (insec) {
          if (ind > pind) { t = l; sub(/^ */, "", t); podsec = podsec "\n" t; continue }
          insec = 0
        }
        if (ind == pind && l ~ /automountServiceAccountToken: true/) fail("pod com automountServiceAccountToken: true")
        if (ind == pind && l ~ /serviceAccountName: /) { sa = l; sub(/^ *serviceAccountName: */, "", sa) }
      }
      podsec = podsec "\n"
      if (sa == "") fail("pod sem serviceAccountName")
      # Passo 3: itens de containers:, cada um com escopo próprio.
      incont = 0
      for (i = 1; i <= NR; i++) {
        l = lines[i]; ind = indent(l)
        if (!incont) { if (l ~ /^ *(containers|initContainers):$/ && ind == cind) { incont = 1; iind = -1 } continue }
        if (ind < cind || (ind == cind && l !~ /^ *- /)) { finish_container(); incont = 0; continue }
        if (iind < 0) iind = ind
        if (ind == iind && l ~ /^ *- /) {
          finish_container(); in_item = 1; n++; cname = ""; sec = "\n"; res = "\n"; insec2 = 0; inres = 0
          l = substr(l, 1, ind) "  " substr(l, ind + 3); ind = ind + 2
        } else if (!in_item) continue
        key_ind = iind + 2
        t = l; sub(/^ */, "", t)
        if (ind == key_ind) {
          insec2 = (t == "securityContext:"); inres = (t == "resources:")
          if (t ~ /^name: /) { cname = t; sub(/^name: */, "", cname) }
          continue
        }
        if (insec2 && ind > key_ind) sec = sec t "\n"
        if (inres && ind > key_ind) res = res t "\n"
      }
      finish_container()
      if (n < 1) fail("nenhum contêiner encontrado")
      exit bad
    }' <<<"$deployment" || return 1
}

# ServiceAccount deve declarar automountServiceAccountToken: false no nível de topo do documento.
s10_check_serviceaccount() {
  local sa
  sa="$(s10_doc ServiceAccount "$1")"
  [ -n "$sa" ] || { echo "S-10: ServiceAccount ausente" >&2; return 1; }
  grep -qx 'automountServiceAccountToken: false' <<<"$sa" || { echo "S-10: ServiceAccount sem automountServiceAccountToken: false" >&2; return 1; }
}

# Toda diretiva FROM (sem diferenciar maiúsculas) deve fixar a imagem por @sha256:<64 hex> no próprio token.
s10_check_dockerfile() {
  awk '
    { line = $0; sub(/[[:space:]]*#.*$/, "", line) }
    tolower(line) ~ /^[[:space:]]*from[[:space:]]/ {
      n++
      m = split(line, tok, /[[:space:]]+/)
      img = ""
      for (j = 2; j <= m; j++) { if (tok[j] == "") continue; if (tok[j] ~ /^--/) continue; img = tok[j]; break }
      if (img ~ /^[^@[:space:]]+@sha256:[0-9a-f]{64}$/) { pinned = 1 } else if (!(tolower(img) in alias)) {
        print "S-10: FROM sem digest sha256: " $0 > "/dev/stderr"; bad = 1
      }
      for (j = 2; j < m; j++) if (tolower(tok[j]) == "as") alias[tolower(tok[j + 1])] = 1
    }
    END {
      if (n < 1) { print "S-10: Dockerfile sem diretiva FROM" > "/dev/stderr"; bad = 1 }
      exit bad
    }' "$1"
}

