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
 *
 * <p>
 * {@code qtdImoveisComIntersecao} (mapa de cobertura, LIZARDS-35): imoveis do municipio
 * que cruzam alguma camada de restricao - ISAP, IAE, IDESMAT ou IFC maior que zero.
 * Vem dos indicadores, calculados sobre a geometria completa da Silver, e nao do
 * desenho do mapa, que e simplificado (~5 m) e erraria nas sobreposicoes pequenas.
 */
public record ConsultaMunicipioResponse(
        Long codIbge,
        String nome,
        Long qtdImoveis,
        Long qtdImoveisComIntersecao,
        Double areaTotalHa,
        Double valorIcv,
        Double valorIrl,
        Double valorIapp,
        Double valorIsap,
        Double valorIae,
        Double valorIdesmat,
        Double valorIfc,
        /** Soma do déficit(-)/excedente(+) de RL dos imóveis do município, em
         *  hectares — pág. 6 do slide oficial do desafio. */
        Double valorIrlDeficitHa,
        /** Soma do passivo de APP a recuperar dos imóveis do município, em
         *  hectares — pág. 6 do slide oficial do desafio. */
        Double valorIappPassivoHa) {
}