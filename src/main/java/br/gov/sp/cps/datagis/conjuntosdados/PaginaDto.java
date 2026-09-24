package br.gov.sp.cps.datagis.conjuntosdados;

import java.util.List;

/**
 * Envelope de paginação com total — diferente do padrão de lista crua já usado em
 * {@code QuarentenaService}, aqui o volume por lote pode chegar a milhares de linhas
 * (ex.: CAR/PR), então a tela precisa saber quantas páginas existem de verdade.
 */
public record PaginaDto<T>(List<T> itens, long total, int pagina, int tamanho) {
}
