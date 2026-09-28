package br.gov.sp.cps.datagis.ingestao;

/**
 * Um registro já pronto pra virar {@code bronze.registro_bruto} — o formato de origem
 * (GeoJSON, CSV, o que for) já foi resolvido; só falta gravar. {@code geometriaWkt} é
 * {@code null} quando o registro não tem geometria (fonte só tabular).
 */
public record RegistroPreparado(String payloadJson, String geometriaWkt) {
}
