-- Histórico de execuções de ingestão (CLAUDE.md §2.7).
CREATE TABLE ${controleSchema}.lote_ingestao (
    id                   NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fonte_dado_id        NUMBER NOT NULL REFERENCES ${controleSchema}.fonte_dado(id),
    origem_referencia    VARCHAR2(1000),        -- URL/consulta (API) ou arquivo (upload)
    hash_conteudo        VARCHAR2(64),          -- hash do corpo recebido (dedupe, RN018)
    quantidade_registros NUMBER,
    status               VARCHAR2(30) DEFAULT 'CONCLUIDO' NOT NULL,
    data_execucao        TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

-- Ver comentário equivalente em V001.
GRANT REFERENCES ON ${controleSchema}.lote_ingestao TO bronze;
