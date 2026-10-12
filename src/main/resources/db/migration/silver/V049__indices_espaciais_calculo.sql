-- Indices espaciais nas 4 tabelas de restricao escaneadas pelos calculadores Gold
-- (pkg_calculo_territorial/pkg_calculo_ambiental, V045/V046) via SDO_RELATE(...,
-- 'mask=ANYINTERACT'). Ja tinham metadata registrada desde a V013 - faltava so o
-- indice (CLAUDE.md 9.11: "indices espaciais ficam pra fatia de calculo, tabelas
-- vazias" - chegamos nessa fatia agora).
--
-- Achado real testando contra dado de produção (16 mil area_protegida, 2,5 mil
-- embargo_ambiental): sem indice, 200 imoveis levaram ~42 minutos pra calcular
-- ISAP/IAE. Com indice + SDO_RELATE (em vez de SDO_ANYINTERACT, que nunca aciona
-- indice - ver comentario na V044), a mesma consulta usa DOMAIN INDEX access.
--
-- CREATE INDEX ... INDEXTYPE IS MDSYS.SPATIAL_INDEX passou por 3 pegadinhas de
-- privilegio reais, achadas nesta sessao:
-- 1. CONTROLE precisa de CREATE ANY INDEX (ja tinha) mas o R-TREE interno (ODCI)
--    roda como se fosse o PROPRIO schema dono (SILVER) fazendo CREATE TABLE/
--    CREATE SEQUENCE - sem esses privilegios DIRETOS (nao "ANY") concedidos a
--    SILVER, a criacao falha com ORA-29855/ORA-01031 mesmo com CONTROLE tendo
--    privilegio de sobra. Precisou GRANT CREATE TABLE, CREATE SEQUENCE,
--    UNLIMITED TABLESPACE TO silver (init script).
-- 2. DROP de um indice de dominio (spatial) tambem nao funciona com "DROP ANY
--    INDEX" concedido a CONTROLE - de novo, as rotinas ODCI internas exigem o
--    dono literal. Resolvido conectando como o proprio schema SILVER pra
--    criar/dropar esses indices especificos (precisa de CREATE SESSION
--    temporario pra SILVER, que normalmente nao faz login).
-- 3. area_protegida (16 mil poligonos, alguns nacionais complexos do MMA/SFB)
--    estourou PGA_AGGREGATE_LIMIT (2G default) construindo o R-tree - precisou
--    subir pra 8G (CDB root + PDB) pra completar. Reflita isso no
--    docker-compose/init se for rodar num ambiente com pouca RAM disponivel.
--
-- Rodado manualmente conectado como SILVER (nao CONTROLE) - ver nota 2 acima. Em
-- caso de reset do ambiente, reaplicar os GRANTs do item 1 (ja no init script) e
-- os PARAMETERS da nota 3 antes, e lembrar que o Flyway (conectado como CONTROLE)
-- sozinho nao consegue criar isto - precisa do passo manual documentado em
-- docs/gold-procedures-calculo-publicacao.md.
CREATE INDEX ${silverSchema}.idx_area_protegida_geom
    ON ${silverSchema}.area_protegida(geometria) INDEXTYPE IS MDSYS.SPATIAL_INDEX;

CREATE INDEX ${silverSchema}.idx_embargo_ambiental_geom
    ON ${silverSchema}.embargo_ambiental(geometria) INDEXTYPE IS MDSYS.SPATIAL_INDEX;

CREATE INDEX ${silverSchema}.idx_desmatamento_geom
    ON ${silverSchema}.desmatamento(geometria) INDEXTYPE IS MDSYS.SPATIAL_INDEX;

CREATE INDEX ${silverSchema}.idx_foco_calor_geom
    ON ${silverSchema}.foco_calor(geometria) INDEXTYPE IS MDSYS.SPATIAL_INDEX;
