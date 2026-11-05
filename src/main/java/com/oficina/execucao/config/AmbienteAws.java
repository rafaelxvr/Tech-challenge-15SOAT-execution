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
        return de(System::getenv, System::getProperty);
    }

    static AmbienteAws de(Map<String, String> variaveis, Map<String, String> propriedadesJvm) {
        return de(variaveis::get, propriedadesJvm::get);
    }

    /** Same as {@link #de(Map, Map)} but with the {@code user.home} the shared AWS files fall back to (env HOME wins). */
    static AmbienteAws de(Map<String, String> variaveis, Map<String, String> propriedadesJvm, Path userHome) {
        Map<String, String> props = new java.util.HashMap<>(propriedadesJvm);
        if (userHome != null) props.put(USER_HOME, userHome.toString());
        return de(variaveis, props);
    }

    private static AmbienteAws de(Function<String, String> variaveis, Function<String, String> propriedadesJvm) {
        return new AmbienteAws(variaveis, propriedadesJvm, resolverHome(variaveis, propriedadesJvm));
    }

    private static final String USER_HOME = "user.home";

    /** Same order the SDK uses (UserHomeDirectoryUtils): env HOME first, then the user.home property. */
    private static Path resolverHome(Function<String, String> variaveis, Function<String, String> propriedadesJvm) {
        String home = variaveis.apply("HOME");
        if (!preenchido(home)) home = propriedadesJvm.apply(USER_HOME);
        if (!preenchido(home)) return null;
        try {
            return Path.of(home);
        } catch (java.nio.file.InvalidPathException e) {
            return null;
        }
    }

    /** True when the environment variable or the JVM property is set to a non-blank value. */
    boolean definido(String variavel, String propriedadeJvm) {
        return (variavel != null && preenchido(variaveis.apply(variavel)))
                || (propriedadeJvm != null && preenchido(propriedadesJvm.apply(propriedadeJvm)));
    }

    /** Keys set in the [default] profile of ~/.aws/credentials (a null return would hide read failures, so none). */
    Set<String> chavesDoPerfilDefaultEmCredenciais() { return chaves(".aws/credentials", false, "default"); }

    /** Keys set in the [default] / [profile default] section of ~/.aws/config. */
    Set<String> chavesDoPerfilDefaultEmConfig() { return chaves(".aws/config", true, "default"); }

    /** Profile the SDK picks: the aws.profile JVM property precedes AWS_PROFILE, else default. */
    String perfilEfetivo() {
        String nome = propriedadesJvm.apply("aws.profile");
        if (!preenchido(nome)) nome = variaveis.apply("AWS_PROFILE");
        return preenchido(nome) ? nome.strip() : "default";
    }

    /** Keys of the effective profile in ~/.aws/credentials. */
    Set<String> chavesDoPerfilEfetivoEmCredenciais() { return chaves(".aws/credentials", false, perfilEfetivo()); }

    /** Keys of the effective profile in ~/.aws/config. */
    Set<String> chavesDoPerfilEfetivoEmConfig() { return chaves(".aws/config", true, perfilEfetivo()); }

    /** Fails closed: an existing file that cannot be read is reported through {@link #ARQUIVO_ILEGIVEL}. */
    static final String ARQUIVO_ILEGIVEL = "<arquivo ilegivel>";

    private Set<String> chaves(String relativo, boolean config, String perfil) {
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
                    String normalizada = secao.replaceAll("\\s+", " ");
                    noPerfil = normalizada.equals(perfil) || (config && normalizada.equals("profile " + perfil));
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
