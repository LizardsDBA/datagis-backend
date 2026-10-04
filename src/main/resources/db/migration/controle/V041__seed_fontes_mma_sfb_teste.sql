-- Cadastro de teste para 2 fontes novas da linha 6 do slide ("Áreas protegidas e
-- territórios especiais"): MMA (CNUC nacional) e SFB (Cadastro Nacional de Florestas
-- Públicas). Ambas testadas ao vivo antes deste cadastro (curl + inspeção via
-- GeoTools): .zip real, SIRGAS2000 nativo, schema confirmado.
--
-- MMA/CNUC: 3.001 poligonos + 184 pontos. Campo 'esfera' = Federal/Estadual/Municipal
-- (994/1.266/741) - NAO e redundante com a fonte ICMBio ja cadastrada (#2), que so
-- traz UC federal (camada 'limiteucsfederais_a').
--
-- SFB/CNFP: 12.874 registros nacionais. Maioria (~7.700) e Projeto de Assentamento
-- (categoria PA/PAE/PDS/...), ~1.740 sao "Gleba Arrecadada" (Tipo B, categoria nova,
-- ninguem cobre hoje), ~900 sao Terra Indigena e ~1.500 sao UC - estas ultimas se
-- sobrepoe com ICMBio/FUNAI/CNUC ja cadastrados. 'orgao'='INCRA' aparece em 8.042 dos
-- 12.874 registros (62%) - pode fechar o gap de "INCRA quilombolas/assentamentos" do
-- tutorial sem precisar cadastrar as 2 fontes INCRA separadas (a confirmar depois
-- de ver o resultado real da ingestao).
--
-- Mapeamento DELIBERADAMENTE minimo pra este teste: 'categoria'/'orgao'/'bioma' NAO
-- apontam pros atributos canonicos (categoria_area_protegida/orgao/bioma) porque
-- OrquestradorValidacao infere validacao de dominio automaticamente so pelo NOME do
-- atributo (ver OrquestradorValidacao.inferirTabelaReferenciaCanonico) - e nenhuma
-- das 2 fontes tem domain 100% coberto hoje (CNFP tem categoria 'PA'/'TRQ'/'GLEBA
-- ARRECADADA' sem entrada em categoria_area_protegida; ~400 registros do CNFP tem
-- bioma composto tipo 'AMAZÔNIA/CERRADO', que nao bate em nenhum codigo). Ligar a
-- validacao agora reprovaria o lote inteiro pela regra All-or-Nothing por causa de
-- uma fracao dos registros. Os 2 payloads brutos completos continuam preservados em
-- atributos_origem/payload de qualquer forma - decidir a categorizacao fina depois
-- de ver o resultado real da ingestao.
INSERT INTO ${controleSchema}.fonte_dado (
    nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo, entidade_silver
) VALUES (
    'MMA - Unidades de Conservacao (CNUC nacional)', 'MMA', 'DOWNLOAD_URL',
    'https://dados.mma.gov.br/dataset/44b6dc8a-dc82-4a84-8d95-1b0da7c85dac/resource/20327e02-d4fe-4a1b-bd12-e381ab461d97/download/shp_cnuc_2025_03.zip',
    'SHAPEFILE', 1, 'area_protegida'
);

INSERT INTO ${controleSchema}.fonte_dado (
    nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo, entidade_silver
) VALUES (
    'SFB - Cadastro Nacional de Florestas Publicas (CNFP)', 'SFB', 'DOWNLOAD_URL',
    'https://mapas.florestal.gov.br/portal/sharing/rest/content/items/2b752dfbf9254920bf8990ce363918af/data',
    'SHAPEFILE', 1, 'area_protegida'
);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'cd_cnuc', 'codigo_origem', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'nome_uc', 'nome', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'ha_total', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'cria_ato', 'ato_legal', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'codigo', 'codigo_origem', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'nome', 'nome', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'area_ha', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'atolegal', 'ato_legal', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)';
