package br.gov.sp.cps.datagis.conjuntosdados;

import java.util.Map;

/**
 * Um registro (Bronze ou Silver) representado de forma genérica — {@code dados} são as
 * colunas/chaves exatamente como existem na origem, sem nenhum tratamento, pra tela
 * montar a tabela dinamicamente sem hardcode de coluna por entidade.
 */
public record RegistroCamadaDto(Long registroBrutoId, boolean temGeometria, String geometriaWkt, Map<String, Object> dados) {
}
