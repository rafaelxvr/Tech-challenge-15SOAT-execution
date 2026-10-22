package com.oficina.execucao.mensagem;

public final class TiposMensagem {
    public static final String INICIAR_DIAGNOSTICO = "IniciarDiagnostico";
    public static final String INICIAR_REPARO = "IniciarReparo";
    public static final String CANCELAR_EXECUCAO = "CancelarExecucao";
    public static final String DIAGNOSTICO_CONCLUIDO = "DiagnosticoConcluido";
    public static final String DIAGNOSTICO_RECUSADO = "DiagnosticoRecusado";
    public static final String REPARO_INICIADO = "ReparoIniciado";
    public static final String REPARO_RECUSADO = "ReparoRecusado";
    public static final String EXECUCAO_FINALIZADA = "ExecucaoFinalizada";
    public static final String EXECUCAO_CANCELADA = "ExecucaoCancelada";
    public static final String COMPENSACAO_FALHOU = "CompensacaoFalhou";

    private TiposMensagem() {}
}
