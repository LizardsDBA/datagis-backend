-- [REESCRITO] Era so o cadastro manual do CAR ("na mao, ate a tela de cadastro de
-- fontes existir"). Virou o seed CONSOLIDADO de todo o catalogo de fontes do projeto -
-- as 18 fontes nasciam espalhadas por 7 migrations diferentes (V006, V009, V025, V039,
-- V041, V053, e esta), cada uma na ordem em que foi descoberta/cadastrada ao longo das
-- sprints. Pedido do time: reordenar pra uma sequencia que reflita a ORDEM DESEJAVEL DE
-- INGESTAO (pre-requisitos primeiro) em vez da ordem historica de descoberta -
-- consolidar tudo aqui (a migration mais antiga que semeia fonte_dado) e a unica forma
-- de controlar os IDs gerados por IDENTITY sem reescrever o auto-increment a mao.
--
-- Ordem nova, com a razao de cada bloco:
--   1-2  Malha Municipal + Limites de biomas: PRE-REQUISITOS - varias fontes
--        dependem de municipio/bioma existir na Silver antes (integridade cruzada,
--        resolucao de municipio por nome/codigo).
--   3-6  CAR (imoveis + os 3 atributos declarados: APP/RL/vegetacao nativa) - o
--        nucleo do desafio (indicadores ICV/IRL/IAPP dependem disso).
--   7-10 Areas protegidas (ICMBio, FUNAI, MMA, SFB) - mesma familia de entidade
--        Silver (area_protegida), agrupadas.
--   11-14 Embargos e autos de infracao (IBAMA/ICMBio) - families embargo_ambiental
--        e auto_infracao, agrupadas; embargo depois de auto_infracao porque
--        embargo_ambiental referencia auto_infracao via integridade cruzada
--        (mesmo motivo documentado na V052, agora sem efeito pratico porque
--        nenhuma fonte tem cron - ver nota abaixo).
--   15-17 Desmatamento + focos de calor - entidades remanescentes, sem
--        pre-requisito entre si.
--   18   CAR via Shapefile (upload manual) - por ultimo, e so disparo manual por
--        upload, nunca concorre com as demais em agendamento.
--
-- Nenhuma fonte sai com cron_expressao: a equipe decidiu, junto com esta
-- reordenacao, deixar TODAS as fontes sem agendamento automatico por ora (disparo
-- manual via POST /api/fontes/{id}/executar, ou pela tela) - as migrations que
-- preenchiam cron (V035) e corrigiam a ordem de disparo entre fontes dependentes
-- (V052) ficaram sem efeito (ver comentario nelas).
--
-- URLs ja vao com os valores CORRIGIDOS (pos-testes reais contra os servicos) - nao
-- os valores originais de cadastro, que foram corrigidos depois em migrations de
-- fix proprias (V006 so URL do CAR, V056 pras 3 fontes novas do CAR). Essas
-- correcoes ficaram sem efeito pratico aqui (ver comentario nelas) porque o valor
-- certo ja nasce certo.
--
-- SEM entidade_silver aqui de proposito: a coluna so existe a partir do V010 (ADD
-- posterior a esta migration) - setar entidade_silver pra todas as 18 fontes agora
-- vive centralizado no V031 (por NOME, nao por id), que ja roda depois do V010.

-- 1. IBGE - Malha Municipal (PR): pre-requisito (silver.municipio).
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IBGE - Malha Municipal (PR)', 'IBGE (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/municipios_2023/FeatureServer/0/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

-- 2. IBGE - Limites de biomas: pre-requisito (silver.bioma). entidade_silver='bioma'
-- explicito de proposito (sem ele, o heuristico por nome bateria em "ibge" antes de
-- qualquer outra coisa e mandaria pra 'municipio' por engano - ver
-- TransicaoBronzeSilverService.resolverEntidadeSilver). DOWNLOAD_URL porque o IBGE
-- publica um .zip estatico e estavel direto no FTP deles. Sem cron de proposito:
-- biomas do IBGE mudam raramente.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IBGE - Limites de biomas', 'IBGE', 'DOWNLOAD_URL',
    'http://geoftp.ibge.gov.br/informacoes_ambientais/estudos_ambientais/biomas/vetores/2025_Biomas-e-Sistema-Costeiro-Marinho-do-Brasil-1-250000_shp.zip',
    'SHAPEFILE', 1
);

-- 3. CAR - Imoveis (PR): nucleo do desafio. URL ja com o filtro de circuito
-- (Curitiba/Maringa/Ponta Grossa, CLAUDE.md §5.2) embutido.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'CAR - Imóveis (PR)', 'SFB/SICAR', 'API_REST',
    'https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr&outputFormat=application/json&CQL_FILTER=cod_municipio_ibge%20IN%20(4106902,4115200,4119905)',
    'GEOJSON', 1
);

-- 4-6. As 3 fontes que resolvem IAPP/IRL/ICV (achadas em 2026-10-07, tutorial
-- §0-bis). entidade_silver ja correto (nao NULL - entrar NULL faria o fallback por
-- nome bater em "car" e tentar gravar isso dentro de silver.imovel_rural, risco real
-- documentado na V055 original). URL ja com o mesmo filtro de circuito do CAR
-- (cod_imovel LIKE 'PR-{ibge}-%'), senao o volume e o estado inteiro (~350-390 mil
-- registros cada) e o limite de 12GB do Oracle Free nao aguenta.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - APP Total (CAR)', 'SFB/SICAR (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/1/query?where=cod_imovel+LIKE+%27PR-4106902-%25%27+OR+cod_imovel+LIKE+%27PR-4115200-%25%27+OR+cod_imovel+LIKE+%27PR-4119905-%25%27&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Reserva Legal (CAR)', 'SFB/SICAR (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/2/query?where=cod_imovel+LIKE+%27PR-4106902-%25%27+OR+cod_imovel+LIKE+%27PR-4115200-%25%27+OR+cod_imovel+LIKE+%27PR-4119905-%25%27&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Vegetação Nativa (CAR)', 'SFB/SICAR (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/3/query?where=cod_imovel+LIKE+%27PR-4106902-%25%27+OR+cod_imovel+LIKE+%27PR-4115200-%25%27+OR+cod_imovel+LIKE+%27PR-4119905-%25%27&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

-- 7-10. Areas protegidas (mesma entidade_silver='area_protegida'): ICMBio (so UC
-- federal), FUNAI (terras indigenas, via copia do geoportal do PR), MMA (CNUC
-- nacional, inclui UC estadual/municipal - nao e redundante com ICMBio), SFB
-- (Cadastro Nacional de Florestas Publicas).
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'ICMBio - Unidades de Conservação (PR)', 'ICMBio/MMA', 'API_REST',
    'https://geoservicos.inde.gov.br/geoserver/ICMBio/ows?service=WFS&version=2.0.0&request=GetFeature&typeNames=ICMBio:limiteucsfederais_a&outputFormat=application/json&bbox=-54.7,-26.8,-48.0,-22.4,EPSG:4674',
    'GEOJSON', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Terras Indígenas (FUNAI)', 'FUNAI (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Geoprocessamento/Terras_indigenas_funai/FeatureServer/0/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'MMA - Unidades de Conservacao (CNUC nacional)', 'MMA', 'DOWNLOAD_URL',
    'https://dados.mma.gov.br/dataset/44b6dc8a-dc82-4a84-8d95-1b0da7c85dac/resource/20327e02-d4fe-4a1b-bd12-e381ab461d97/download/shp_cnuc_2025_03.zip',
    'SHAPEFILE', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'SFB - Cadastro Nacional de Florestas Publicas (CNFP)', 'SFB', 'DOWNLOAD_URL',
    'https://mapas.florestal.gov.br/portal/sharing/rest/content/items/2b752dfbf9254920bf8990ce363918af/data',
    'SHAPEFILE', 1
);

-- 11-14. Embargos (entidade_silver='embargo_ambiental') e autos de infracao
-- (entidade_silver='auto_infracao'), IBAMA e ICMBio via geoportal do PR. Autos
-- entram ANTES de embargos na ordem (ids 13-14 antes de 11-12 ficaria mais correto
-- topologicamente, mas a ordem de ID aqui e so cadastro - a dependencia real
-- (embargo_ambiental referencia auto_infracao) so importava pra ordem de CRON, que
-- nao existe mais - ver V052).
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Embargos IBAMA', 'IBAMA (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/9/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Embargos ICMBio', 'ICMBio (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/5/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Autos de Infração IBAMA', 'IBAMA (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/8/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Autos de Infração ICMBio', 'ICMBio (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer/4/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

-- 15. INPE (PRODES) - Desmatamento Mata Atlantica (PR).
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'INPE (PRODES) - Desmatamento Mata Atlântica (PR)', 'INPE', 'API_REST',
    'https://terrabrasilis.dpi.inpe.br/geoserver/ows?service=WFS&version=1.1.0&request=GetFeature&typeName=prodes-mata-atlantica-nb:accumulated_deforestation_2000&outputFormat=application/json&cql_filter=state=''PR''',
    'GEOJSON', 1
);

-- 16-17. Focos de calor (entidade_silver='foco_calor'): IAT/PR (preferida, mais
-- rica, ja filtrada pro PR) e a amostra CSV do INPE (so pra provar o executor de
-- CSV com lat/lon - ver IngestaoCsvExecutor). campo_geometria_lat/lon da fonte CSV
-- NAO entra aqui - essas 2 colunas so existem a partir do V005 (ADD posterior a
-- esta migration); completado via UPDATE no V006 (que ja roda depois do V005).
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'IAT/PR - Focos de Calor (INPE)', 'IAT (Paraná)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/Focos_de_Calor_INPE/FeatureServer/0/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1
);

INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES (
    'INPE (Queimadas) - Focos de calor, Brasil (mensal)', 'INPE', 'DOWNLOAD_URL',
    'https://dataserver-coids.inpe.br/queimadas/queimadas/focos/csv/mensal/Brasil/focos_mensal_br_202401.csv',
    'CSV', 1
);

-- 18. CAR - Imoveis (PR) via Shapefile (upload manual): mesmo dado da fonte 3, outro
-- caminho (upload manual em vez de API) - comparacao/contingencia. Por ultimo, de
-- proposito: tipo_aquisicao=UPLOAD_MANUAL nunca concorre com agendamento automatico.
INSERT INTO ${controleSchema}.fonte_dado (nome, orgao, tipo_aquisicao, formato_esperado, ativo)
VALUES (
    'CAR - Imóveis (PR) via Shapefile (upload)', 'SFB/SICAR', 'UPLOAD_MANUAL', 'SHAPEFILE', 1
);
