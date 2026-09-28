-- Guarda o dado exatamente como veio da fonte, sem tratar nada.
CREATE TABLE ${bronzeSchema}.registro_bruto (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lote_ingestao_id  NUMBER NOT NULL REFERENCES ${controleSchema}.lote_ingestao(id),
    fonte_dado_id     NUMBER NOT NULL REFERENCES ${controleSchema}.fonte_dado(id),
    numero_linha      NUMBER,        -- posicao no arquivo/resposta original
    payload           JSON NOT NULL, -- o registro inteiro, com os nomes de coluna originais
    geometria_wkt     CLOB,          -- geometria em WKT, sem validar
    hash_registro     VARCHAR2(64),  -- pra ajudar a achar duplicata depois
    data_ingestao     TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
