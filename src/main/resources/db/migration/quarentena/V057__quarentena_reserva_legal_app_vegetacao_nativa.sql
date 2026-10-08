-- Faltou nas migrations V054/V055 (Silver + cadastro das fontes): as tabelas-espelho
-- de quarentena pras 3 entidades novas do CAR (reserva_legal, app_declarada,
-- vegetacao_nativa_declarada). Achado ao vivo tentando a primeira ingestao real: o
-- lote caiu certo em RETIDO_QUARENTENA (a validacao funcionou), mas
-- PersistidorQuarentenaService falhou com ORA-00942 tentando gravar o motivo da
-- rejeicao - sem essa tabela, o analista nunca saberia POR QUE o lote ficou retido.
-- Resiliente (nao derrubou a ingestao - so os 0 registros de quarentena ficaram sem
-- auditoria), mas precisa existir pra "resolver quarentena" ser possivel de verdade.
--
-- Mesmo padrao "espelho frouxo" das demais tabelas q_* (V015: colunas nullable,
-- atributos do dominio como texto cru) - aqui os 5 atributos de reserva_legal/
-- app_declarada/vegetacao_nativa_declarada (identificador_origem, cod_imovel,
-- area_ha, situacao_bruto, condicao_bruto) + o bloco padrao de rejeicao.
CREATE TABLE ${quarentenaSchema}.q_reserva_legal (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem    VARCHAR2(120),
    cod_imovel              VARCHAR2(60),
    area_ha                 VARCHAR2(60),
    situacao_bruto          VARCHAR2(60),
    condicao_bruto          VARCHAR2(500),
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

CREATE TABLE ${quarentenaSchema}.q_app_declarada (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem    VARCHAR2(120),
    cod_imovel              VARCHAR2(60),
    area_ha                 VARCHAR2(60),
    situacao_bruto          VARCHAR2(60),
    condicao_bruto          VARCHAR2(500),
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

CREATE TABLE ${quarentenaSchema}.q_vegetacao_nativa_declarada (
    id                      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem    VARCHAR2(120),
    cod_imovel              VARCHAR2(60),
    area_ha                 VARCHAR2(60),
    situacao_bruto          VARCHAR2(60),
    condicao_bruto          VARCHAR2(500),
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
