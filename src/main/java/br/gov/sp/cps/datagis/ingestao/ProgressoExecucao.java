package br.gov.sp.cps.datagis.ingestao;

import java.time.LocalDateTime;

/**
 * Estado de progresso de uma execução de ingestão, mantido só em memória (ver
 * {@link ProgressoExecucaoRegistry}) — não é persistido no banco.
 *
 * @param totalEstimado null quando o total não é conhecido de antemão (ex.: GeoJSON
 *                       paginado, antes de buscar a última página; CSV, durante o
 *                       download). Nesse caso a tela mostra "N registros processados"
 *                       sem percentual, em vez de inventar um número.
 * @param detalhe        texto curto opcional (ex.: "página 3", "nome-do-arquivo.dbf").
 */
public record ProgressoExecucao(
        FaseExecucao fase,
        long registrosProcessados,
        Long totalEstimado,
        String detalhe,
        LocalDateTime atualizadoEm) {
}
