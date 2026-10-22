-- Adiciona a coluna tabela_destino na tabela mapeamento_coluna para
-- guardar a tabela Silver de destino diretamente em cada registro de de-para.
-- Acelera consultas e evita joins repetidos com fonte_dado.

ALTER TABLE ${controleSchema}.mapeamento_coluna ADD (
    tabela_destino VARCHAR2(60)
);

-- Backfill dos mapeamentos existentes a partir da entidade_silver da fonte correspondente
UPDATE ${controleSchema}.mapeamento_coluna m
SET tabela_destino = (
    SELECT f.entidade_silver
    FROM ${controleSchema}.fonte_dado f
    WHERE f.id = m.fonte_dado_id
);

CREATE INDEX ${controleSchema}.ix_mapeamento_coluna_destino
    ON ${controleSchema}.mapeamento_coluna (fonte_dado_id, tabela_destino);
