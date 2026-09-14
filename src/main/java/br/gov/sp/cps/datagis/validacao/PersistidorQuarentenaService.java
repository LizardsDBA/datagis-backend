package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Responsável pela persistência física dos registros reprovados
 * nas tabelas espelho da camada de Quarentena (quarentena.q_*).
 */
@Service
public class PersistidorQuarentenaService {

    private static final Logger log = LoggerFactory.getLogger(PersistidorQuarentenaService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String quarentenaSchema;
    private final ObjectMapper objectMapper;
    private final Map<String, Long> tiposErroCache = new ConcurrentHashMap<>();
    private volatile Long statusPendenteId = null;

    public PersistidorQuarentenaService(
            JdbcTemplate jdbcTemplate,
            @Value("${spring.flyway.placeholders.quarentenaSchema:QUARENTENA}") String quarentenaSchema,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.quarentenaSchema = (quarentenaSchema != null && !quarentenaSchema.isBlank())
                ? quarentenaSchema.trim()
                : "QUARENTENA";
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    /**
     * Persiste uma lista de registros rejeitados na tabela espelho correspondente da entidade.
     */
    @Transactional
    public int persistirRejeitados(String entidade, List<RegistroRejeitadoQuarentena> rejeitados) {
        if (rejeitados == null || rejeitados.isEmpty()) {
            return 0;
        }

        String tabelaEspelho = resolverTabelaEspelho(entidade);
        Long statusId = obterStatusPendenteId();

        int inseridos = 0;
        for (RegistroRejeitadoQuarentena rej : rejeitados) {
            RegistroBruto reg = rej.registroBruto();
            Long tipoErroId = obterTipoErroId(rej.tipoErro());

            String sql = "INSERT INTO " + quarentenaSchema + "." + tabelaEspelho + " (" +
                    "registro_bruto_id, lote_ingestao_id, fonte_dado_id, " +
                    "payload_original, tipo_erro_id, campo_problema, " +
                    "valor_recebido, motivo_rejeicao, status_analise_id, geometria_wkt" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try {
                jdbcTemplate.update(
                        sql,
                        reg != null ? reg.getId() : null,
                        reg != null ? reg.getLoteIngestaoId() : null,
                        reg != null ? reg.getFonteDadoId() : null,
                        reg != null ? reg.getPayload() : null,
                        tipoErroId,
                        rej.campoProblema(),
                        rej.valorRecebido() != null ? truncar(rej.valorRecebido(), 4000) : null,
                        rej.motivoRejeicao() != null ? truncar(rej.motivoRejeicao(), 500) : null,
                        statusId,
                        reg != null ? reg.getGeometriaWkt() : null
                );
                inseridos++;
            } catch (Exception e) {
                log.error("Erro ao persistir registro bruto {} na quarentena {}: {}",
                        reg != null ? reg.getId() : "null", tabelaEspelho, e.getMessage(), e);
            }
        }

        log.info("Persistidos {} registros rejeitados em {}.{}", inseridos, quarentenaSchema, tabelaEspelho);
        return inseridos;
    }

    public String resolverTabelaEspelho(String entidade) {
        if (entidade == null || entidade.isBlank()) {
            return "q_imovel_rural";
        }
        String limpa = entidade.trim().toLowerCase(Locale.ROOT);
        if (limpa.startsWith("q_")) {
            return limpa;
        }
        return "q_" + limpa;
    }

    private Long obterTipoErroId(String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return 1L; // Fallback
        }
        String codUpper = codigo.trim().toUpperCase(Locale.ROOT);
        return tiposErroCache.computeIfAbsent(codUpper, c -> {
            try {
                String sql = "SELECT id FROM " + quarentenaSchema + ".tipo_erro_quarentena WHERE codigo = ?";
                List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"), c);
                return ids.isEmpty() ? 1L : ids.get(0);
            } catch (Exception e) {
                log.warn("Falha ao buscar tipo_erro_id para {}: {}", c, e.getMessage());
                return 1L;
            }
        });
    }

    private Long obterStatusPendenteId() {
        if (statusPendenteId == null) {
            synchronized (this) {
                if (statusPendenteId == null) {
                    try {
                        String sql = "SELECT id FROM " + quarentenaSchema + ".status_analise_quarentena WHERE codigo = 'PENDENTE'";
                        List<Long> ids = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong("id"));
                        statusPendenteId = ids.isEmpty() ? 1L : ids.get(0);
                    } catch (Exception e) {
                        statusPendenteId = 1L;
                    }
                }
            }
        }
        return statusPendenteId;
    }

    private String truncar(String texto, int maxLen) {
        if (texto == null) return null;
        return texto.length() > maxLen ? texto.substring(0, maxLen) : texto;
    }
}
