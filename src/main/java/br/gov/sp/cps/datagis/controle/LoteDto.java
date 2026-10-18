package br.gov.sp.cps.datagis.controle;

import java.time.LocalDateTime;

/**
 * DTO para exibição e gestão de lotes de ingestão no portal.
 */
public record LoteDto(
        Long id,
        Long fonteDadoId,
        String fonteDadoNome,
        String entidadeSilver,
        String origemReferencia,
        Integer quantidadeRegistros,
        String status,
        LocalDateTime dataExecucao
) {
    public static LoteDto fromEntity(LoteIngestao lote) {
        if (lote == null) return null;
        FonteDado fonte = lote.getFonteDado();
        Long fonteId = null;
        String fonteNome = null;
        String entidadeSilver = null;
        if (fonte != null) {
            try {
                fonteId = fonte.getId();
                fonteNome = fonte.getNome();
                entidadeSilver = fonte.getEntidadeSilver();
                if ((entidadeSilver == null || entidadeSilver.isBlank()) && fonteNome != null) {
                    String n = fonteNome.toLowerCase(java.util.Locale.ROOT);
                    if (n.contains("reserva") || n.contains("legal")) {
                        entidadeSilver = "reserva_legal";
                    } else if (n.contains("app")) {
                        entidadeSilver = "app_declarada";
                    } else if (n.contains("vegeta") || n.contains("cobertura")) {
                        entidadeSilver = "vegetacao_nativa_declarada";
                    } else if (n.contains("desmatamento") || n.contains("prodes")) {
                        entidadeSilver = "desmatamento";
                    } else if (n.contains("embargo")) {
                        entidadeSilver = "embargo_ambiental";
                    } else if (n.contains("indígena") || n.contains("indigena") || n.contains("uc") || n.contains("protegida")) {
                        entidadeSilver = "area_protegida";
                    } else if (n.contains("município") || n.contains("municipio") || n.contains("ibge")) {
                        entidadeSilver = "municipio";
                    } else if (n.contains("foco") || n.contains("queimada")) {
                        entidadeSilver = "foco_calor";
                    } else if (n.contains("car") || n.contains("imovel") || n.contains("imóvel")) {
                        entidadeSilver = "imovel_rural";
                    }
                }
            } catch (Exception e) {
                fonteNome = "Fonte #" + (fonteId != null ? fonteId : "");
            }
        }
        return new LoteDto(
                lote.getId(),
                fonteId,
                fonteNome != null ? fonteNome : (fonteId != null ? "Fonte #" + fonteId : "—"),
                entidadeSilver,
                lote.getOrigemReferencia(),
                lote.getQuantidadeRegistros() != null ? lote.getQuantidadeRegistros() : 0,
                lote.getStatus(),
                lote.getDataExecucao()
        );
    }
}
