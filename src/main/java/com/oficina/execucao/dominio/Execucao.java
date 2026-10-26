package com.oficina.execucao.dominio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** One diagnosis or repair job of a service order in a branch queue. */
public final class Execucao {
    private final UUID id;
    private final UUID osId;
    private final long numeroOs;
    private final String filialId;
    private final TipoExecucao tipo;
    private StatusExecucao status;
    private final OrigemComando origem;
    private final int versaoOrcamento;
    private final List<Tarefa> tarefas;
    private String mecanico;
    private final Instant enfileiradoEm;
    private Instant iniciadoEm;
    private Instant concluidoEm;
    private final MotivoRecusa motivoRecusa;
    private final long versao;

    private Execucao(DadosExecucao d) {
        this.id = Objects.requireNonNull(d.id(), "id");
        this.osId = Objects.requireNonNull(d.osId(), "osId");
        this.numeroOs = d.numeroOs();
        this.filialId = d.filialId();
        this.tipo = Objects.requireNonNull(d.tipo(), "tipo");
        this.status = Objects.requireNonNull(d.status(), "status");
        this.origem = Objects.requireNonNull(d.origem(), "origem");
        this.versaoOrcamento = d.versaoOrcamento();
        this.tarefas = new ArrayList<>(d.tarefas());
        this.mecanico = d.mecanico();
        this.enfileiradoEm = Objects.requireNonNull(d.enfileiradoEm(), "enfileiradoEm");
        this.iniciadoEm = d.iniciadoEm();
        this.concluidoEm = d.concluidoEm();
        this.motivoRecusa = d.motivoRecusa();
        this.versao = d.versao();
    }

    public static Execucao diagnostico(UUID osId, long numeroOs, String filialId, int versaoOrcamento,
                                       OrigemComando origem, Instant agora) {
        validarCabecalho(numeroOs, filialId);
        if (versaoOrcamento < 1) throw new RegraNegocioException("Versão do orçamento deve ser positiva.");
        return nova(osId, numeroOs, filialId, TipoExecucao.DIAGNOSTICO, StatusExecucao.NA_FILA, origem,
                versaoOrcamento, List.of(), null, agora);
    }

    public static Execucao reparo(UUID osId, long numeroOs, String filialId, List<Tarefa> servicos,
                                  OrigemComando origem, Instant agora) {
        validarCabecalho(numeroOs, filialId);
        return nova(osId, numeroOs, filialId, TipoExecucao.REPARO, StatusExecucao.NA_FILA, origem, 0,
                servicos, null, agora);
    }

    public static Execucao reparoRecusado(UUID osId, long numeroOs, String filialId, OrigemComando origem,
                                          MotivoRecusa motivo, Instant agora) {
        validarCabecalho(numeroOs, filialId);
        return nova(osId, numeroOs, filialId, TipoExecucao.REPARO, StatusExecucao.RECUSADA, origem, 0,
                List.of(), Objects.requireNonNull(motivo, "motivo"), agora);
    }

    public static Execucao restaurar(DadosExecucao dados) { return new Execucao(dados); }

    public DadosExecucao dados() {
        return new DadosExecucao(id, osId, numeroOs, filialId, tipo, status, origem, versaoOrcamento, tarefas,
                mecanico, enfileiradoEm, iniciadoEm, concluidoEm, motivoRecusa, versao);
    }

    public void iniciar(String mecanico, Instant agora) {
        exigir(StatusExecucao.NA_FILA, "iniciar");
        if (mecanico == null || mecanico.isBlank()) throw new RegraNegocioException("Mecânico obrigatório.");
        this.mecanico = mecanico;
        this.iniciadoEm = milis(agora);
        this.status = StatusExecucao.EM_ANDAMENTO;
    }

    public void registrarTarefa(String descricao, Instant agora) {
        exigir(StatusExecucao.EM_ANDAMENTO, "registrar tarefa");
        tarefas.add(new Tarefa(descricao, 1, agora));
    }

    public void concluir(Instant agora) {
        exigir(StatusExecucao.EM_ANDAMENTO, "concluir");
        this.concluidoEm = milis(agora);
        this.status = StatusExecucao.CONCLUIDA;
    }

    /** Compensation: true when an active job was cancelled; false (no-op) otherwise. */
    public boolean cancelar(Instant agora) {
        if (!status.ativa()) return false;
        this.concluidoEm = milis(agora);
        this.status = StatusExecucao.CANCELADA;
        return true;
    }

    public boolean ocupaVaga() { return tipo == TipoExecucao.REPARO && status.ativa(); }

    public boolean nova() { return versao == 0; }

    public UUID id() { return id; }
    public UUID osId() { return osId; }
    public long numeroOs() { return numeroOs; }
    public String filialId() { return filialId; }
    public TipoExecucao tipo() { return tipo; }
    public StatusExecucao status() { return status; }
    public OrigemComando origem() { return origem; }
    public int versaoOrcamento() { return versaoOrcamento; }
    public List<Tarefa> tarefas() { return List.copyOf(tarefas); }
    public String mecanico() { return mecanico; }
    public Instant enfileiradoEm() { return enfileiradoEm; }
    public Instant iniciadoEm() { return iniciadoEm; }
    public Instant concluidoEm() { return concluidoEm; }
    public MotivoRecusa motivoRecusa() { return motivoRecusa; }
    public long versao() { return versao; }

    private static Execucao nova(UUID osId, long numeroOs, String filialId, TipoExecucao tipo, StatusExecucao status,
                                 OrigemComando origem, int versaoOrcamento, List<Tarefa> tarefas,
                                 MotivoRecusa motivo, Instant agora) {
        return new Execucao(new DadosExecucao(UUID.randomUUID(), osId, numeroOs, filialId, tipo, status, origem,
                versaoOrcamento, tarefas, null, milis(agora), null, null, motivo, 0));
    }

    private static void validarCabecalho(long numeroOs, String filialId) {
        if (numeroOs < 1) throw new RegraNegocioException("Número da OS deve ser positivo.");
        if (filialId == null || filialId.isBlank()) throw new RegraNegocioException("Filial obrigatória.");
    }

    private void exigir(StatusExecucao esperado, String acao) {
        if (status != esperado) {
            throw new RegraNegocioException("Não é possível %s uma execução em %s.".formatted(acao, status));
        }
    }

    private static Instant milis(Instant instante) {
        return Objects.requireNonNull(instante, "instante").truncatedTo(ChronoUnit.MILLIS);
    }
}
