-- Bioma (V011) nasceu como tabela de referencia pura (codigo/descricao/ativo), sem
-- geometria - nenhuma fonte cadastrada trazia o poligono oficial dos biomas ainda.
-- Agora que a fonte "IBGE - Limites de biomas" (Shapefile, download automatico) foi
-- cadastrada, a tabela precisa aceitar geometria + o bloco de linhagem cross-schema
-- (fonte_dado_id/lote_ingestao_id/registro_bruto_id/hash_origem), igual as tabelas de
-- fato (V012/V013).
--
-- Difere das tabelas de fato num ponto: aqui NAO e NOT NULL. As 6 linhas ja existem
-- desde a V011 (semeadas sem geometria) - esta ingestao so enriquece linhas que ja
-- tem id, nunca insere uma 7a (ver PersistidorSilverService.extrairParametrosBioma).
-- NOT NULL quebraria a ALTER TABLE contra as 6 linhas que ja existem sem esses dados.
ALTER TABLE ${silverSchema}.bioma ADD (
    geometria            MDSYS.SDO_GEOMETRY,
    atributos_origem     JSON,
    fonte_dado_id        NUMBER,
    lote_ingestao_id     NUMBER,
    registro_bruto_id    NUMBER,
    data_processamento   TIMESTAMP,
    hash_origem          VARCHAR2(64)
);

-- Metadata espacial (mesmo padrao da V013: CONTROLE tem INSERT ANY TABLE, SDO_OWNER
-- explicito pq a view USER_SDO_GEOM_METADATA registraria no owner errado). Bbox do
-- Brasil inteiro (biomas sao nacionais, nao so Parana).
INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (SDO_OWNER, SDO_TABLE_NAME, SDO_COLUMN_NAME, SDO_DIMINFO, SDO_SRID)
VALUES ('SILVER', 'BIOMA', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

COMMIT;
