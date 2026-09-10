-- Gold, bloco 3: detalhe congelado por imovel. E o artefato de reprodutibilidade
-- (RN016-019) - a tela de detalhe e o relatorio do auditor leem daqui. Ver
-- docs/modelagem-silver-gold-quarentena.md secao 5.3.
--
-- geometria_intersecao NAO existe na Silver: nasce no calculo do indicador (recorte
-- imovel INTERSECT restricao), entao a Gold e obrigada a guardar.

-- O imovel como publicado + seu "boletim" (os 7 valores denormalizados pra leitura
-- rapida do painel; NULL = indicador nao calculavel pra esse imovel/versao).
CREATE TABLE ${goldSchema}.imovel_publicado (
    id                   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cod_imovel           VARCHAR2(80)  NOT NULL,   -- ref natural silver.imovel_rural
    indicador_versao_id  NUMBER        NOT NULL REFERENCES ${goldSchema}.indicador_versao(id),
    municipio_cod_ibge   NUMBER(7),
    area_ha              NUMBER,
    geometria            MDSYS.SDO_GEOMETRY,       -- congelada no momento da publicacao (registrada na V020)
    valor_icv            NUMBER,
    valor_irl            NUMBER,
    valor_iapp           NUMBER,
    valor_isap           NUMBER,
    valor_iae            NUMBER,
    valor_idesmat        NUMBER,
    valor_ifc            NUMBER,
    data_publicacao      TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT uq_imovel_publicado UNIQUE (cod_imovel, indicador_versao_id)
);
CREATE INDEX ${goldSchema}.ix_imovel_pub_municipio ON ${goldSchema}.imovel_publicado (municipio_cod_ibge);

-- Cada recorte de sobreposicao que entrou no calculo de um indicador do imovel.
-- geometria_intersecao em PRECISAO TOTAL - e o que o auditor precisa poder conferir.
CREATE TABLE ${goldSchema}.sobreposicao_publicada (
    id                    NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    imovel_publicado_id   NUMBER        NOT NULL REFERENCES ${goldSchema}.imovel_publicado(id),
    tipo_restricao        VARCHAR2(30)  NOT NULL,   -- AREA_PROTEGIDA | EMBARGO | DESMATAMENTO
    nome                  VARCHAR2(300),
    orgao                 VARCHAR2(60),
    geometria_intersecao  MDSYS.SDO_GEOMETRY,       -- recorte imovel INTERSECT restricao, precisao total
    area_intersecao_ha    NUMBER,
    percentual            NUMBER,                   -- % do imovel coberto por esta restricao
    fonte_dado_id         NUMBER,
    lote_ingestao_id      NUMBER,
    data_publicacao       TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ck_sobrep_pub_tipo CHECK (tipo_restricao IN ('AREA_PROTEGIDA', 'EMBARGO', 'DESMATAMENTO'))
);
CREATE INDEX ${goldSchema}.ix_sobrep_pub_imovel ON ${goldSchema}.sobreposicao_publicada (imovel_publicado_id);
