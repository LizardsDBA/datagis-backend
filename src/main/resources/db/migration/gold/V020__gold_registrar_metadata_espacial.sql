-- Metadata espacial das colunas SDO_GEOMETRY da Gold (blocos 2 e 3). Mesmo padrao da
-- V013 (Silver), bbox do Brasil, SRID 4674. Indice espacial fica pra fatia de
-- publicacao (tabela vazia agora).
--
-- [CORRIGIDO 2026-09-22, LIZARDS-3] Mesma correcao da V013: INSERT direto em
-- MDSYS.SDO_GEOM_METADATA_TABLE funciona no Oracle local (CONTROLE/ADMIN tem
-- INSERT ANY TABLE) mas quebra no Autonomous Database com ORA-41900 - essa tabela e
-- global do MDSYS, INSERT direto nunca foi o caminho suportado pela Oracle.
-- SDO_UTIL.INSERT_SDO_GEOM_METADATA exige so SELECT na tabela alvo, que CONTROLE/ADMIN
-- ja tem via SELECT ANY TABLE. Ver detalhes completos no cabecalho da V013.
--
-- ATENCAO time: mesma pegadinha de checksum da V013 - quem ja tinha o volume Oracle
-- local migrado precisa `docker compose down -v` ou `flyway repair` apos puxar esta
-- mudanca (CLAUDE.md §9.6).

BEGIN
    SDO_UTIL.INSERT_SDO_GEOM_METADATA('GOLD', 'FEATURE_PUBLICADA', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    SDO_UTIL.INSERT_SDO_GEOM_METADATA('GOLD', 'IMOVEL_PUBLICADO', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    SDO_UTIL.INSERT_SDO_GEOM_METADATA('GOLD', 'SOBREPOSICAO_PUBLICADA', 'GEOMETRIA_INTERSECAO',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    COMMIT;
END;
/
