-- Seed manual até o cadastro de fontes ter tela/endpoint próprio (CLAUDE.md §5.2).
-- Quando existir CRUD de fonte_dado, este seed é substituído por dado real da tela —
-- a estrutura da tabela não muda.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'CAR - Imóveis (PR)',
    'SFB/SICAR',
    'API_REST',
    'https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr&outputFormat=application/json',
    'GEOJSON',
    1
);
