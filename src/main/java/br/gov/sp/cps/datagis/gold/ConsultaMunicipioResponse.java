package br.gov.sp.cps.datagis.gold;

/**
 * Uma linha da visao "por municipio" da tela de consulta de indicadores
 * (LIZARDS-34).
 *
 * <p>
 * Cada indicador vem como media PONDERADA PELA AREA dos imoveis do municipio,
 * nao media
 * simples: um municipio com uma fazenda de 10 mil hectares e cinquenta sitios
 * de 2 hectares
 * teria resultados completamente diferentes nos dois calculos, e para indicador
 * ambiental o
 * que interessa e o territorio, nao a contagem de proprietarios.
 *
 * <p>
 * {@code qtdImoveis} e {@code areaTotalHa} vao junto de proposito: sao o
 * contexto que
 * permite desconfiar do numero. Municipio com 2 imoveis e 60 mil hectares
 * avisa, sozinho,
 * que uma propriedade esta mandando na media.
 */
public record ConsultaMunicipioResponse(
        Long codIbge,
        String nome,
        Long qtdImoveis,
        Double areaTotalHa,
        Double valorIcv,
        Double valorIrl,
        Double valorIapp,
        Double valorIsap,
        Double valorIae,
        Double valorIdesmat,
        Double valorIfc) {
}