-- Camada Gold: indicador versionado + linhagem. Ver
-- docs/modelagem-silver-gold-quarentena.md secao 5 e CLAUDE.md 2.6.
--
-- Referencia pro imovel e por codigo natural (cod_imovel), nao FK - imovel_rural
-- vive no schema SILVER (cross-schema, mesmo padrao do Bronze - CLAUDE.md 9.5).
-- FK real so intra-schema (gold -> gold).

CREATE TABLE ${goldSchema}.tipo_indicador (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(20)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    tem_fonte NUMBER(1) DEFAULT 1 NOT NULL,   -- 0 = sem fonte de dado ainda (ICV/IRL/IAPP)
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${goldSchema}.tipo_indicador (codigo, descricao, tem_fonte) VALUES ('ISAP',    'Indice de Sobreposicao com Areas Protegidas', 1);
INSERT INTO ${goldSchema}.tipo_indicador (codigo, descricao, tem_fonte) VALUES ('IAE',     'Indice de Area Embargada', 1);
INSERT INTO ${goldSchema}.tipo_indicador (codigo, descricao, tem_fonte) VALUES ('IDESMAT', 'Indice de Desmatamento', 1);
INSERT INTO ${goldSchema}.tipo_indicador (codigo, descricao, tem_fonte) VALUES ('IFC',     'Indice de Focos de Calor', 1);
INSERT INTO ${goldSchema}.tipo_indicador (codigo, descricao, tem_fonte) VALUES ('ICV',     'Indice de Cobertura Vegetal Nativa', 0);
INSERT INTO ${goldSchema}.tipo_indicador (codigo, descricao, tem_fonte) VALUES ('IRL',     'Indice de Reserva Legal', 0);
INSERT INTO ${goldSchema}.tipo_indicador (codigo, descricao, tem_fonte) VALUES ('IAPP',    'Indice de Area de Preservacao Permanente', 0);

-- Formula de calculo versionada por indicador.
CREATE TABLE ${goldSchema}.regra_calculo (
    id                 NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo_indicador_id  NUMBER        NOT NULL REFERENCES ${goldSchema}.tipo_indicador(id),
    versao             NUMBER        NOT NULL,
    expressao          CLOB          NOT NULL,   -- texto/pseudocodigo da formula (pag. 6 do desafio)
    descricao          VARCHAR2(500),
    vigente_de         DATE          NOT NULL,
    vigente_ate        DATE,
    data_add           TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT uq_regra_calculo_versao UNIQUE (tipo_indicador_id, versao)
);

-- 1 execucao de calculo para 1 imovel. hash_versao amarra dado-fonte + regra + resultado.
CREATE TABLE ${goldSchema}.indicador_versao (
    id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cod_imovel          VARCHAR2(80)  NOT NULL,   -- ref natural pra silver.imovel_rural
    regra_calculo_id    NUMBER        NOT NULL REFERENCES ${goldSchema}.regra_calculo(id),
    data_processamento  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_versao         VARCHAR2(64)  NOT NULL UNIQUE,
    publicado           NUMBER(1) DEFAULT 0 NOT NULL
);
CREATE INDEX ${goldSchema}.ix_indicador_versao_imovel ON ${goldSchema}.indicador_versao (cod_imovel);

CREATE TABLE ${goldSchema}.indicador_resultado (
    id                     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    indicador_versao_id    NUMBER        NOT NULL REFERENCES ${goldSchema}.indicador_versao(id),
    tipo_indicador_id      NUMBER        NOT NULL REFERENCES ${goldSchema}.tipo_indicador(id),
    valor                  NUMBER,
    area_afetada_ha        NUMBER,
    percentual_sobreposicao NUMBER,
    data_add               TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT uq_indicador_resultado UNIQUE (indicador_versao_id, tipo_indicador_id)
);

-- N:N - quais lotes de quais fontes entraram nesse calculo (espinha da linhagem).
CREATE TABLE ${goldSchema}.indicador_versao_fonte (
    indicador_versao_id NUMBER NOT NULL REFERENCES ${goldSchema}.indicador_versao(id),
    fonte_dado_id       NUMBER NOT NULL,   -- ref controle.fonte_dado (cross-schema, sem FK)
    lote_ingestao_id    NUMBER NOT NULL,   -- ref controle.lote_ingestao (cross-schema, sem FK)
    CONSTRAINT pk_indicador_versao_fonte PRIMARY KEY (indicador_versao_id, fonte_dado_id, lote_ingestao_id)
);
