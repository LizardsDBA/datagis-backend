-- ============================================================================
-- V031: Atribuir entidade_silver para as fontes oficiais e semear
-- os mapeamentos de coluna padrão em controle.mapeamento_coluna.
-- Revalidado com os schemas reais das APIs (WFS/ArcGIS FeatureServer) em 2026.
-- ============================================================================

-- 1. Atualizar entidade_silver nas fontes de dados cadastradas
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'imovel_rural' WHERE id IN (1, 12);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'area_protegida' WHERE id IN (2, 8);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'desmatamento' WHERE id = 3;
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'foco_calor' WHERE id IN (4, 6);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'embargo_ambiental' WHERE id IN (5, 9);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'municipio' WHERE id = 7;
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'auto_infracao' WHERE id IN (10, 11);

-- 2. Semear mapeamentos para Fonte 1 (CAR GeoJSON) e Fonte 12 (CAR Shapefile) -> imovel_rural
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12)
UNION ALL
SELECT f.id, 'status_imovel', 'situacao_imovel', 'COLUNA', 'texto', 1, NULL, 'situacao_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12)
UNION ALL
SELECT f.id, 'condicao', 'condicao_imovel', 'COLUNA', 'texto', 0, NULL, 'condicao_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12)
UNION ALL
SELECT f.id, 'tipo_imovel', 'tipo_imovel', 'COLUNA', 'texto', 0, NULL, 'tipo_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12)
UNION ALL
SELECT f.id, 'area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12)
UNION ALL
SELECT f.id, 'm_fiscal', 'modulos_fiscais', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12)
UNION ALL
SELECT f.id, 'cod_municipio_ibge', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12)
UNION ALL
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12);

-- 3. Semear mapeamentos para Fonte 2 (ICMBio UCs - WFS INDE) -> area_protegida
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (2, 'cnuc', 'codigo_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (2, 'nomeuc', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (2, 'areahaalb', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (2, 'grupouc', 'categoria_area_protegida', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (2, 'criacaoato', 'ato_legal', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (2, 'biomas', 'bioma', 'COLUNA', 'texto', 0, NULL, 'bioma', NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (2, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 4. Semear mapeamentos para Fonte 3 (INPE PRODES Desmatamento) -> desmatamento
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (3, 'uuid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (3, 'year', 'ano', 'COLUNA', 'numero', 0, 1980.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (3, 'area_km', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (3, 'class_name', 'classe_prodes', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (3, 'image_date', 'data_imagem', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (3, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 5. Semear mapeamentos para Fonte 4 (IAT/PR Focos de Calor) -> foco_calor
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'datahora', 'data_hora', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'satelite', 'satelite', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'bioma', 'bioma', 'COLUNA', 'texto', 0, NULL, 'bioma', NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'riscofogo', 'risco_fogo', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'latitude', 'latitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'longitude', 'longitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 6. Semear mapeamentos para Fonte 5 (IAT/PR Embargos IBAMA) -> embargo_ambiental
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'num_tad', 'numero_termo', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'cpf_cnpj_embargado', 'cpf_cnpj', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'nome_embargado', 'nome_autuado', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'qtd_area_embargada', 'area_embargada_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'tipo_area', 'tipo_area_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'num_auto_infracao', 'num_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'num_processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'dat_embargo', 'data_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'cod_municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (5, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 7. Semear mapeamentos para Fonte 6 (INPE Queimadas CSV) -> foco_calor
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (6, 'id', 'identificador_origem', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (6, 'lat', 'latitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (6, 'lon', 'longitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (6, 'risco_fogo', 'risco_fogo', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (6, 'satelite', 'satelite', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (6, 'bioma', 'bioma', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (6, 'data_hora_gmt', 'data_hora', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (6, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 8. Semear mapeamentos para Fonte 7 (IBGE Municípios) -> municipio
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'codibge', 'cod_ibge', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'nome', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'area_km2', 'area_km2', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

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
VALUES (9, 'numero_emb', 'numero_termo', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'cpf_cnpj', 'cpf_cnpj', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'autuado', 'nome_autuado', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'numero_ai', 'num_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'desc_sanc', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'tipo_infra', 'tipo_area_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'data', 'data_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (9, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 11. Semear mapeamentos para Fonte 10 (IAT/PR Autos IBAMA) -> auto_infracao
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'num_auto_infracao', 'numero_auto', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'cpf_cnpj_infrator', 'cpf_cnpj_infrator', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'nome_infrator', 'nome_infrator', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'val_auto_infracao', 'valor_multa', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'tipo_auto', 'tipo_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'des_auto_infracao', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'num_processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'dat_hora_auto_infracao', 'data_auto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'cod_municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (10, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 12. Semear mapeamentos para Fonte 11 (IAT/PR Autos ICMBio) -> auto_infracao
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'numero_ai', 'numero_auto', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'cpf_cnpj', 'cpf_cnpj_infrator', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'autuado', 'nome_infrator', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'valor_mult', 'valor_multa', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'tipo', 'tipo_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'desc_ai', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'data', 'data_auto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (11, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);
