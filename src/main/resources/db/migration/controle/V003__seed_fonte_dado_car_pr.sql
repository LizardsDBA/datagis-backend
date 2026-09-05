-- Cadastra o CAR na mao, ate a tela de cadastro de fontes existir.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'CAR - Imóveis (PR)',
    'SFB/SICAR',
    'API_REST',
    'https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr&outputFormat=application/json',
    'GEOJSON',
    1
);
