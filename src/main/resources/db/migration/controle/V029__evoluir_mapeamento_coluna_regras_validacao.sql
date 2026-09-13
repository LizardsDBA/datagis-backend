-- Evolucao de controle.mapeamento_coluna com regras de validacao por coluna
-- para triagem dos registros brutos (Bronze -> Silver / Quarentena).
-- Ver docs/modelagem-silver-gold-quarentena.md secao 6.

ALTER TABLE ${controleSchema}.mapeamento_coluna ADD (
    -- Limite numerico inferior (apenas minimo, sem teto maximo; ex.: area_ha > 0)
    valor_minimo               NUMBER,

    -- Tabela de referencia na Silver para validacao de dominio/lookup (ex.: situacao_imovel, orgao, bioma)
    tabela_referencia          VARCHAR2(60),

    -- Validacao de integridade cruzada contra outra entidade Silver (ex.: 'municipio' para cod_municipio_ibge)
    referencia_entidade_silver VARCHAR2(60),

    -- Flag para validacao algoritmica de CPF/CNPJ (modulo 11)
    validar_cpf_cnpj           NUMBER(1) DEFAULT 0 NOT NULL,

    -- Flag para validacao de geometria topologica (is_valid) e primitivo geometrico compativel
    validar_geometria          NUMBER(1) DEFAULT 0 NOT NULL,

    -- Flag para validacao de coordenadas/poligono dentro dos limites do Parana (SRID 4674)
    validar_recorte_pr         NUMBER(1) DEFAULT 0 NOT NULL,

    -- Flag para impedir regressao de valor (se existia preenchido no snapshot anterior e veio nulo agora)
    impedir_regressao          NUMBER(1) DEFAULT 1 NOT NULL,

    -- Campo flexivel para regras customizadas de fontes futuras que nao justifiquem novas colunas DDL
    regras_extras              JSON,

    CONSTRAINT ck_map_col_cpf_cnpj CHECK (validar_cpf_cnpj IN (0, 1)),
    CONSTRAINT ck_map_col_geometria CHECK (validar_geometria IN (0, 1)),
    CONSTRAINT ck_map_col_recorte_pr CHECK (validar_recorte_pr IN (0, 1)),
    CONSTRAINT ck_map_col_regressao CHECK (impedir_regressao IN (0, 1))
);
