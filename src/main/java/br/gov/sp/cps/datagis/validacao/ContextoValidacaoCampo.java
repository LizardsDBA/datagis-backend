package br.gov.sp.cps.datagis.validacao;

import lombok.Builder;

/**
 * Encapsula as regras e metadados de validação configurados para uma coluna
 * em {@code controle.mapeamento_coluna}.
 */
@Builder
public record ContextoValidacaoCampo(
        String colunaOrigem,
        String atributoDestino,
        String destinoTipo,
        String tipoDado,
        boolean obrigatorio,
        Double valorMinimo,
        String tabelaReferencia,
        String referenciaEntidadeSilver,
        boolean validarCpfCnpj,
        boolean validarGeometria,
        boolean validarRecortePr,
        boolean impedirRegressao,
        String regrasExtras) {

    public static ContextoValidacaoCampo simples(String colunaOrigem, String atributoDestino, boolean obrigatorio) {
        return ContextoValidacaoCampo.builder()
                .colunaOrigem(colunaOrigem)
                .atributoDestino(atributoDestino)
                .obrigatorio(obrigatorio)
                .build();
    }
}
