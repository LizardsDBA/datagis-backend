-- Tabelas de fato da Silver, uma por fenomeno (nao por fonte). Ver
-- docs/modelagem-silver-gold-quarentena.md secao 3.2.
--
-- Padrao em todas:
--   - bloco de linhagem: fonte_dado_id / lote_ingestao_id / registro_bruto_id
--     (NUMBER sem FK - referencia cross-schema, igual o Bronze faz - CLAUDE.md 9.5),
--     data_competencia, data_processamento, hash_origem;
--   - atributos_origem JSON: o que e especifico da fonte e nao virou coluna canonica;
--   - geometria MDSYS.SDO_GEOMETRY (SRID 4674 - registrado na V013);
--   - toda coluna "enum" e FK (_id) pra uma tabela de referencia da V011.
--
-- NENHUMA transformacao Bronze->Silver e feita aqui. So o schema.

-- ============================================================
-- municipio (fonte: IBGE Malha Municipal) - dimensao / hub de join
-- ============================================================
CREATE TABLE ${silverSchema}.municipio (
    id                     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cod_ibge               NUMBER(7)     NOT NULL UNIQUE,
    nome                   VARCHAR2(120) NOT NULL,
    unidade_federativa_id  NUMBER        NOT NULL REFERENCES ${silverSchema}.unidade_federativa(id),
    bioma_predominante_id  NUMBER        REFERENCES ${silverSchema}.bioma(id),
    area_km2               NUMBER,
    geometria              MDSYS.SDO_GEOMETRY,
    atributos_origem       JSON,
    fonte_dado_id          NUMBER        NOT NULL,
    lote_ingestao_id       NUMBER        NOT NULL,
    registro_bruto_id      NUMBER        NOT NULL,
    data_competencia       DATE,
    data_processamento     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem            VARCHAR2(64)
);

-- ============================================================
-- imovel_rural (fonte: CAR) - entidade central; todo indicador e "sobre um imovel"
-- ============================================================
CREATE TABLE ${silverSchema}.imovel_rural (
    id                   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cod_imovel           VARCHAR2(80)  NOT NULL UNIQUE,   -- ex.: PR-4106902-00B2EA81...
    situacao_imovel_id   NUMBER        REFERENCES ${silverSchema}.situacao_imovel(id),
    condicao_imovel_id   NUMBER        REFERENCES ${silverSchema}.condicao_imovel(id),
    tipo_imovel_id       NUMBER        REFERENCES ${silverSchema}.tipo_imovel(id),
    municipio_id         NUMBER        REFERENCES ${silverSchema}.municipio(id),
    data_registro        DATE,
    area_ha              NUMBER,
    modulos_fiscais      NUMBER,
    geometria            MDSYS.SDO_GEOMETRY,
    atributos_origem     JSON,
    fonte_dado_id        NUMBER        NOT NULL,
    lote_ingestao_id     NUMBER        NOT NULL,
    registro_bruto_id    NUMBER        NOT NULL,
    data_competencia     DATE,
    data_processamento   TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem          VARCHAR2(64)
);
CREATE INDEX ${silverSchema}.ix_imovel_rural_municipio ON ${silverSchema}.imovel_rural (municipio_id);

-- ============================================================
-- area_protegida (fontes: ICMBio UC, FUNAI TI; futuramente INCRA) -> indicador ISAP
-- ============================================================
CREATE TABLE ${silverSchema}.area_protegida (
    id                          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo_origem               VARCHAR2(80)  NOT NULL,   -- cnuc (UC) ou terrai_cod (TI)
    nome                        VARCHAR2(200) NOT NULL,
    tipo_area_protegida_id      NUMBER        NOT NULL REFERENCES ${silverSchema}.tipo_area_protegida(id),
    categoria_area_protegida_id NUMBER        REFERENCES ${silverSchema}.categoria_area_protegida(id),
    orgao_id                    NUMBER        NOT NULL REFERENCES ${silverSchema}.orgao(id),
    bioma_id                    NUMBER        REFERENCES ${silverSchema}.bioma(id),
    ato_legal                   VARCHAR2(500),
    area_ha                     NUMBER,
    geometria                   MDSYS.SDO_GEOMETRY,
    atributos_origem            JSON,
    fonte_dado_id               NUMBER        NOT NULL,
    lote_ingestao_id            NUMBER        NOT NULL,
    registro_bruto_id           NUMBER        NOT NULL,
    data_competencia            DATE,
    data_processamento          TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem                 VARCHAR2(64),
    CONSTRAINT uq_area_protegida_origem UNIQUE (codigo_origem, orgao_id)
);

-- bridge N:N - UC/TI que abrange mais de uma UF ("SP/PR", "PR,SC" nos dados reais)
CREATE TABLE ${silverSchema}.area_protegida_uf (
    area_protegida_id     NUMBER NOT NULL REFERENCES ${silverSchema}.area_protegida(id),
    unidade_federativa_id NUMBER NOT NULL REFERENCES ${silverSchema}.unidade_federativa(id),
    CONSTRAINT pk_area_protegida_uf PRIMARY KEY (area_protegida_id, unidade_federativa_id)
);

-- ============================================================
-- auto_infracao (fontes: IBAMA, ICMBio) - suplementar (contexto do IAE)
-- IBAMA quase sempre vem sem geometria -> coluna NULL-avel.
-- ============================================================
CREATE TABLE ${silverSchema}.auto_infracao (
    id                        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero_auto               VARCHAR2(40)  NOT NULL,
    orgao_id                  NUMBER        NOT NULL REFERENCES ${silverSchema}.orgao(id),
    tipo_auto_infracao_id     NUMBER        REFERENCES ${silverSchema}.tipo_auto_infracao(id),
    tipo_infracao_id          NUMBER        REFERENCES ${silverSchema}.tipo_infracao(id),
    tipo_pessoa_id            NUMBER        REFERENCES ${silverSchema}.tipo_pessoa(id),
    status_auto_infracao_id   NUMBER        REFERENCES ${silverSchema}.status_auto_infracao(id),
    municipio_id              NUMBER        REFERENCES ${silverSchema}.municipio(id),
    cpf_cnpj_infrator         VARCHAR2(20),
    nome_infrator             VARCHAR2(200),
    data_auto                 DATE,
    num_processo              VARCHAR2(40),
    valor_multa               NUMBER,
    houve_embargo             NUMBER(1),
    houve_apreensao           NUMBER(1),
    descricao                 CLOB,
    geometria                 MDSYS.SDO_GEOMETRY,
    atributos_origem          JSON,
    fonte_dado_id             NUMBER        NOT NULL,
    lote_ingestao_id          NUMBER        NOT NULL,
    registro_bruto_id         NUMBER        NOT NULL,
    data_competencia          DATE,
    data_processamento        TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem               VARCHAR2(64),
    CONSTRAINT uq_auto_infracao_origem UNIQUE (numero_auto, orgao_id)
);
CREATE INDEX ${silverSchema}.ix_auto_infracao_municipio ON ${silverSchema}.auto_infracao (municipio_id);

-- ============================================================
-- embargo_ambiental (fontes: IBAMA, ICMBio) -> indicador IAE
-- ============================================================
CREATE TABLE ${silverSchema}.embargo_ambiental (
    id                     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero_termo           VARCHAR2(60)  NOT NULL,   -- num_tad (IBAMA) ou numero_emb (ICMBio)
    orgao_id               NUMBER        NOT NULL REFERENCES ${silverSchema}.orgao(id),
    auto_infracao_id       NUMBER        REFERENCES ${silverSchema}.auto_infracao(id),  -- embargo e consequencia do auto
    tipo_area_embargo_id   NUMBER        REFERENCES ${silverSchema}.tipo_area_embargo(id),
    municipio_id           NUMBER        REFERENCES ${silverSchema}.municipio(id),
    cpf_cnpj               VARCHAR2(20),
    nome_autuado           VARCHAR2(200),
    data_embargo           DATE,
    num_processo           VARCHAR2(40),
    num_auto_infracao      VARCHAR2(40),
    area_embargada_ha      NUMBER,        -- direto do IBAMA; calculado da geometria no ICMBio
    descricao              CLOB,
    geometria              MDSYS.SDO_GEOMETRY,
    atributos_origem       JSON,
    fonte_dado_id          NUMBER        NOT NULL,
    lote_ingestao_id       NUMBER        NOT NULL,
    registro_bruto_id      NUMBER        NOT NULL,
    data_competencia       DATE,
    data_processamento     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem            VARCHAR2(64),
    CONSTRAINT uq_embargo_ambiental_origem UNIQUE (numero_termo, orgao_id)
);
CREATE INDEX ${silverSchema}.ix_embargo_municipio ON ${silverSchema}.embargo_ambiental (municipio_id);

-- ============================================================
-- desmatamento (fonte: INPE PRODES) -> indicador IDesmat
-- ============================================================
CREATE TABLE ${silverSchema}.desmatamento (
    id                    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem  VARCHAR2(80)  NOT NULL,   -- uuid / fid do PRODES
    ano                   NUMBER(4),
    classe_prodes_id      NUMBER        REFERENCES ${silverSchema}.classe_prodes(id),
    satelite_id           NUMBER        REFERENCES ${silverSchema}.satelite(id),
    sensor_id             NUMBER        REFERENCES ${silverSchema}.sensor(id),
    area_ha               NUMBER,
    data_imagem           DATE,
    geometria             MDSYS.SDO_GEOMETRY,
    atributos_origem      JSON,
    fonte_dado_id         NUMBER        NOT NULL,
    lote_ingestao_id      NUMBER        NOT NULL,
    registro_bruto_id     NUMBER        NOT NULL,
    data_competencia      DATE,
    data_processamento    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem           VARCHAR2(64),
    CONSTRAINT uq_desmatamento_origem UNIQUE (fonte_dado_id, identificador_origem)
);

-- ============================================================
-- foco_calor (fontes: IAT/PR, INPE Queimadas CSV) -> indicador IFC. A maior (~154k).
-- ============================================================
CREATE TABLE ${silverSchema}.foco_calor (
    id                    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem  VARCHAR2(80)  NOT NULL,   -- id (uuid, fonte 7) ou objectid (fonte 5)
    data_hora             TIMESTAMP,
    satelite_id           NUMBER        REFERENCES ${silverSchema}.satelite(id),
    bioma_id              NUMBER        REFERENCES ${silverSchema}.bioma(id),
    municipio_id          NUMBER        REFERENCES ${silverSchema}.municipio(id),  -- so fonte 7 traz cod IBGE
    latitude              NUMBER,
    longitude             NUMBER,
    risco_fogo            NUMBER,
    dias_sem_chuva        NUMBER,
    precipitacao_mm       NUMBER,
    frp                   NUMBER,
    geometria             MDSYS.SDO_GEOMETRY,
    atributos_origem      JSON,
    fonte_dado_id         NUMBER        NOT NULL,
    lote_ingestao_id      NUMBER        NOT NULL,
    registro_bruto_id     NUMBER        NOT NULL,
    data_competencia      DATE,
    data_processamento    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem           VARCHAR2(64),
    CONSTRAINT uq_foco_calor_origem UNIQUE (fonte_dado_id, identificador_origem)
);
CREATE INDEX ${silverSchema}.ix_foco_calor_municipio ON ${silverSchema}.foco_calor (municipio_id);
