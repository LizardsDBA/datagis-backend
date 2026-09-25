package br.gov.sp.cps.datagis.conjuntosdados;

import java.time.LocalDateTime;

/** Cabeçalho da tela de Conjunto de Dados: qual lote está sendo comparado e de que fonte. */
public record LoteResumoDto(
        Long loteId,
        Long fonteDadoId,
        String fonteDadoNome,
        String entidadeSilver,
        Integer quantidadeRegistros,
        String status,
        LocalDateTime dataExecucao) {
}
