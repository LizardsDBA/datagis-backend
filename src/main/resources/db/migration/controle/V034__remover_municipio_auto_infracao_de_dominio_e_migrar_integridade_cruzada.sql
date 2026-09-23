-- ============================================================================
-- V034: Remover municipio e auto_infracao de tabela_referencia (dominio/lookup)
-- e consolidar como referencia_entidade_silver (Integridade Cruzada).
--
-- Municipio e Auto de Infracao sao entidades canonicas da camada Silver,
-- pertencendo ao validador de Integridade Cruzada, e nao tabelas de codigo/dominio.
-- ============================================================================

-- 1. Limpar tabela_referencia onde foi configurada indevidamente com municipio ou auto_infracao
UPDATE ${controleSchema}.mapeamento_coluna
SET tabela_referencia = NULL
WHERE LOWER(tabela_referencia) IN ('municipio', 'auto_infracao');

-- 2. Garantir referencia_entidade_silver = 'municipio' em todos os mapeamentos de municipio na Silver
UPDATE ${controleSchema}.mapeamento_coluna
SET referencia_entidade_silver = 'municipio'
WHERE (LOWER(atributo_destino) IN ('municipio', 'cod_municipio_ibge', 'municipio_id')
       OR LOWER(coluna_origem) IN ('cod_municipio_ibge', 'cod_municipio', 'municipio'))
  AND (referencia_entidade_silver IS NULL OR referencia_entidade_silver = '');

-- 3. Garantir referencia_entidade_silver = 'auto_infracao' nos mapeamentos de auto de infracao
-- (como em embargo_ambiental, onde o embargo faz referencia ao auto de infracao)
UPDATE ${controleSchema}.mapeamento_coluna
SET referencia_entidade_silver = 'auto_infracao'
WHERE LOWER(atributo_destino) IN ('num_auto_infracao', 'auto_infracao', 'auto_infracao_id')
  AND (referencia_entidade_silver IS NULL OR referencia_entidade_silver = '');
