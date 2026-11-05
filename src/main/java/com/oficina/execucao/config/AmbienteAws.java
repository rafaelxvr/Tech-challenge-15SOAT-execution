package com.oficina.execucao.config;

import java.util.Map;
import java.util.function.Function;

/**
 * Process-level inputs the AWS SDK reads on its own (environment variables and JVM system properties),
 * outside the Spring properties. Injectable so tests never touch the real process environment.
 */
public final class AmbienteAws {
    private final Function<String, String> variaveis;
    private final Function<String, String> propriedadesJvm;

    private AmbienteAws(Function<String, String> variaveis, Function<String, String> propriedadesJvm) {
        this.variaveis = variaveis;
        this.propriedadesJvm = propriedadesJvm;
    }

    public static AmbienteAws doProcesso() { return new AmbienteAws(System::getenv, System::getProperty); }

    static AmbienteAws de(Map<String, String> variaveis, Map<String, String> propriedadesJvm) {
        return new AmbienteAws(variaveis::get, propriedadesJvm::get);
    }

    /** True when the environment variable or the JVM property is set to a non-blank value. */
    boolean definido(String variavel, String propriedadeJvm) {
        return preenchido(variaveis.apply(variavel))
                || (propriedadeJvm != null && preenchido(propriedadesJvm.apply(propriedadeJvm)));
    }

    String variavel(String nome) { return variaveis.apply(nome); }

    private static boolean preenchido(String valor) { return valor != null && !valor.isBlank(); }
}
