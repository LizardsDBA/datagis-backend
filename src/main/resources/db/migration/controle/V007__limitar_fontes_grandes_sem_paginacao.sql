-- A ingestao ainda nao pagina (grava 1 por 1, sem paginacao — CLAUDE.md §5.6/§9.3).
-- O ArcGIS REST tem um limite de pagina padrao no servidor (~2000, confirmado
-- testando os embargos do IAT/PR: pedimos tudo e voltou so 2000 de ~2113). O WFS do
-- PRODES nao tem esse limite embutido por padrao — sem isso, uma execucao tentaria
-- trazer ~38 mil poligonos de uma vez. Adicionando maxFeatures=1000 so pra manter o
-- teste/demo num tamanho razoavel; subir esse numero (ou implementar paginacao de
-- verdade) fica pra quando o volume real for decidido.
UPDATE ${controleSchema}.fonte_dado
SET url_origem = 'https://terrabrasilis.dpi.inpe.br/geoserver/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=prodes-mata-atlantica-nb:accumulated_deforestation_2000&outputFormat=application/json&cql_filter=state=''PR''&maxFeatures=1000'
WHERE nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)';
