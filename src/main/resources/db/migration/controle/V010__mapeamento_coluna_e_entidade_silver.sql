-- Prepara o cadastro de fonte pra apontar pra uma entidade canonica da Silver e
-- descrever o de-para campo a campo. Com isso, cadastrar fonte nova (mesmo tema) =
-- 1 linha em fonte_dado + N linhas em mapeamento_coluna, sem migration nova.
-- Ver docs/modelagem-silver-gold-quarentena.md secao 6.

ALTER TABLE ${controleSchema}.fonte_dado ADD (
    -- Pra qual tabela de fato da Silver os registros desta fonte vao:
    -- IMOVEL_RURAL | AREA_PROTEGIDA | EMBARGO_AMBIENTAL | AUTO_INFRACAO |
    -- DESMATAMENTO | FOCO_CALOR | MUNICIPIO. Nulo enquanto a fonte so tiver Bronze.
    entidade_silver VARCHAR2(40)
);

-- De-para de coluna do payload (Bronze) -> atributo canonico (Silver).
-- Ja estava previsto no CLAUDE.md 2.3; agora vira tabela de verdade.
CREATE TABLE ${controleSchema}.mapeamento_coluna (
    id               NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fonte_dado_id    NUMBER NOT NULL REFERENCES ${controleSchema}.fonte_dado(id),
    coluna_origem    VARCHAR2(200) NOT NULL,   -- chave no payload JSON do Bronze (ex.: 'num_tad')
    atributo_destino VARCHAR2(100) NOT NULL,   -- coluna canonica da Silver (ex.: 'numero_termo')
    destino_tipo     VARCHAR2(20)  NOT NULL,   -- COLUNA | JSON | GEOMETRIA
    tipo_dado        VARCHAR2(30),             -- texto | numero | data_iso | epoch_millis |
                                               -- cpf_cnpj | graus2_para_ha | wkt | ...
    obrigatorio      NUMBER(1) DEFAULT 0 NOT NULL,
    data_add         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT uq_mapeamento_coluna UNIQUE (fonte_dado_id, coluna_origem, atributo_destino),
    CONSTRAINT ck_mapeamento_destino_tipo CHECK (destino_tipo IN ('COLUNA', 'JSON', 'GEOMETRIA'))
);

CREATE INDEX ${controleSchema}.ix_mapeamento_coluna_fonte
    ON ${controleSchema}.mapeamento_coluna (fonte_dado_id);
