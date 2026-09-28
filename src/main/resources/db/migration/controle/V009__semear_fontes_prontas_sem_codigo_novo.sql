-- "Grupo 1" do levantamento em docs/fontes-de-dados-v2.md: fontes que ja tem caminho
-- tecnico confirmado e cabem no executor GEOJSON generico que ja existe (WFS ou ArcGIS
-- REST com f=geojson) - nenhum codigo novo precisou ser escrito, so cadastro.
--
-- Ficam de fora deste grupo (propositalmente):
-- - IBGE Biomas, INCRA Quilombolas, INCRA Assentamentos: formato SHAPEFILE, exige um
--   IngestaoShapefileExecutor que ainda nao existe (gt-shapefile ja esta no pom.xml,
--   so nao foi usado ainda) - fatia futura.
-- - CAR Temas (APP/RL/vegetacao nativa): bloqueado por credenciamento externo
--   (OAuth2 + IP allowlist no Conecta Gov/Serpro) - nao cadastrar como ativo ate ter
--   acesso real (ver docs/fontes-de-dados-v2.md secao 2).

-- IBGE - Malha municipal (PR), via geoportal do IAT: mais simples que iterar a API do
-- IBGE municipio a municipio - vem tudo numa consulta so, ja no SRID certo.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IBGE - Malha Municipal (PR)',
    'IBGE (via IAT/PR)',
    'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/municipios_2023/FeatureServer/0/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON',
    1
);

-- FUNAI - Terras Indigenas (PR): preferimos a copia do geoportal do PR a ir direto na
-- FUNAI - mesmo schema, sem as ressalvas tecnicas da fonte federal (WFS 1.1.0 forcado +
-- User-Agent de navegador pra passar o WAF).
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Terras Indígenas (FUNAI)',
    'FUNAI (via IAT/PR)',
    'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Geoprocessamento/Terras_indigenas_funai/FeatureServer/0/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON',
    1
);

-- Mesmo servico ArcGIS ja cadastrado pra Embargos IBAMA (layer 9), so outras layers:
-- 5 = areas embargadas ICMBio, 8 = autos de infracao IBAMA, 4 = autos de infracao ICMBio.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Embargos ICMBio',
    'ICMBio (via IAT/PR)',
    'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/5/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON',
    1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Autos de Infração IBAMA',
    'IBAMA (via IAT/PR)',
    'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/8/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON',
    1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Autos de Infração ICMBio',
    'ICMBio (via IAT/PR)',
    'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/4/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON',
    1
);
