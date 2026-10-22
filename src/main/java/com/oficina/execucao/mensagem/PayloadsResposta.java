package com.oficina.execucao.mensagem;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oficina.execucao.dominio.MotivoRecusa;

import java.time.Instant;
import java.util.UUID;

/** Reply payloads exactly as Plan 0 §4.3 and contracts/{Tipo}/v1.json. */
public final class PayloadsResposta {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private PayloadsResposta() {}

    public static ObjectNode diagnosticoConcluido(UUID execucaoId) { return F.objectNode().put("execucaoId", execucaoId.toString()); }
    public static ObjectNode diagnosticoRecusado(MotivoRecusa motivo) { return F.objectNode().put("motivo", motivo.name()); }
    public static ObjectNode reparoIniciado(UUID execucaoId) { return F.objectNode().put("execucaoId", execucaoId.toString()); }
    public static ObjectNode reparoRecusado(MotivoRecusa motivo) { return F.objectNode().put("motivo", motivo.name()); }

    public static ObjectNode execucaoFinalizada(UUID execucaoId, Instant concluidoEm) {
        return F.objectNode().put("execucaoId", execucaoId.toString()).put("concluidoEm", concluidoEm.toString());
    }

    public static ObjectNode execucaoCancelada(int canceladas) { return F.objectNode().put("canceladas", canceladas); }
    public static ObjectNode compensacaoFalhou() { return F.objectNode().put("compensacao", TiposMensagem.CANCELAR_EXECUCAO); }
}
