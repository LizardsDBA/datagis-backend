-- Generaliza o mesmo principio ja usado pro CAR (filtro por circuito de homologacao
-- - Curitiba/Maringa/Ponta Grossa, CLAUDE.md §5.2) pras demais fontes que aceitam
-- filtro configuravel na propria URL. Objetivo duplo: reduz o volume ingerido (ajuda
-- o limite de armazenamento) E reduz o tempo de validacao Bronze->Silver (achado
-- real: PRODES com 38.296 registros travava mais de 40min so validando, memoria
-- subindo sem parar - ver docs/gold-procedures-calculo-publicacao.md §9.2). So URL,
-- zero codigo Java novo - mesma filosofia de "lista de municipios e configuracao,
-- nao codigo" do §2.1/§5.2.
--
-- Verificado ao vivo via curl antes de escrever esta migration (contagens reais):
--   Fonte  7 (ICMBio)          28  -> 2    (filtro espacial, 2 UCs cruzam o circuito)
--   Fonte  8 (FUNAI)           25  -> 0    (nenhuma TI nos 3 municipios - real, nao bug)
--   Fonte 11 (Embargos IBAMA)  2.161 -> 99
--   Fonte 12 (Embargos ICMBio) 450  -> 27
--   Fonte 13 (Autos IBAMA)     16.870 -> 11
--   Fonte 14 (Autos ICMBio)    1.333 -> 30
--   Fonte 15 (PRODES)          38.296 -> 1.432
--   Fonte 16 (Focos de Calor)  333.604 -> 0 (nenhum foco nos 3 municipios - real,
--                              faz sentido pra area urbana, nao bug)
--
-- NAO filtradas (fontes 1/2/9/10/17/18), de proposito:
--   - Fonte 1 (Malha Municipal) e 2 (Biomas): precisam ficar com TODO o Parana/Brasil
--     - sao tabelas de referencia usadas por integridade cruzada de outras fontes
--     (ex.: embargo de um municipio qualquer do PR precisa achar aquele municipio na
--     Silver), nao da pra restringir ao circuito sem quebrar isso.
--   - Fonte 9 (MMA) e 10 (SFB): sao download de arquivo .zip inteiro
--     (DOWNLOAD_URL/SHAPEFILE) - nao existe parametro de filtro na URL, o servidor
--     manda o arquivo nacional completo sempre. Reduzir isso exigiria filtrar DEPOIS
--     de baixar, no codigo Java do IngestaoShapefileExecutor - fora do escopo desta
--     migration (que e so configuracao de URL, sem codigo novo).
--   - Fonte 17 (INPE CSV) e 18 (CAR Shapefile upload): mesma situacao de arquivo
--     estatico/upload manual, sem parametro de filtro possivel.
--
-- 2 tecnicas usadas, dependendo da familia de servico:
--   - ArcGIS REST com campo 'municipio' (fontes 11,12,13,14,16) ou 'municipio_' com
--     varios municipios separados por virgula no mesmo valor (fonte 8, FUNAI) ->
--     filtro por atributo (where=municipio IN (...) ou LIKE OR).
--   - WFS sem atributo de municipio usavel (fontes 7 ICMBio, 15 PRODES) -> filtro
--     espacial (CQL_FILTER com BBOX por municipio, OR entre os 3) - bbox de cada
--     municipio calculado a partir da geometria real deles (mesmo servico da fonte 1,
--     Malha Municipal), nao um retangulo arbitrario.

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geoservicos.inde.gov.br/geoserver/ICMBio/ows?service=WFS&version=2.0.0&request=GetFeature&typeNames=ICMBio:limiteucsfederais_a&outputFormat=application/json&CQL_FILTER=BBOX(the_geom,-49.389416,-25.644912,-49.184383,-25.344964,%27EPSG:4674%27)%20OR%20BBOX(the_geom,-50.456479,-25.386648,-49.680156,-24.892010,%27EPSG:4674%27)%20OR%20BBOX(the_geom,-52.100133,-23.555947,-51.836144,-23.253963,%27EPSG:4674%27)'
 WHERE nome = 'ICMBio - Unidades de Conservação (PR)';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/Geoprocessamento/Terras_indigenas_funai/FeatureServer/0/query?where=municipio_+LIKE+%27%25Curitiba%25%27+OR+municipio_+LIKE+%27%25Maring%C3%A1%25%27+OR+municipio_+LIKE+%27%25Ponta+Grossa%25%27&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - Terras Indígenas (FUNAI)';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/9/query?where=municipio+IN+(%27Curitiba%27,%27Maring%C3%A1%27,%27Ponta+Grossa%27)&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - Embargos IBAMA';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/5/query?where=municipio+IN+(%27Curitiba%27,%27Maring%C3%A1%27,%27Ponta+Grossa%27)&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - Embargos ICMBio';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/8/query?where=municipio+IN+(%27Curitiba%27,%27Maring%C3%A1%27,%27Ponta+Grossa%27)&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - Autos de Infração IBAMA';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/4/query?where=municipio+IN+(%27Curitiba%27,%27Maring%C3%A1%27,%27Ponta+Grossa%27)&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - Autos de Infração ICMBio';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://terrabrasilis.dpi.inpe.br/geoserver/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=prodes-mata-atlantica-nb:accumulated_deforestation_2000&outputFormat=application/json&cql_filter=state=%27PR%27%20AND%20(BBOX(geom,-49.389416,-25.644912,-49.184383,-25.344964,%27EPSG:4674%27)%20OR%20BBOX(geom,-50.456479,-25.386648,-49.680156,-24.892010,%27EPSG:4674%27)%20OR%20BBOX(geom,-52.100133,-23.555947,-51.836144,-23.253963,%27EPSG:4674%27))'
 WHERE nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/Focos_de_Calor_INPE/FeatureServer/0/query?where=municipio+IN+(%27Curitiba%27,%27Maring%C3%A1%27,%27Ponta+Grossa%27)&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - Focos de Calor (INPE)';
