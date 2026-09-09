-- V007 limitou a URL do PRODES a maxFeatures=1000 pra não trazer os ~38 mil poligonos
-- de uma vez, antes de existir paginacao de verdade. Agora o IngestaoGeoJsonExecutor
-- pagina sozinho (PaginadorUrlGeoJson) e ignora/substitui qualquer maxFeatures/count/
-- startIndex/resultOffset/resultRecordCount que estiver salvo na URL — o cap manual
-- virou so ruido visual na tela de cadastro. Removendo pra a URL cadastrada refletir o
-- que realmente é pedido ao servico de origem.
UPDATE ${controleSchema}.fonte_dado
SET url_origem = 'https://terrabrasilis.dpi.inpe.br/geoserver/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=prodes-mata-atlantica-nb:accumulated_deforestation_2000&outputFormat=application/json&cql_filter=state=''PR'''
WHERE nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)';
