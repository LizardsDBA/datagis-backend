-- Registra cada execucao de ingestao (um "lote" por chamada).
CREATE TABLE ${controleSchema}.lote_ingestao (
    id                   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fonte_dado_id        NUMBER NOT NULL REFERENCES ${controleSchema}.fonte_dado(id),
    origem_referencia    VARCHAR2(1000),        -- URL da API, ou nome do arquivo no upload
    hash_conteudo        VARCHAR2(64),          -- hash do conteudo recebido, pra saber se mudou
    quantidade_registros NUMBER,
    status               VARCHAR2(30) DEFAULT 'CONCLUIDO' NOT NULL,
    data_execucao        TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

-- Mesmo motivo do GRANT em V001.
GRANT REFERENCES ON ${controleSchema}.lote_ingestao TO bronze;
