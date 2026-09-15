-- ============================================================================
-- V031: Atribuir entidade_silver para as fontes oficiais e semear
-- os mapeamentos de coluna padrão em controle.mapeamento_coluna.
-- ============================================================================

-- 1. Atualizar entidade_silver nas fontes de dados cadastradas
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'imovel_rural' WHERE id IN (1, 2, 12);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'area_protegida' WHERE id IN (3, 8);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'desmatamento' WHERE id = 4;
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'embargo_ambiental' WHERE id IN (5, 9);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'foco_calor' WHERE id IN (6, 32);
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'municipio' WHERE id = 7;
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'auto_infracao' WHERE id IN (10, 11);

-- 2. Semear mapeamentos para Fonte 1 (CAR GeoJSON) e Fonte 12 (CAR Shapefile)
-- Imóveis Rurais
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
SELECT f.id, 'cod_municipio_ibge', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12)
UNION ALL
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (1, 12);

-- 3. Semear mapeamentos para Fonte 7 (IBGE Municípios)
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'CD_MUN', 'cod_ibge', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'NM_MUN', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (7, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 4. Semear mapeamentos para Fonte 10 e Fonte 11 (Autos de Infração IBAMA / ICMBio)
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'numero_auto', 'numero_auto', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (10, 11)
UNION ALL
SELECT f.id, 'cpf_cnpj_infrator', 'cpf_cnpj_infrator', 'COLUNA', 'cpf_cnpj', 1, NULL, NULL, NULL, 1, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (10, 11)
UNION ALL
SELECT f.id, 'valor_multa', 'valor_multa', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (10, 11)
UNION ALL
SELECT f.id, 'cod_municipio_ibge', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (10, 11);

-- 5. Semear mapeamentos para Fonte 5 e Fonte 9 (Embargos Ambientais IBAMA / ICMBio)
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'num_tad', 'numero_termo', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (5, 9)
UNION ALL
SELECT f.id, 'cpf_cnpj', 'cpf_cnpj', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (5, 9)
UNION ALL
SELECT f.id, 'area_ha', 'area_embargada_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (5, 9)
UNION ALL
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (5, 9);

-- 6. Semear mapeamentos para Fonte 6 e Fonte 32 (Focos de Calor INPE)
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'id', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (6, 32)
UNION ALL
SELECT f.id, 'lat', 'latitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (6, 32)
UNION ALL
SELECT f.id, 'lon', 'longitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (6, 32)
UNION ALL
SELECT f.id, 'risco_fogo', 'risco_fogo', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (6, 32);

-- 7. Semear mapeamentos para Fonte 4 (PRODES Desmatamento)
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'uuid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'area_ha', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1);

INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
VALUES (4, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1);

-- 8. Semear mapeamentos para Fonte 3 e Fonte 8 (Áreas Protegidas UC / FUNAI)
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'cnuc', 'codigo_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 8)
UNION ALL
SELECT f.id, 'nome', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 8)
UNION ALL
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.id IN (3, 8);

