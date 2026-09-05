-- Zona bruta, schema-on-read (CLAUDE.md §2.2). Payload inteiro como JSON, sem
-- tipagem/interpretação — a Bronze só preserva com fidelidade e amarra a proveniência.
CREATE TABLE ${bronzeSchema}.registro_bruto (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lote_ingestao_id  NUMBER NOT NULL REFERENCES ${controleSchema}.lote_ingestao(id),
    fonte_dado_id     NUMBER NOT NULL REFERENCES ${controleSchema}.fonte_dado(id),
    numero_linha      NUMBER,        -- posição no arquivo/resposta original (rastreabilidade)
    payload           JSON NOT NULL, -- registro inteiro, chaves = nomes de colunas originais
    geometria_wkt     CLOB,          -- WKT bruto como texto, sem validar
    hash_registro     VARCHAR2(64),  -- para deduplicação
    data_ingestao     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
