-- Fonte de teste pra comparar o mesmo dado (perimetro do imovel CAR/PR) vindo por dois
-- caminhos diferentes: a fonte 1 ja existe via WFS (API_REST/GEOJSON); esta e a mesma
-- informacao via Shapefile baixado manualmente em consultapublica.car.gov.br e enviado
-- por upload. url_origem fica nula de proposito (UPLOAD_MANUAL nao tem URL).
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, formato_esperado, ativo)
VALUES (
    'CAR - Imóveis (PR) via Shapefile (upload)',
    'SFB/SICAR',
    'UPLOAD_MANUAL',
    'SHAPEFILE',
    1
);
