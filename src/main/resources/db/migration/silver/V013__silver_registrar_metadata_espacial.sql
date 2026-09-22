-- Registro da metadata espacial das colunas SDO_GEOMETRY da Silver.
-- Necessario pra criar indice espacial (V014) e pra usar as funcoes SDO_GEOM de
-- validacao/area. Bounding box do Brasil inteiro (a fonte INPE Queimadas e nacional),
-- SRID 4674 (SIRGAS 2000), tolerancia 0,05 m.
--
-- PRIMEIRO USO DE ORACLE SPATIAL NO PROJETO (item 2 do spike, CLAUDE.md 5.5).
--
-- [CORRIGIDO 2026-09-22, LIZARDS-3] Versao original inseria direto em
-- MDSYS.SDO_GEOM_METADATA_TABLE (com SDO_OWNER='SILVER' na mao, ja que o Flyway conecta
-- como CONTROLE/ADMIN, nao como SILVER). Funcionava no Oracle local (CONTROLE tem
-- INSERT ANY TABLE), mas quebra no Autonomous Database com ORA-41900: essa tabela e
-- global do MDSYS e a propria Oracle documenta que usuario nunca deveria fazer INSERT
-- direto nela, independente do privilegio ANY que a sessao tenha - o caminho suportado
-- e a procedure abaixo, que so exige SELECT na tabela alvo (que CONTROLE/ADMIN ja tem
-- via SELECT ANY TABLE) em vez de INSERT no objeto interno do MDSYS. Testado contra o
-- Autonomous AI Database Always Free "datagis" (docs/analise-hospedagem-autonomous-
-- database-2026-09-22.md §3.3/§7) e contra o Oracle local - funciona nos dois.
--
-- ATENCAO time: esta migration ja estava aplicada antes desta correcao. Editar o texto
-- muda o checksum que o Flyway guarda - quem ja tinha o volume Oracle local migrado
-- precisa rodar `docker compose down -v` (reseta o banco de dev) ou `flyway repair`
-- depois de puxar esta mudanca, senao a subida falha com FlywayValidateException
-- (mesma pegadinha ja documentada no CLAUDE.md §9.6).

BEGIN
    SDO_UTIL.INSERT_SDO_GEOM_METADATA('SILVER', 'MUNICIPIO', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    SDO_UTIL.INSERT_SDO_GEOM_METADATA('SILVER', 'IMOVEL_RURAL', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    SDO_UTIL.INSERT_SDO_GEOM_METADATA('SILVER', 'AREA_PROTEGIDA', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    SDO_UTIL.INSERT_SDO_GEOM_METADATA('SILVER', 'AUTO_INFRACAO', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    SDO_UTIL.INSERT_SDO_GEOM_METADATA('SILVER', 'EMBARGO_AMBIENTAL', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    SDO_UTIL.INSERT_SDO_GEOM_METADATA('SILVER', 'DESMATAMENTO', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    SDO_UTIL.INSERT_SDO_GEOM_METADATA('SILVER', 'FOCO_CALOR', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);

    COMMIT;
END;
/
