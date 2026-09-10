-- Gold, bloco 2: camadas de mapa publicadas. TODO poligono/ponto que o mapa do Parana
-- desenha vive aqui, como copia publicada (nao Silver ao vivo). Ver
-- docs/modelagem-silver-gold-quarentena.md secao 5.2.
--
-- feature_publicada e generica, discriminada por mapa_camada - camada nova = novo
-- codigo em mapa_camada + linhas em feature_publicada, sem DDL (mesmo principio da
-- Silver "fonte nova sem tabela nova", um nivel acima).

-- Catalogo de camadas que o mapa oferece. O front monta o seletor de camadas daqui.
CREATE TABLE ${goldSchema}.mapa_camada (
    id             NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo         VARCHAR2(40)  NOT NULL UNIQUE,
    rotulo         VARCHAR2(120) NOT NULL,
    tipo_camada    VARCHAR2(20)  NOT NULL,   -- BASE (basemap) | TEMATICA
    geometria_tipo VARCHAR2(20)  NOT NULL,   -- POLIGONO | PONTO | LINHA
    cor_padrao     VARCHAR2(20),             -- hex, dica de estilo pro Leaflet
    ordem_z        NUMBER        DEFAULT 0 NOT NULL,
    ativo          NUMBER(1)     DEFAULT 1 NOT NULL,
    data_add       TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ck_mapa_camada_tipo CHECK (tipo_camada IN ('BASE', 'TEMATICA')),
    CONSTRAINT ck_mapa_camada_geom CHECK (geometria_tipo IN ('POLIGONO', 'PONTO', 'LINHA'))
);
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('MUNICIPIO', 'Municipios do Parana', 'BASE', 'POLIGONO', '#94a3b8', 0);
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('AREA_PROTEGIDA', 'Areas protegidas (UC / TI)', 'TEMATICA', 'POLIGONO', '#16a34a', 20);
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('DESMATAMENTO', 'Desmatamento (PRODES)', 'TEMATICA', 'POLIGONO', '#b45309', 25);
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('EMBARGO', 'Embargos ambientais', 'TEMATICA', 'POLIGONO', '#dc2626', 30);
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('IMOVEL_RURAL', 'Imoveis rurais (CAR)', 'TEMATICA', 'POLIGONO', '#2563eb', 40);
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('FOCO_CALOR', 'Focos de calor', 'TEMATICA', 'PONTO', '#ea580c', 50);

-- Toda feature que o mapa desenha. Copia GENERALIZADA da geometria (Douglas-Peucker)
-- pra renderizar rapido - a precisao total fica na Silver.
CREATE TABLE ${goldSchema}.feature_publicada (
    id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mapa_camada_id      NUMBER        NOT NULL REFERENCES ${goldSchema}.mapa_camada(id),
    cod_natural         VARCHAR2(120) NOT NULL,   -- chave natural na Silver (cod_ibge, cod_imovel, numero_termo...)
    origem_silver_id    NUMBER,                   -- id da linha na Silver (cross-schema, sem FK)
    rotulo              VARCHAR2(300),            -- texto do popup
    municipio_cod_ibge  NUMBER(7),                -- filtro rapido por cidade
    geometria           MDSYS.SDO_GEOMETRY,       -- copia generalizada pra web (registrada na V020)
    propriedades        JSON,                     -- atributos que aparecem ao clicar
    versao_publicacao   NUMBER        NOT NULL,
    data_publicacao     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    fonte_dado_id       NUMBER,
    lote_ingestao_id    NUMBER,
    CONSTRAINT uq_feature_publicada UNIQUE (mapa_camada_id, cod_natural, versao_publicacao)
);
CREATE INDEX ${goldSchema}.ix_feature_pub_camada    ON ${goldSchema}.feature_publicada (mapa_camada_id);
CREATE INDEX ${goldSchema}.ix_feature_pub_municipio ON ${goldSchema}.feature_publicada (municipio_cod_ibge);
