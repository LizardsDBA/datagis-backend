-- Catálogo de fontes de dados (CLAUDE.md §2.7/§2.8).
CREATE TABLE ${controleSchema}.fonte_dado (
    id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome                VARCHAR2(200) NOT NULL,
    orgao               VARCHAR2(200),
    tipo_aquisicao      VARCHAR2(20) NOT NULL, -- UPLOAD_MANUAL | API_REST | DOWNLOAD_URL
    url_origem          VARCHAR2(1000),
    cron_expressao      VARCHAR2(100),
    formato_esperado    VARCHAR2(30),          -- CSV | GEOJSON | SHAPEFILE | JSON
    ativo               NUMBER(1) DEFAULT 1 NOT NULL,
    ultima_execucao_em  TIMESTAMP,
    proxima_execucao_em TIMESTAMP,
    data_add            TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

-- CREATE ANY TABLE nao concede REFERENCES automaticamente pra quem vai criar FK
-- pra esta tabela a partir de outro schema (ex.: bronze.registro_bruto). Como
-- CONTROLE e o dono, pode conceder aqui, logo depois de criar. Adicionar mais um
-- GRANT REFERENCES por schema conforme outras camadas passem a referenciar esta
-- tabela.
GRANT REFERENCES ON ${controleSchema}.fonte_dado TO bronze;
