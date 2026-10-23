-- Indice espacial em gold.feature_publicada (LIZARDS-35, Mapa de Cobertura).
--
-- O GeoServer traduz o INTERSECTS do WFS em SDO_RELATE(..., 'mask=anyinteract') no
-- Oracle (confirmado no spike). Sem indice, cada consulta e TABLE ACCESS FULL -
-- cruzar Ponta Grossa com os 2.654 imoveis levou 6,1 s; com indice o plano passa a
-- DOMAIN INDEX. As views vw_mapa_* (V080) leem desta tabela, entao o indice vale
-- pra todas.
--
-- PRE-REQUISITO: o schema GOLD precisa de CREATE TABLE, CREATE SEQUENCE e UNLIMITED
-- TABLESPACE concedidos DIRETO a ele (mesma pegadinha da V049 na Silver - o R-tree
-- e criado como se fosse o proprio dono da tabela; o privilegio ANY do CONTROLE nao
-- serve). Banco novo ja nasce com o grant (infra/oracle-init/01-criar-schemas.sh).
-- Banco criado antes disso precisa rodar UMA vez, como SYS:
--
--   printf 'ALTER SESSION SET CONTAINER = FREEPDB1;\nGRANT CREATE TABLE, CREATE SEQUENCE, UNLIMITED TABLESPACE TO gold;\n' \
--     | docker compose exec -T -e ORACLE_SID=ORCLCDB oracle sqlplus -s / as sysdba
--
-- e reiniciar o backend.
--
-- Por que a sonda antes do CREATE INDEX de verdade: sem o grant, o CREATE INDEX nao
-- falha limpo - o indice fica criado com DOMIDX_OPSTATUS = FAILED (testado), e
-- tabela com indice de dominio FAILED recusa INSERT, ou seja, a publicacao da Gold
-- pararia de funcionar. O CONTROLE tambem nao consegue consultar os privilegios do
-- GOLD (DBA_SYS_PRIVS nao e visivel pra ele). Entao a migration testa numa tabela
-- descartavel: se der certo, apaga a sonda e cria o indice real; se nao, apaga a
-- sonda e falha com a instrucao acima, sem deixar nada pela metade.
DECLARE
    v_dono  CONSTANT VARCHAR2(30) := UPPER('${goldSchema}');
    v_erro  VARCHAR2(4000);

    PROCEDURE apagar_sonda IS
    BEGIN
        BEGIN
            EXECUTE IMMEDIATE 'DROP TABLE ' || v_dono || '.sonda_indice_espacial PURGE';
        EXCEPTION WHEN OTHERS THEN NULL;
        END;
        DELETE FROM MDSYS.SDO_GEOM_METADATA_TABLE
         WHERE sdo_owner = v_dono AND sdo_table_name = 'SONDA_INDICE_ESPACIAL';
        COMMIT;
    END;
BEGIN
    apagar_sonda;

    EXECUTE IMMEDIATE 'CREATE TABLE ' || v_dono || '.sonda_indice_espacial (g MDSYS.SDO_GEOMETRY)';
    INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (SDO_OWNER, SDO_TABLE_NAME, SDO_COLUMN_NAME, SDO_DIMINFO, SDO_SRID)
    VALUES (v_dono, 'SONDA_INDICE_ESPACIAL', 'G',
            SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);
    COMMIT;

    BEGIN
        EXECUTE IMMEDIATE 'CREATE INDEX ' || v_dono || '.ix_sonda_indice_espacial ON '
            || v_dono || '.sonda_indice_espacial(g) INDEXTYPE IS MDSYS.SPATIAL_INDEX';
    EXCEPTION WHEN OTHERS THEN
        v_erro := SQLERRM;
    END;

    apagar_sonda;

    IF v_erro IS NOT NULL THEN
        RAISE_APPLICATION_ERROR(-20080,
            'O schema ' || v_dono || ' nao tem privilegio para criar indice espacial. '
            || 'Rode uma vez, como SYS: ALTER SESSION SET CONTAINER = FREEPDB1; '
            || 'GRANT CREATE TABLE, CREATE SEQUENCE, UNLIMITED TABLESPACE TO ' || v_dono || '; '
            || 'e reinicie o backend (detalhes no cabecalho da V081). Erro original: '
            || SUBSTR(v_erro, 1, 1500));
    END IF;

    EXECUTE IMMEDIATE 'CREATE INDEX ' || v_dono || '.idx_feature_publicada_geom ON '
        || v_dono || '.feature_publicada(geometria) INDEXTYPE IS MDSYS.SPATIAL_INDEX';
END;
/
