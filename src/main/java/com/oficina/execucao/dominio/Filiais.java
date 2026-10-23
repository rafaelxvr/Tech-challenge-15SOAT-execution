package com.oficina.execucao.dominio;

import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;

public final class Filiais {
    private final Set<String> conhecidas;

    public Filiais(Collection<String> filiais) {
        this.conhecidas = filiais.stream().map(String::trim).filter(f -> !f.isEmpty()).collect(Collectors.toUnmodifiableSet());
        if (conhecidas.isEmpty()) throw new IllegalArgumentException("Ao menos uma filial é obrigatória");
    }

    public boolean conhece(String filialId) { return filialId != null && conhecidas.contains(filialId); }
}
