package com.oficina.execucao.dominio;

/** Maximum concurrent active repairs per branch; enforced atomically by the persistence adapter. */
public final class CapacidadeOficina {
    private final int reparosPorFilial;

    public CapacidadeOficina(int reparosPorFilial) {
        if (reparosPorFilial < 1) throw new IllegalArgumentException("Capacidade deve ser positiva");
        this.reparosPorFilial = reparosPorFilial;
    }

    public int limite() { return reparosPorFilial; }
}
