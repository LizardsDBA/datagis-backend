package br.gov.sp.cps.datagis.gold;

import java.time.LocalDateTime;

/**
 * Uma linha da visao "por imovel" da tela de consulta de indicadores
 * (LIZARDS-34).
 *
 * <p>
 * E o {@code gold.imovel_publicado} acrescido do nome do municipio, que mora na
 * Silver.
 * A tela precisa do nome na primeira coluna e na busca; so com o codigo IBGE o
 * usuario
 * veria "4106902" em vez de "Curitiba".
 */
public record ConsultaImovelResponse(
        String codImovel,
        Long municipioCodIbge,
        String municipioNome,
        Double areaHa,
        Double valorIcv,
        Double valorIrl,
        Double valorIapp,
        Double valorIsap,
        Double valorIae,
        Double valorIdesmat,
        Double valorIfc,
        LocalDateTime dataPublicacao) {
}