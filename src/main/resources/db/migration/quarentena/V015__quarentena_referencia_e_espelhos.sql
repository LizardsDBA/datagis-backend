-- Camada de Quarentena: espelho FROUXO do Silver + bloco de rejeicao.
-- Uma tabela q_<nome> por tabela de fato do Silver. Regras (ver
-- docs/modelagem-silver-gold-quarentena.md secao 4):
--   - toda coluna de negocio e NULL-avel, SEM FK / UNIQUE / CHECK (o dado rejeitado
--     pode ser exatamente o que quebrou a regra);
--   - as colunas que no Silver sao _id FK, aqui sao texto cru (guarda o valor original);
--   - geometria como CLOB (WKT cru) - geometria invalida nao entra em coluna espacial;
--   - bloco de rejeicao igual em todas.
-- Correcao e sempre manual, pelo Operador de Dados (RN007).

-- ============================================================
-- Referencias da Quarentena
-- ============================================================
CREATE TABLE ${quarentenaSchema}.tipo_erro_quarentena (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('GEOMETRIA_NULA', 'Registro sem geometria quando ela e obrigatoria');
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('GEOMETRIA_INVALIDA', 'Geometria presente mas topologicamente invalida');
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('GEOMETRIA_MALFORMADA', 'Geometria que nao pode nem ser parseada (WKT/GeoJSON quebrado)');
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('CAMPO_OBRIGATORIO_AUSENTE', 'Atributo obrigatorio nao veio no payload');
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('TIPO_DE_DADO_INVALIDO', 'Valor nao converte pro tipo esperado (ex.: texto onde se espera numero/data)');
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('VALOR_FORA_DO_DOMINIO', 'Valor de campo enum nao existe na tabela de referencia');
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('CHAVE_NATURAL_DUPLICADA', 'Mais de um registro com a mesma chave natural no mesmo lote');
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('REFERENCIA_NAO_ENCONTRADA', 'FK esperada nao existe (ex.: cod_municipio_ibge sem municipio correspondente)');
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao) VALUES ('FORA_DO_RECORTE_TERRITORIAL', 'Registro fora do Parana / da area de interesse');

CREATE TABLE ${quarentenaSchema}.status_analise_quarentena (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${quarentenaSchema}.status_analise_quarentena (codigo, descricao) VALUES ('PENDENTE', 'Aguardando analise do operador');
INSERT INTO ${quarentenaSchema}.status_analise_quarentena (codigo, descricao) VALUES ('EM_ANALISE', 'Operador esta tratando');
INSERT INTO ${quarentenaSchema}.status_analise_quarentena (codigo, descricao) VALUES ('CORRIGIDO_REPROCESSADO', 'Corrigido manualmente e reenviado pro Silver');
INSERT INTO ${quarentenaSchema}.status_analise_quarentena (codigo, descricao) VALUES ('DESCARTADO', 'Registro invalido de forma irrecuperavel, descartado');

-- ============================================================
-- Espelhos frouxos (colunas de negocio + bloco de rejeicao)
-- ============================================================

CREATE TABLE ${quarentenaSchema}.q_municipio (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cod_ibge                VARCHAR2(20),
    nome                    VARCHAR2(200),
    uf                      VARCHAR2(40),
    bioma_predominante      VARCHAR2(60),
    area_km2                VARCHAR2(60),
    geometria_wkt           CLOB,
    -- bloco de rejeicao
    registro_bruto_id       NUMBER,
    lote_ingestao_id        NUMBER,
    fonte_dado_id           NUMBER,
    payload_original        JSON,
    tipo_erro_id            NUMBER NOT NULL REFERENCES ${quarentenaSchema}.tipo_erro_quarentena(id),
    campo_problema          VARCHAR2(100),
    valor_recebido          VARCHAR2(4000),
    motivo_rejeicao         VARCHAR2(500),
    status_analise_id       NUMBER NOT NULL REFERENCES ${quarentenaSchema}.status_analise_quarentena(id),
    analista_responsavel_id NUMBER,
    data_quarentena         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    data_resolucao          TIMESTAMP
);

CREATE TABLE ${quarentenaSchema}.q_imovel_rural (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cod_imovel              VARCHAR2(120),
    situacao_imovel         VARCHAR2(60),
    condicao_imovel         VARCHAR2(120),
    tipo_imovel             VARCHAR2(60),
    cod_municipio           VARCHAR2(20),
    data_registro           VARCHAR2(60),
    area                    VARCHAR2(60),
    modulos_fiscais         VARCHAR2(60),
    geometria_wkt           CLOB,
    registro_bruto_id       NUMBER,
    lote_ingestao_id        NUMBER,
    fonte_dado_id           NUMBER,
    payload_original        JSON,
    tipo_erro_id            NUMBER NOT NULL REFERENCES ${quarentenaSchema}.tipo_erro_quarentena(id),
    campo_problema          VARCHAR2(100),
    valor_recebido          VARCHAR2(4000),
    motivo_rejeicao         VARCHAR2(500),
    status_analise_id       NUMBER NOT NULL REFERENCES ${quarentenaSchema}.status_analise_quarentena(id),
    analista_responsavel_id NUMBER,
    data_quarentena         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    data_resolucao          TIMESTAMP
);

CREATE TABLE ${quarentenaSchema}.q_area_protegida (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo_origem           VARCHAR2(120),
    nome                    VARCHAR2(300),
    tipo_area_protegida     VARCHAR2(60),
    categoria_area_protegida VARCHAR2(80),
    orgao                   VARCHAR2(60),
    bioma                   VARCHAR2(60),
    uf                      VARCHAR2(60),
    ato_legal               VARCHAR2(600),
    area                    VARCHAR2(60),
    geometria_wkt           CLOB,
    registro_bruto_id       NUMBER,
    lote_ingestao_id        NUMBER,
    fonte_dado_id           NUMBER,
    payload_original        JSON,
    tipo_erro_id            NUMBER NOT NULL REFERENCES ${quarentenaSchema}.tipo_erro_quarentena(id),
    campo_problema          VARCHAR2(100),
    valor_recebido          VARCHAR2(4000),
    motivo_rejeicao         VARCHAR2(500),
    status_analise_id       NUMBER NOT NULL REFERENCES ${quarentenaSchema}.status_analise_quarentena(id),
    analista_responsavel_id NUMBER,
    data_quarentena         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    data_resolucao          TIMESTAMP
);

CREATE TABLE ${quarentenaSchema}.q_embargo_ambiental (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero_termo            VARCHAR2(80),
    orgao                   VARCHAR2(60),
    tipo_area_embargo       VARCHAR2(60),
    cod_municipio           VARCHAR2(20),
    cpf_cnpj                VARCHAR2(40),
    nome_autuado            VARCHAR2(300),
    data_embargo            VARCHAR2(60),
    num_processo            VARCHAR2(60),
    num_auto_infracao       VARCHAR2(60),
    area_embargada          VARCHAR2(60),
    descricao               CLOB,
    geometria_wkt           CLOB,
    registro_bruto_id       NUMBER,
    lote_ingestao_id        NUMBER,
    fonte_dado_id           NUMBER,
    payload_original        JSON,
    tipo_erro_id            NUMBER NOT NULL REFERENCES ${quarentenaSchema}.tipo_erro_quarentena(id),
    campo_problema          VARCHAR2(100),
    valor_recebido          VARCHAR2(4000),
    motivo_rejeicao         VARCHAR2(500),
    status_analise_id       NUMBER NOT NULL REFERENCES ${quarentenaSchema}.status_analise_quarentena(id),
    analista_responsavel_id NUMBER,
    data_quarentena         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    data_resolucao          TIMESTAMP
);

CREATE TABLE ${quarentenaSchema}.q_auto_infracao (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero_auto             VARCHAR2(60),
    orgao                   VARCHAR2(60),
    tipo_auto_infracao      VARCHAR2(80),
    tipo_infracao           VARCHAR2(200),
    tipo_pessoa             VARCHAR2(20),
    status_auto_infracao    VARCHAR2(60),
    cod_municipio           VARCHAR2(20),
    cpf_cnpj_infrator       VARCHAR2(40),
    nome_infrator           VARCHAR2(300),
    data_auto               VARCHAR2(60),
    num_processo            VARCHAR2(60),
    valor_multa             VARCHAR2(60),
    houve_embargo           VARCHAR2(20),
    houve_apreensao         VARCHAR2(20),
    descricao               CLOB,
    geometria_wkt           CLOB,
    registro_bruto_id       NUMBER,
    lote_ingestao_id        NUMBER,
    fonte_dado_id           NUMBER,
    payload_original        JSON,
    tipo_erro_id            NUMBER NOT NULL REFERENCES ${quarentenaSchema}.tipo_erro_quarentena(id),
    campo_problema          VARCHAR2(100),
    valor_recebido          VARCHAR2(4000),
    motivo_rejeicao         VARCHAR2(500),
    status_analise_id       NUMBER NOT NULL REFERENCES ${quarentenaSchema}.status_analise_quarentena(id),
    analista_responsavel_id NUMBER,
    data_quarentena         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    data_resolucao          TIMESTAMP
);

CREATE TABLE ${quarentenaSchema}.q_desmatamento (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem    VARCHAR2(120),
    ano                     VARCHAR2(20),
    classe_prodes           VARCHAR2(60),
    satelite                VARCHAR2(60),
    sensor                  VARCHAR2(60),
    area                    VARCHAR2(60),
    data_imagem             VARCHAR2(60),
    geometria_wkt           CLOB,
    registro_bruto_id       NUMBER,
    lote_ingestao_id        NUMBER,
    fonte_dado_id           NUMBER,
    payload_original        JSON,
    tipo_erro_id            NUMBER NOT NULL REFERENCES ${quarentenaSchema}.tipo_erro_quarentena(id),
    campo_problema          VARCHAR2(100),
    valor_recebido          VARCHAR2(4000),
    motivo_rejeicao         VARCHAR2(500),
    status_analise_id       NUMBER NOT NULL REFERENCES ${quarentenaSchema}.status_analise_quarentena(id),
    analista_responsavel_id NUMBER,
    data_quarentena         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    data_resolucao          TIMESTAMP
);

CREATE TABLE ${quarentenaSchema}.q_foco_calor (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem    VARCHAR2(120),
    data_hora               VARCHAR2(60),
    satelite                VARCHAR2(60),
    bioma                   VARCHAR2(60),
    cod_municipio           VARCHAR2(20),
    latitude                VARCHAR2(60),
    longitude               VARCHAR2(60),
    risco_fogo              VARCHAR2(60),
    dias_sem_chuva          VARCHAR2(60),
    precipitacao            VARCHAR2(60),
    frp                     VARCHAR2(60),
    geometria_wkt           CLOB,
    registro_bruto_id       NUMBER,
    lote_ingestao_id        NUMBER,
    fonte_dado_id           NUMBER,
    payload_original        JSON,
    tipo_erro_id            NUMBER NOT NULL REFERENCES ${quarentenaSchema}.tipo_erro_quarentena(id),
    campo_problema          VARCHAR2(100),
    valor_recebido          VARCHAR2(4000),
    motivo_rejeicao         VARCHAR2(500),
    status_analise_id       NUMBER NOT NULL REFERENCES ${quarentenaSchema}.status_analise_quarentena(id),
    analista_responsavel_id NUMBER,
    data_quarentena         TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    data_resolucao          TIMESTAMP
);
