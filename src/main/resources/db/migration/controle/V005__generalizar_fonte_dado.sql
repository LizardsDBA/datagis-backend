-- Generaliza fonte_dado pra ingestao configuravel por DADO, nao por codigo:
-- url_origem passa a ser a URL final, ja com todo filtro que a fonte precisar
-- (CQL_FILTER, outSR, bbox etc.) resolvido — expandir o circuito de municipios
-- de uma fonte, por exemplo, vira "editar a URL na tela", nao mexer em codigo.
ALTER TABLE ${controleSchema}.fonte_dado MODIFY (url_origem VARCHAR2(2000));

-- Colunas que informam qual campo do CSV/planilha e latitude/longitude, quando a
-- fonte for tabular e tiver coordenada (ex.: focos de calor). Nulas = fonte sem
-- geometria (so tabela) ou fonte cujo formato ja embute geometria (GeoJSON).
ALTER TABLE ${controleSchema}.fonte_dado ADD (
    campo_geometria_lat VARCHAR2(100),
    campo_geometria_lon VARCHAR2(100)
);
