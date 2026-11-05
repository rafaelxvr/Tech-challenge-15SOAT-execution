package com.oficina.execucao.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Process-level inputs the AWS SDK reads on its own (environment variables and JVM system properties),
 * outside the Spring properties. Injectable so tests never touch the real process environment.
 */
public final class AmbienteAws {
    private final Function<String, String> variaveis;
    private final Function<String, String> propriedadesJvm;
    private final Path diretorioHome;

    private AmbienteAws(Function<String, String> variaveis, Function<String, String> propriedadesJvm, Path diretorioHome) {
        this.variaveis = variaveis;
        this.propriedadesJvm = propriedadesJvm;
        this.diretorioHome = diretorioHome;
    }

    public static AmbienteAws doProcesso() {
        String home = System.getProperty("user.home");
        return new AmbienteAws(System::getenv, System::getProperty, home == null || home.isBlank() ? null : Path.of(home));
    }

    static AmbienteAws de(Map<String, String> variaveis, Map<String, String> propriedadesJvm) {
        return new AmbienteAws(variaveis::get, propriedadesJvm::get, null);
    }

    /** Same as {@link #de(Map, Map)} but with the home directory the shared AWS files are read from. */
    static AmbienteAws de(Map<String, String> variaveis, Map<String, String> propriedadesJvm, Path diretorioHome) {
        return new AmbienteAws(variaveis::get, propriedadesJvm::get, diretorioHome);
    }

    /** True when the environment variable or the JVM property is set to a non-blank value. */
    boolean definido(String variavel, String propriedadeJvm) {
        return (variavel != null && preenchido(variaveis.apply(variavel)))
                || (propriedadeJvm != null && preenchido(propriedadesJvm.apply(propriedadeJvm)));
    }

    /** Keys set in the [default] profile of ~/.aws/credentials (a null return would hide read failures, so none). */
    Set<String> chavesDoPerfilDefaultEmCredenciais() { return chaves(".aws/credentials", false); }

    /** Keys set in the [default] / [profile default] section of ~/.aws/config. */
    Set<String> chavesDoPerfilDefaultEmConfig() { return chaves(".aws/config", true); }

    /** Fails closed: an existing file that cannot be read is reported through {@link #ARQUIVO_ILEGIVEL}. */
    static final String ARQUIVO_ILEGIVEL = "<arquivo ilegivel>";

    private Set<String> chaves(String relativo, boolean config) {
        Set<String> chaves = new HashSet<>();
        if (diretorioHome == null) return chaves;
        Path arquivo = diretorioHome.resolve(relativo);
        if (!Files.exists(arquivo)) return chaves;
        try {
            boolean noPerfil = false;
            for (String linha : Files.readAllLines(arquivo)) {
                String l = linha.strip();
                if (l.isEmpty() || l.startsWith("#") || l.startsWith(";")) continue;
                if (l.startsWith("[")) {
                    int fim = l.indexOf(']');
                    String secao = fim < 0 ? l.substring(1) : l.substring(1, fim);
                    secao = secao.strip();
                    noPerfil = secao.equals("default") || (config && secao.replaceAll("\\s+", " ").equals("profile default"));
                } else if (noPerfil && l.indexOf('=') > 0 && !linha.startsWith(" ") && !linha.startsWith("\t")) {
                    chaves.add(l.substring(0, l.indexOf('=')).strip().toLowerCase());
                }
            }
        } catch (IOException | RuntimeException e) {
            chaves.add(ARQUIVO_ILEGIVEL);
        }
        return chaves;
    }

    String variavel(String nome) { return variaveis.apply(nome); }

    /** Value of the property, used only for boolean switches (never echoed in messages). */
    String propriedade(String nome) { return propriedadesJvm.apply(nome); }

    private static boolean preenchido(String valor) { return valor != null && !valor.isBlank(); }
}
