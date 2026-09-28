-- Cadastro das fontes de dados que o sistema conhece (CAR, IBAMA, etc).
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

-- Sem isso, criar uma FK de outro schema (ex.: bronze) pra esta tabela da
-- erro de privilegio. Quem concede e o dono da tabela, o CONTROLE.
GRANT REFERENCES ON ${controleSchema}.fonte_dado TO bronze;
