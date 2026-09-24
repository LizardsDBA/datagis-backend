-- ============================================================================
-- V036: Atualizar Validação de Domínio (Lookup) e Integridade Cruzada
-- nos mapeamentos de colunas da camada Silver.
--
-- Unificação das configurações de tabela_referencia (domínios de lookup)
-- e referencia_entidade_silver (entidades canônicas relacionais como município e auto de infração).
-- ============================================================================

-- 1. Limpar tabela_referencia onde foi configurada indevidamente com municipio ou auto_infracao
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = NULL
WHERE LOWER(tabela_referencia) IN ('municipio', 'auto_infracao');

-- 2. Imóvel Rural (CAR) - Domínios
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'situacao_imovel'
WHERE atributo_destino IN ('situacao_imovel', 'status_imovel')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'condicao_imovel'
WHERE atributo_destino IN ('condicao_imovel', 'condicao')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'tipo_imovel'
WHERE atributo_destino = 'tipo_imovel'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

-- 3. Área Protegida - Domínios
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'tipo_area_protegida'
WHERE atributo_destino = 'tipo_area_protegida'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'categoria_area_protegida'
WHERE atributo_destino IN ('categoria_area_protegida', 'grupouc')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'orgao'
WHERE atributo_destino = 'orgao'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'bioma'
WHERE atributo_destino IN ('bioma', 'bioma_predominante', 'biomas')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

-- 4. Auto de Infração - Domínios
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'tipo_auto_infracao'
WHERE atributo_destino = 'tipo_auto_infracao'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'tipo_infracao'
WHERE atributo_destino = 'tipo_infracao'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'tipo_pessoa'
WHERE atributo_destino = 'tipo_pessoa'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'status_auto_infracao'
WHERE atributo_destino = 'status_auto_infracao'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

-- 5. Desmatamento (PRODES) - Domínios
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'classe_prodes'
WHERE atributo_destino IN ('classe_prodes', 'classes_prodes')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'satelite'
WHERE atributo_destino = 'satelite'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'sensor'
WHERE atributo_destino = 'sensor'
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

-- 6. Embargo Ambiental - Domínios
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'tipo_area_embargo'
WHERE atributo_destino IN ('tipo_area_embargo', 'tipo_area_embrago')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

-- 7. Unidade Federativa (UF) - Domínio
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'unidade_federativa'
WHERE atributo_destino IN ('unidade_federativa', 'uf')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

-- 8. Integridade Cruzada - Município
UPDATE ${controleSchema}.mapeamento_coluna
SET referencia_entidade_silver = 'municipio'
WHERE (LOWER(atributo_destino) IN ('municipio', 'cod_municipio_ibge', 'municipio_id')
       OR LOWER(coluna_origem) IN ('cod_municipio_ibge', 'cod_municipio', 'municipio'))
  AND (referencia_entidade_silver IS NULL OR referencia_entidade_silver = '');

-- 9. Integridade Cruzada - Auto de Infração
UPDATE ${controleSchema}.mapeamento_coluna
SET referencia_entidade_silver = 'auto_infracao'
WHERE LOWER(atributo_destino) IN ('num_auto_infracao', 'auto_infracao', 'auto_infracao_id')
  AND (referencia_entidade_silver IS NULL OR referencia_entidade_silver = '');
