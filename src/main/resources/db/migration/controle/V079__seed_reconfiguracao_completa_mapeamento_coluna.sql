-- ============================================================================
-- V079: Reconfiguração Completa e Seed Definitiva de controle.mapeamento_coluna
--
-- Aplica o estudo consolidado (docs/estudo_mapeamento_fontes_silver.md):
-- 1. Garante a entidade_silver canônica para todas as 18 fontes cadastradas.
-- 2. Limpa todos os mapeamentos anteriores para evitar resíduos ou divergências.
-- 3. Insere todos os de-paras oficiais com tabela_destino, tipos e regras canônicas.
-- ============================================================================

-- 1. Garantir entidade_silver para todas as 18 fontes de dados cadastradas
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'municipio' WHERE nome = 'IBGE - Malha Municipal (PR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'bioma' WHERE nome = 'IBGE - Limites de biomas';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'imovel_rural' WHERE nome = 'CAR - Imóveis (PR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'imovel_rural' WHERE nome = 'CAR - Imóveis (PR) via Shapefile (upload)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'app_declarada' WHERE nome = 'IAT/PR - APP Total (CAR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'reserva_legal' WHERE nome = 'IAT/PR - Reserva Legal (CAR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'vegetacao_nativa_declarada' WHERE nome = 'IAT/PR - Vegetação Nativa (CAR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'area_protegida' WHERE nome = 'ICMBio - Unidades de Conservação (PR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'area_protegida' WHERE nome = 'IAT/PR - Terras Indígenas (FUNAI)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'area_protegida' WHERE nome = 'MMA - Unidades de Conservacao (CNUC nacional)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'area_protegida' WHERE nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'embargo_ambiental' WHERE nome = 'IAT/PR - Embargos IBAMA';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'embargo_ambiental' WHERE nome = 'IAT/PR - Embargos ICMBio';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'auto_infracao' WHERE nome = 'IAT/PR - Autos de Infração IBAMA';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'auto_infracao' WHERE nome = 'IAT/PR - Autos de Infração ICMBio';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'desmatamento' WHERE nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'foco_calor' WHERE nome = 'IAT/PR - Focos de Calor (INPE)';
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'foco_calor' WHERE nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)';

-- 2. Limpar a tabela mapeamento_coluna por completo
DELETE FROM ${controleSchema}.mapeamento_coluna;

-- 3. Inserir nova seed para todas as fontes
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, tabela_destino, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
-- 1. IBGE - Malha Municipal (PR) -> municipio
SELECT f.id, 'municipio', 'codibge', 'cod_ibge', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Malha Municipal (PR)'
UNION ALL
SELECT f.id, 'municipio', 'nome', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Malha Municipal (PR)'
UNION ALL
SELECT f.id, 'municipio', 'area_km2', 'area_km2', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Malha Municipal (PR)'
UNION ALL
SELECT f.id, 'municipio', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Malha Municipal (PR)'

-- 2. IBGE - Limites de biomas -> bioma
UNION ALL
SELECT f.id, 'bioma', 'CD_BIOMA', 'codigo_bioma', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Limites de biomas'
UNION ALL
SELECT f.id, 'bioma', 'NM_BIOMA', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Limites de biomas'
UNION ALL
SELECT f.id, 'bioma', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Limites de biomas'

-- 3. CAR - Imóveis (PR) (WFS) -> imovel_rural
UNION ALL
SELECT f.id, 'imovel_rural', 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'
UNION ALL
SELECT f.id, 'imovel_rural', 'status_imovel', 'situacao_imovel', 'COLUNA', 'texto', 1, NULL, 'situacao_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'
UNION ALL
SELECT f.id, 'imovel_rural', 'condicao', 'condicao_imovel', 'COLUNA', 'texto', 0, NULL, 'condicao_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'
UNION ALL
SELECT f.id, 'imovel_rural', 'tipo_imovel', 'tipo_imovel', 'COLUNA', 'texto', 0, NULL, 'tipo_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'
UNION ALL
SELECT f.id, 'imovel_rural', 'area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'
UNION ALL
SELECT f.id, 'imovel_rural', 'm_fiscal', 'modulos_fiscais', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'
UNION ALL
SELECT f.id, 'imovel_rural', 'cod_municipio_ibge', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'
UNION ALL
SELECT f.id, 'imovel_rural', 'dat_criacao', 'data_registro', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'
UNION ALL
SELECT f.id, 'imovel_rural', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR)'

-- 4. CAR - Imóveis (PR) via Shapefile (upload) -> imovel_rural
UNION ALL
SELECT f.id, 'imovel_rural', 'COD_IMOVEL', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'
UNION ALL
SELECT f.id, 'imovel_rural', 'STATUS_IMOVEL', 'situacao_imovel', 'COLUNA', 'texto', 1, NULL, 'situacao_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'
UNION ALL
SELECT f.id, 'imovel_rural', 'CONDICAO', 'condicao_imovel', 'COLUNA', 'texto', 0, NULL, 'condicao_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'
UNION ALL
SELECT f.id, 'imovel_rural', 'TIPO_IMOVEL', 'tipo_imovel', 'COLUNA', 'texto', 0, NULL, 'tipo_imovel', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'
UNION ALL
SELECT f.id, 'imovel_rural', 'NUM_AREA', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'
UNION ALL
SELECT f.id, 'imovel_rural', 'NUM_MODULO', 'modulos_fiscais', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'
UNION ALL
SELECT f.id, 'imovel_rural', 'COD_MUNICIPIO', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'
UNION ALL
SELECT f.id, 'imovel_rural', 'DAT_CRIACAO', 'data_registro', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'
UNION ALL
SELECT f.id, 'imovel_rural', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'CAR - Imóveis (PR) via Shapefile (upload)'

-- 5. IAT/PR - APP Total (CAR) -> app_declarada
UNION ALL
SELECT f.id, 'app_declarada', 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'app_declarada', 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'app_declarada', 'num_area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'app_declarada', 'ind_status', 'situacao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'app_declarada', 'des_condic', 'condicao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'app_declarada', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'

-- 6. IAT/PR - Reserva Legal (CAR) -> reserva_legal
UNION ALL
SELECT f.id, 'reserva_legal', 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'reserva_legal', 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'reserva_legal', 'num_area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'reserva_legal', 'ind_status', 'situacao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'reserva_legal', 'des_condic', 'condicao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'reserva_legal', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'

-- 7. IAT/PR - Vegetação Nativa (CAR) -> vegetacao_nativa_declarada
UNION ALL
SELECT f.id, 'vegetacao_nativa_declarada', 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'vegetacao_nativa_declarada', 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'vegetacao_nativa_declarada', 'num_area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'vegetacao_nativa_declarada', 'ind_status', 'situacao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'vegetacao_nativa_declarada', 'des_condic', 'condicao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'vegetacao_nativa_declarada', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'

-- 8. ICMBio - Unidades de Conservação (PR) -> area_protegida
UNION ALL
SELECT f.id, 'area_protegida', 'cnuc', 'codigo_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'ICMBio - Unidades de Conservação (PR)'
UNION ALL
SELECT f.id, 'area_protegida', 'nomeuc', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'ICMBio - Unidades de Conservação (PR)'
UNION ALL
SELECT f.id, 'area_protegida', 'areahaalb', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'ICMBio - Unidades de Conservação (PR)'
UNION ALL
SELECT f.id, 'area_protegida', 'categoria', 'categoria_area_protegida', 'COLUNA', 'texto', 0, NULL, 'categoria_area_protegida', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'ICMBio - Unidades de Conservação (PR)'
UNION ALL
SELECT f.id, 'area_protegida', 'biomas', 'bioma', 'COLUNA', 'texto', 0, NULL, 'bioma', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'ICMBio - Unidades de Conservação (PR)'
UNION ALL
SELECT f.id, 'area_protegida', 'criacaoato', 'ato_legal', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'ICMBio - Unidades de Conservação (PR)'
UNION ALL
SELECT f.id, 'area_protegida', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'ICMBio - Unidades de Conservação (PR)'

-- 9. IAT/PR - Terras Indígenas (FUNAI) -> area_protegida
UNION ALL
SELECT f.id, 'area_protegida', 'terrai_cod', 'codigo_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Terras Indígenas (FUNAI)'
UNION ALL
SELECT f.id, 'area_protegida', 'terrai_nom', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Terras Indígenas (FUNAI)'
UNION ALL
SELECT f.id, 'area_protegida', 'superficie', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Terras Indígenas (FUNAI)'
UNION ALL
SELECT f.id, 'area_protegida', 'modalidade', 'categoria_area_protegida', 'COLUNA', 'texto', 0, NULL, 'categoria_area_protegida', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Terras Indígenas (FUNAI)'
UNION ALL
SELECT f.id, 'area_protegida', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Terras Indígenas (FUNAI)'

-- 10. MMA - Unidades de Conservacao (CNUC nacional) -> area_protegida
UNION ALL
SELECT f.id, 'area_protegida', 'cd_cnuc', 'codigo_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'area_protegida', 'nome_uc', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'area_protegida', 'ha_total', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'area_protegida', 'categoria', 'categoria_area_protegida', 'COLUNA', 'texto', 0, NULL, 'categoria_area_protegida', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'area_protegida', 'cria_ato', 'ato_legal', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'area_protegida', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'

-- 11. SFB - Cadastro Nacional de Florestas Publicas (CNFP) -> area_protegida
UNION ALL
SELECT f.id, 'area_protegida', 'geocodigo', 'codigo_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'area_protegida', 'nome', 'nome', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'area_protegida', 'area_ha', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'area_protegida', 'categoria', 'categoria_area_protegida', 'COLUNA', 'texto', 0, NULL, 'categoria_area_protegida', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'area_protegida', 'bioma', 'bioma', 'COLUNA', 'texto', 0, NULL, 'bioma', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'area_protegida', 'atolegal', 'ato_legal', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'
UNION ALL
SELECT f.id, 'area_protegida', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 1, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)'

-- 12. IAT/PR - Embargos IBAMA -> embargo_ambiental
UNION ALL
SELECT f.id, 'embargo_ambiental', 'num_tad', 'numero_termo', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'cpf_cnpj_embargado', 'cpf_cnpj', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'nome_embargado', 'nome_autuado', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'qtd_area_embargada', 'area_embargada_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'tipo_area', 'tipo_area_embargo', 'COLUNA', 'texto', 0, NULL, 'tipo_area_embargo', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'num_auto_infracao', 'num_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'num_processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'dat_embargo', 'data_embargo', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'cod_municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'des_infracao', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos IBAMA'

-- 13. IAT/PR - Embargos ICMBio -> embargo_ambiental
UNION ALL
SELECT f.id, 'embargo_ambiental', 'numero_emb', 'numero_termo', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'cpf_cnpj', 'cpf_cnpj', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'autuado', 'nome_autuado', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'numero_ai', 'num_auto_infracao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'desc_sanc', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'tipo_infra', 'tipo_area_embargo', 'COLUNA', 'texto', 0, NULL, 'tipo_area_embargo', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'data', 'data_embargo', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'
UNION ALL
SELECT f.id, 'embargo_ambiental', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Embargos ICMBio'

-- 14. IAT/PR - Autos de Infração IBAMA -> auto_infracao
UNION ALL
SELECT f.id, 'auto_infracao', 'num_auto_infracao', 'numero_auto', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'cpf_cnpj_infrator', 'cpf_cnpj_infrator', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'nome_infrator', 'nome_infrator', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'val_auto_infracao', 'valor_multa', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'tipo_auto', 'tipo_auto_infracao', 'COLUNA', 'texto', 0, NULL, 'tipo_auto_infracao', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'tipo_infracao', 'tipo_infracao', 'COLUNA', 'texto', 0, NULL, 'tipo_infracao', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'tp_pessoa_infrator', 'tipo_pessoa', 'COLUNA', 'texto', 0, NULL, 'tipo_pessoa', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'des_status_formulario', 'status_auto_infracao', 'COLUNA', 'texto', 0, NULL, 'status_auto_infracao', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'des_auto_infracao', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'num_processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'dat_hora_auto_infracao', 'data_auto', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'cod_municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'cd_termos_embargos', 'houve_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'
UNION ALL
SELECT f.id, 'auto_infracao', 'cd_termos_apreensao', 'houve_apreensao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração IBAMA'

-- 15. IAT/PR - Autos de Infração ICMBio -> auto_infracao
UNION ALL
SELECT f.id, 'auto_infracao', 'numero_ai', 'numero_auto', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'cpf_cnpj', 'cpf_cnpj_infrator', 'COLUNA', 'cpf_cnpj', 0, NULL, NULL, NULL, 1, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'autuado', 'nome_infrator', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'valor_mult', 'valor_multa', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'tipo', 'tipo_auto_infracao', 'COLUNA', 'texto', 0, NULL, 'tipo_auto_infracao', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'tipo_infra', 'tipo_infracao', 'COLUNA', 'texto', 0, NULL, 'tipo_infracao', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'desc_ai', 'descricao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'processo', 'num_processo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'data', 'data_auto', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'embargo', 'houve_embargo', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'
UNION ALL
SELECT f.id, 'auto_infracao', 'apreensao', 'houve_apreensao', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Autos de Infração ICMBio'

-- 16. INPE (PRODES) - Desmatamento Mata Atlântica (PR) -> desmatamento
UNION ALL
SELECT f.id, 'desmatamento', 'uuid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)'
UNION ALL
SELECT f.id, 'desmatamento', 'year', 'ano', 'COLUNA', 'numero', 0, 1900.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)'
UNION ALL
SELECT f.id, 'desmatamento', 'area_km', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)'
UNION ALL
SELECT f.id, 'desmatamento', 'main_class', 'classe_prodes', 'COLUNA', 'texto', 0, NULL, 'classe_prodes', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)'
UNION ALL
SELECT f.id, 'desmatamento', 'satelite', 'satelite', 'COLUNA', 'texto', 0, NULL, 'satelite', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)'
UNION ALL
SELECT f.id, 'desmatamento', 'sensor', 'sensor', 'COLUNA', 'texto', 0, NULL, 'sensor', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)'
UNION ALL
SELECT f.id, 'desmatamento', 'image_date', 'data_imagem', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)'
UNION ALL
SELECT f.id, 'desmatamento', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)'

-- 17. IAT/PR - Focos de Calor (INPE) -> foco_calor
UNION ALL
SELECT f.id, 'foco_calor', 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'datahora', 'data_hora', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'satelite', 'satelite', 'COLUNA', 'texto', 0, NULL, 'satelite', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'bioma', 'bioma', 'COLUNA', 'texto', 0, NULL, 'bioma', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'riscofogo', 'risco_fogo', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'latitude', 'latitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'longitude', 'longitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'diasemchuv', 'dias_sem_chuva', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'precipitac', 'precipitacao_mm', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'frp', 'frp', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'
UNION ALL
SELECT f.id, 'foco_calor', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Focos de Calor (INPE)'

-- 18. INPE (Queimadas) - Focos de calor, Brasil (mensal) -> foco_calor
UNION ALL
SELECT f.id, 'foco_calor', 'id', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'data_hora_gmt', 'data_hora', 'COLUNA', 'data_iso', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'satelite', 'satelite', 'COLUNA', 'texto', 0, NULL, 'satelite', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'bioma', 'bioma', 'COLUNA', 'texto', 0, NULL, 'bioma', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'municipio', 'municipio', 'COLUNA', 'texto', 0, NULL, NULL, 'municipio', 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'risco_fogo', 'risco_fogo', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'numero_dias_sem_chuva', 'dias_sem_chuva', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'precipitacao', 'precipitacao_mm', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'lat', 'latitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'lon', 'longitude', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'frp', 'frp', 'COLUNA', 'numero', 0, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)'
UNION ALL
SELECT f.id, 'foco_calor', 'geometry', 'geometria', 'GEOMETRIA', 'wkt', 0, NULL, NULL, NULL, 0, 1, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)';
