-- A URL do CAR nao tinha o filtro de municipio embutido (ele era montado em Java,
-- a partir de app.car.municipios-ibge). Agora que qualquer fonte GeoJSON e generica
-- (nenhum codigo especifico de CAR mais), a URL guardada precisa vir pronta com tudo.
UPDATE ${controleSchema}.fonte_dado
SET url_origem = 'https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr&outputFormat=application/json&CQL_FILTER=cod_municipio_ibge%20IN%20(4106902,4115200,4119905)'
WHERE nome = 'CAR - Imóveis (PR)';

-- Novas fontes reais, validadas em docs/fontes-de-dados-v2.md (2026-09-08). Cobrem
-- os dois formatos genericos que existem hoje: WFS (GeoServer) devolvendo GeoJSON
-- puro, e ArcGIS REST (geoportal do PR) pedido com f=geojson (o proprio ArcGIS sabe
-- devolver GeoJSON padrao, entao nao precisa de um parser proprio pra ele - o mesmo
-- executor de GEOJSON atende os dois).
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'ICMBio - Unidades de Conservação (PR)',
    'ICMBio/MMA',
    'API_REST',
    'https://geoservicos.inde.gov.br/geoserver/ICMBio/ows?service=WFS&version=2.0.0&request=GetFeature&typeNames=ICMBio:limiteucsfederais_a&outputFormat=application/json&bbox=-54.7,-26.8,-48.0,-22.4,EPSG:4674',
    'GEOJSON',
    1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'INPE (PRODES) - Desmatamento Mata Atlântica (PR)',
    'INPE',
    'API_REST',
    'https://terrabrasilis.dpi.inpe.br/geoserver/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=prodes-mata-atlantica-nb:accumulated_deforestation_2000&outputFormat=application/json&cql_filter=state=''PR''',
    'GEOJSON',
    1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Focos de Calor (INPE)',
    'IAT (Paraná)',
    'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Focos_de_Calor_INPE/FeatureServer/0/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON',
    1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Embargos IBAMA',
    'IBAMA (via IAT/PR)',
    'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/9/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON',
    1
);

-- Exemplo de fonte CSV (prova o executor de CSV) — a versao do IAT/PR acima ja e a
-- fonte preferida pra focos de calor (mais rica, ja filtrada pro PR); esta fica so
-- pra validar o caminho de ingestao tabular com lat/long.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, campo_geometria_lat, campo_geometria_lon, ativo)
VALUES (
    'INPE (Queimadas) - Focos de calor, Brasil (mensal)',
    'INPE',
    'DOWNLOAD_URL',
    'https://dataserver-coids.inpe.br/queimadas/queimadas/focos/csv/mensal/Brasil/focos_mensal_br_202401.csv',
    'CSV',
    'lat',
    'lon',
    1
);
