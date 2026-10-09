-- ============================================================================
-- V031: Atribuir entidade_silver para as fontes oficiais e semear
-- os mapeamentos de coluna padrão em controle.mapeamento_coluna.
-- Revalidado com os schemas reais das APIs (WFS/ArcGIS FeatureServer) em 2026.
-- ============================================================================

-- 1. Atualizar entidade_silver nas fontes de dados cadastradas. Por NOME (nao por
-- id) - V003 (seed consolidado do catalogo, reordenado por pedido do time) ainda
-- nao pode setar entidade_silver no INSERT porque a coluna so existe a partir
-- desta migration (ADD em V010, antes desta); usar nome tambem elimina a
-- fragilidade de hardcoded id que ja causou retrabalho aqui (a versao antiga
-- deste bloco usava "WHERE id IN (1, 12)" etc., que quebrava a cada reordenacao
-- do catalogo). Cobre as 18 fontes, nao so as 11 originais - as que vieram depois
-- (MMA/SFB, V041; APP/RL/Vegetacao Nativa do CAR, V053) setavam entidade_silver no
-- proprio INSERT (porque a coluna ja existia na epoca); agora que o insert de
-- todas moveu pro V003 (antes da coluna existir), fica tudo centralizado aqui.
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'municipio' WHERE nome = 'IBGE - Malha Municipal (PR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'bioma' WHERE nome = 'IBGE - Limites de biomas';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'imovel_rural' WHERE nome IN ('CAR - Imóveis (PR)', 'CAR - Imóveis (PR) via Shapefile (upload)');
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'app_declarada' WHERE nome = 'IAT/PR - APP Total (CAR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'reserva_legal' WHERE nome = 'IAT/PR - Reserva Legal (CAR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'vegetacao_nativa_declarada' WHERE nome = 'IAT/PR - Vegetação Nativa (CAR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'area_protegida' WHERE nome IN (
    'ICMBio - Unidades de Conservação (PR)', 'IAT/PR - Terras Indígenas (FUNAI)',
    'MMA - Unidades de Conservacao (CNUC nacional)', 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'embargo_ambiental' WHERE nome IN ('IAT/PR - Embargos IBAMA', 'IAT/PR - Embargos ICMBio');
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'auto_infracao' WHERE nome IN ('IAT/PR - Autos de Infração IBAMA', 'IAT/PR - Autos de Infração ICMBio');
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'desmatamento' WHERE nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'foco_calor' WHERE nome IN ('IAT/PR - Focos de Calor (INPE)', 'INPE (Queimadas) - Focos de calor, Brasil (mensal)');

-- 2. Semear mapeamentos para Fonte 1 (CAR GeoJSON) e Fonte 12 (CAR Shapefile) -> imovel_rural
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 18)
UNION ALL
SELECT f.id, 'status_imovel', 'situacao_imovel', 'COLUNA', 'texto', 1, NULL, 'situacao_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 18)
UNION ALL
SELECT f.id, 'condicao', 'condicao_imovel', 'COLUNA', 'texto', 0, NULL, 'condicao_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 18)
UNION ALL
SELECT f.id, 'tipo_imovel', 'tipo_imovel', 'COLUNA', 'texto', 0, NULL, 'tipo_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 18)
UNION ALL
SELECT f.id, 'area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 18)
UNION ALL
SELECT f.id, 'm_fiscal', 'modulos_fiscais', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 18)
UNION ALL
SELECT f.id, 'cod_municipio_ibge', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 18)
UNION ALL
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 18);

-- 3. Semear mapeamentos para Fonte 2 (ICMBio UCs - WFS INDE) -> area_protegida
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'cnuc', 'codigo_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'nomeuc', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'areahaalb', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'grupouc', 'categoria_area_protegida', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'criacaoato', 'ato_legal', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'biomas', 'bioma', 'COLUNA', 'texto', 0, NULL, 'bioma', NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 4. Semear mapeamentos para Fonte 3 (INPE PRODES Desmatamento) -> desmatamento
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (15, 'uuid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (15, 'year', 'ano', 'COLUNA', 'numero', 0, 1980.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (15, 'area_km', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (15, 'class_name', 'classe_prodes', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (15, 'image_date', 'data_imagem', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (15, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 5. Semear mapeamentos para Fonte 4 (IAT/PR Focos de Calor) -> foco_calor
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'datahora', 'data_hora', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'satelite', 'satelite', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'bioma', 'bioma', 'COLUNA', 'texto', 0, NULL, 'bioma', NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'riscofogo', 'risco_fogo', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'latitude', 'latitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'longitude', 'longitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (16, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 6. Semear mapeamentos para Fonte 5 (IAT/PR Embargos IBAMA) -> embargo_ambiental
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'num_tad', 'numero_termo', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'cpf_cnpj_embargado', 'cpf_cnpj', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'nome_embargado', 'nome_autuado', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'qtd_area_embargada', 'area_embargada_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'tipo_area', 'tipo_area_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'num_auto_infracao', 'num_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'num_processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'dat_embargo', 'data_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'cod_municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 7. Semear mapeamentos para Fonte 6 (INPE Queimadas CSV) -> foco_calor
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (17, 'id', 'identificador_origem', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (17, 'lat', 'latitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (17, 'lon', 'longitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (17, 'risco_fogo', 'risco_fogo', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (17, 'satelite', 'satelite', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (17, 'bioma', 'bioma', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (17, 'data_hora_gmt', 'data_hora', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (17, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 8. Semear mapeamentos para Fonte 7 (IBGE Municípios) -> municipio
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (1, 'codibge', 'cod_ibge', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (1, 'nome', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (1, 'area_km2', 'area_km2', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (1, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 9. Semear mapeamentos para Fonte 8 (IAT/PR Terras Indígenas FUNAI) -> area_protegida
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (8, 'terrai_cod', 'codigo_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (8, 'terrai_nom', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (8, 'modalidade', 'categoria_area_protegida', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (8, 'superficie', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (8, 'uf_sigla', 'uf', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (8, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 10. Semear mapeamentos para Fonte 9 (IAT/PR Embargos ICMBio) -> embargo_ambiental
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'numero_emb', 'numero_termo', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'cpf_cnpj', 'cpf_cnpj', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'autuado', 'nome_autuado', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'numero_ai', 'num_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'desc_sanc', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'tipo_infra', 'tipo_area_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'data', 'data_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (12, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 11. Semear mapeamentos para Fonte 10 (IAT/PR Autos IBAMA) -> auto_infracao
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'num_auto_infracao', 'numero_auto', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'cpf_cnpj_infrator', 'cpf_cnpj_infrator', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'nome_infrator', 'nome_infrator', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'val_auto_infracao', 'valor_multa', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'tipo_auto', 'tipo_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'des_auto_infracao', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'num_processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'dat_hora_auto_infracao', 'data_auto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'cod_municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (13, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 12. Semear mapeamentos para Fonte 11 (IAT/PR Autos ICMBio) -> auto_infracao
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'numero_ai', 'numero_auto', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'cpf_cnpj', 'cpf_cnpj_infrator', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'autuado', 'nome_infrator', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'valor_mult', 'valor_multa', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'tipo', 'tipo_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'desc_ai', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'data', 'data_auto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (14, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);
