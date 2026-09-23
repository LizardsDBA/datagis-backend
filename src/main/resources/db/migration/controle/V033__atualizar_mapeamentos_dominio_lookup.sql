-- ============================================================================
-- V033: Atualizar Validação de Domínio (Lookup) nos mapeamentos de colunas.
-- Garante que todas as colunas canônicas da camada Silver que referenciam
-- domínios ou hubs relacionais estejam com tabela_referencia configurada.
-- ============================================================================

-- 1. Município
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'municipio'
WHERE (atributo_destino = 'municipio' OR coluna_origem IN ('cod_municipio_ibge', 'municipio'))
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

-- 2. Imóvel Rural (CAR)
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

-- 3. Área Protegida
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

-- 4. Auto de Infração
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

-- 5. Desmatamento (PRODES)
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

-- 6. Embargo Ambiental
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'tipo_area_embargo'
WHERE atributo_destino IN ('tipo_area_embargo', 'tipo_area_embrago')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'auto_infracao'
WHERE atributo_destino IN ('auto_infracao', 'num_auto_infracao')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');

-- 7. Município (UF)
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = 'unidade_federativa'
WHERE atributo_destino IN ('unidade_federativa', 'uf')
  AND (tabela_referencia IS NULL OR tabela_referencia = '');
