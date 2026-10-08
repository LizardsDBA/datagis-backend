-- 3 tabelas de fato novas pros temas do CAR achados em 2026-10-07 (geoportal IAT/PR,
-- docs/tutorial-acesso-fontes-pendentes.md §0-bis) que resolvem IRL, IAPP e ICV.
--
-- Mesmo padrao das tabelas de fato existentes (V012: desmatamento/foco_calor) - NAO
-- tem FK pra imovel_rural de proposito. Confirmado ao vivo contra o servico real:
-- um imovel pode ter VARIOS poligonos de Reserva Legal nao-contiguos (fragmentos
-- separados) - cod_imovel repete entre linhas, entao NAO pode ser chave unica nem
-- FK 1:1. A chave de deduplicacao/unicidade de cada registro e identificador_origem
-- (do campo 'objectid' do ArcGIS, unico por poligono), igual desmatamento/foco_calor
-- ja fazem - nao cod_imovel (que e so um atributo, repetido quando ha mais de um
-- fragmento).
--
-- O vinculo com o imovel fica resolvido por cod_imovel como atributo de texto
-- simples (sem integridade cruzada ativada ainda - ver V053: a Silver so tem os
-- ~4.608 imoveis do circuito de homologacao, nao os ~350-390 mil que estas 3
-- camadas cobrem; ativar FK/validacao agora quarentenaria quase tudo). O calculo
-- dos indicadores (calcular_irl/iapp/icv, ainda nao implementado) vai juntar por
-- cod_imovel em texto (SUM de area_ha agrupado) ou, no caso do IAPP, interseccionar
-- app_declarada x vegetacao_nativa_declarada via SDO_RELATE.
CREATE TABLE ${silverSchema}.reserva_legal (
    id                    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem  VARCHAR2(80)  NOT NULL,   -- objectid do ArcGIS IAT/PR
    cod_imovel            VARCHAR2(60),              -- atributo, NAO FK (1 imovel pode ter N fragmentos)
    area_ha               NUMBER,
    situacao_bruto        VARCHAR2(60),              -- ind_status cru (AT/PE/SU) - sem dominio ativado
    condicao_bruto        VARCHAR2(500),             -- des_condic cru (mesmo texto SICAR do ValidadorDominio)
    geometria             MDSYS.SDO_GEOMETRY,
    atributos_origem      JSON,
    fonte_dado_id         NUMBER        NOT NULL,
    lote_ingestao_id      NUMBER        NOT NULL,
    registro_bruto_id     NUMBER        NOT NULL,
    data_processamento    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem           VARCHAR2(64),
    CONSTRAINT uq_reserva_legal_origem UNIQUE (fonte_dado_id, identificador_origem)
);

CREATE TABLE ${silverSchema}.app_declarada (
    id                    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem  VARCHAR2(80)  NOT NULL,
    cod_imovel            VARCHAR2(60),
    area_ha               NUMBER,
    situacao_bruto        VARCHAR2(60),
    condicao_bruto        VARCHAR2(500),
    geometria             MDSYS.SDO_GEOMETRY,
    atributos_origem      JSON,
    fonte_dado_id         NUMBER        NOT NULL,
    lote_ingestao_id      NUMBER        NOT NULL,
    registro_bruto_id     NUMBER        NOT NULL,
    data_processamento    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem           VARCHAR2(64),
    CONSTRAINT uq_app_declarada_origem UNIQUE (fonte_dado_id, identificador_origem)
);

CREATE TABLE ${silverSchema}.vegetacao_nativa_declarada (
    id                    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identificador_origem  VARCHAR2(80)  NOT NULL,
    cod_imovel            VARCHAR2(60),
    area_ha               NUMBER,
    situacao_bruto        VARCHAR2(60),
    condicao_bruto        VARCHAR2(500),
    geometria             MDSYS.SDO_GEOMETRY,
    atributos_origem      JSON,
    fonte_dado_id         NUMBER        NOT NULL,
    lote_ingestao_id      NUMBER        NOT NULL,
    registro_bruto_id     NUMBER        NOT NULL,
    data_processamento    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    hash_origem           VARCHAR2(64),
    CONSTRAINT uq_vegetacao_nativa_origem UNIQUE (fonte_dado_id, identificador_origem)
);

-- Indice nao-espacial em cod_imovel: e o join usado pelo calculo dos indicadores
-- (SUM de area_ha por cod_imovel), muito mais barato que interseccao espacial pra
-- IRL/ICV (a area ja vem pre-calculada pelo SICAR por poligono).
CREATE INDEX ${silverSchema}.idx_reserva_legal_cod_imovel ON ${silverSchema}.reserva_legal(cod_imovel);
CREATE INDEX ${silverSchema}.idx_app_declarada_cod_imovel ON ${silverSchema}.app_declarada(cod_imovel);
CREATE INDEX ${silverSchema}.idx_vegetacao_nativa_cod_imovel ON ${silverSchema}.vegetacao_nativa_declarada(cod_imovel);

-- Metadata espacial (mesmo padrao da V013, bbox Brasil inteiro, SRID 4674,
-- tolerancia 0.05 - necessaria antes de qualquer indice espacial ou SDO_GEOM.*).
-- Indice espacial de verdade (necessario pro calculo do IAPP, que interseccciona
-- app_declarada x vegetacao_nativa_declarada via SDO_RELATE) fica pra quando
-- calcular_iapp for implementado de verdade - mesmo principio da V011/V049
-- ("indices espaciais ficam pra fatia de calculo").
INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (SDO_OWNER, SDO_TABLE_NAME, SDO_COLUMN_NAME, SDO_DIMINFO, SDO_SRID)
VALUES ('SILVER', 'RESERVA_LEGAL', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (SDO_OWNER, SDO_TABLE_NAME, SDO_COLUMN_NAME, SDO_DIMINFO, SDO_SRID)
VALUES ('SILVER', 'APP_DECLARADA', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (SDO_OWNER, SDO_TABLE_NAME, SDO_COLUMN_NAME, SDO_DIMINFO, SDO_SRID)
VALUES ('SILVER', 'VEGETACAO_NATIVA_DECLARADA', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

COMMIT;
