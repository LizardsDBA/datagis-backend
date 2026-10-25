-- registrar_resultado ganha o parametro opcional p_valor_secundario_ha (DEFAULT
-- NULL, no fim da lista) - compativel com todas as chamadas existentes (ISAP, IAE,
-- IDESMAT, IFC, ICV) sem precisar mexer nelas; so calcular_irl/calcular_iapp
-- (V088) passam o valor de verdade. f_ultimo_valor_secundario espelha
-- f_ultimo_valor, e publicar_imovel usa ela pra preencher as 2 colunas novas da
-- V086 em imovel_publicado.
CREATE OR REPLACE EDITIONABLE PACKAGE "GOLD"."PKG_GOLD_PUBLICACAO" AS

    FUNCTION f_dissolver(p_geoms IN GOLD.t_geom_tab) RETURN MDSYS.SDO_GEOMETRY;

    FUNCTION f_ultimo_valor(p_cod_imovel VARCHAR2, p_tipo_codigo VARCHAR2) RETURN NUMBER;

    FUNCTION f_ultimo_valor_secundario(p_cod_imovel VARCHAR2, p_tipo_codigo VARCHAR2) RETURN NUMBER;

    PROCEDURE registrar_resultado(
        p_cod_imovel           IN  VARCHAR2,
        p_tipo_indicador_cod   IN  VARCHAR2,
        p_regra_calculo_id     IN  NUMBER,
        p_valor                IN  NUMBER,
        p_area_afetada_ha      IN  NUMBER,
        p_percentual           IN  NUMBER,
        p_fontes               IN  GOLD.t_fonte_lote_tab,
        p_indicador_versao_id  OUT NUMBER,
        p_valor_secundario_ha  IN  NUMBER DEFAULT NULL
    );

    PROCEDURE publicar_imovel(p_cod_imovel IN VARCHAR2);

END pkg_gold_publicacao;
/

CREATE OR REPLACE EDITIONABLE PACKAGE BODY "GOLD"."PKG_GOLD_PUBLICACAO" AS

    c_tolerancia_calculo     CONSTANT NUMBER := 0.05;  -- fuzz numerico SDO_GEOM

    FUNCTION f_dissolver(p_geoms IN GOLD.t_geom_tab) RETURN MDSYS.SDO_GEOMETRY IS
        v_resultado MDSYS.SDO_GEOMETRY;
    BEGIN
        IF p_geoms IS NULL OR p_geoms.COUNT = 0 THEN
            RETURN NULL;
        END IF;
        IF p_geoms.COUNT = 1 THEN
            RETURN p_geoms(1);
        END IF;
        SELECT SDO_AGGR_UNION(SDOAGGRTYPE(VALUE(t), c_tolerancia_calculo))
          INTO v_resultado
          FROM TABLE(p_geoms) t;
        RETURN v_resultado;
    END f_dissolver;

    FUNCTION f_ultimo_valor(p_cod_imovel VARCHAR2, p_tipo_codigo VARCHAR2) RETURN NUMBER IS
        v_valor NUMBER;
    BEGIN
        SELECT ir.valor INTO v_valor
          FROM GOLD.indicador_resultado ir
          JOIN GOLD.indicador_versao iv ON iv.id = ir.indicador_versao_id
          JOIN GOLD.tipo_indicador ti ON ti.id = ir.tipo_indicador_id
         WHERE iv.cod_imovel = p_cod_imovel AND ti.codigo = p_tipo_codigo
         ORDER BY iv.data_processamento DESC
         FETCH FIRST 1 ROW ONLY;
        RETURN v_valor;
    EXCEPTION WHEN NO_DATA_FOUND THEN
        RETURN NULL;
    END f_ultimo_valor;

    FUNCTION f_ultimo_valor_secundario(p_cod_imovel VARCHAR2, p_tipo_codigo VARCHAR2) RETURN NUMBER IS
        v_valor NUMBER;
    BEGIN
        SELECT ir.valor_secundario_ha INTO v_valor
          FROM GOLD.indicador_resultado ir
          JOIN GOLD.indicador_versao iv ON iv.id = ir.indicador_versao_id
          JOIN GOLD.tipo_indicador ti ON ti.id = ir.tipo_indicador_id
         WHERE iv.cod_imovel = p_cod_imovel AND ti.codigo = p_tipo_codigo
         ORDER BY iv.data_processamento DESC
         FETCH FIRST 1 ROW ONLY;
        RETURN v_valor;
    EXCEPTION WHEN NO_DATA_FOUND THEN
        RETURN NULL;
    END f_ultimo_valor_secundario;

    PROCEDURE registrar_resultado(
        p_cod_imovel           IN  VARCHAR2,
        p_tipo_indicador_cod   IN  VARCHAR2,
        p_regra_calculo_id     IN  NUMBER,
        p_valor                IN  NUMBER,
        p_area_afetada_ha      IN  NUMBER,
        p_percentual           IN  NUMBER,
        p_fontes               IN  GOLD.t_fonte_lote_tab,
        p_indicador_versao_id  OUT NUMBER,
        p_valor_secundario_ha  IN  NUMBER DEFAULT NULL
    ) IS
        v_regra_id  NUMBER;
        v_lotes_csv VARCHAR2(4000);
        v_base      VARCHAR2(4000);
        v_hash      VARCHAR2(64);
    BEGIN
        IF p_regra_calculo_id IS NOT NULL THEN
            v_regra_id := p_regra_calculo_id;
        ELSE
            SELECT rc.id INTO v_regra_id
              FROM GOLD.regra_calculo rc
              JOIN GOLD.tipo_indicador ti ON ti.id = rc.tipo_indicador_id
             WHERE ti.codigo = p_tipo_indicador_cod
               AND rc.vigente_de <= TRUNC(SYSDATE)
               AND (rc.vigente_ate IS NULL OR rc.vigente_ate >= TRUNC(SYSDATE))
             ORDER BY rc.versao DESC
             FETCH FIRST 1 ROW ONLY;
        END IF;

        IF p_fontes IS NOT NULL AND p_fontes.COUNT > 0 THEN
            SELECT LISTAGG(lote_ingestao_id, ',') WITHIN GROUP (ORDER BY lote_ingestao_id)
              INTO v_lotes_csv
              FROM (SELECT DISTINCT t.lote_ingestao_id FROM TABLE(p_fontes) t);
        END IF;

        -- valor_secundario entra no hash tambem - se ele mudar sozinho (ex.:
        -- ajuste futuro do percentual minimo do bioma no IRL) tem que gerar
        -- versao nova, mesmo com valor/area/percentual iguais.
        v_base := p_cod_imovel || '|' || v_regra_id || '|' || NVL(v_lotes_csv, '') || '|' ||
                  p_tipo_indicador_cod || ':' || TO_CHAR(NVL(p_valor, -1)) || ':' ||
                  TO_CHAR(NVL(p_area_afetada_ha, -1)) || ':' || TO_CHAR(NVL(p_percentual, -1)) || ':' ||
                  TO_CHAR(NVL(p_valor_secundario_ha, -1));
        v_hash := RAWTOHEX(DBMS_CRYPTO.HASH(UTL_I18N.STRING_TO_RAW(v_base, 'AL32UTF8'), DBMS_CRYPTO.HASH_SH256));

        BEGIN
            SELECT id INTO p_indicador_versao_id
              FROM GOLD.indicador_versao WHERE hash_versao = v_hash;
            RETURN;
        EXCEPTION WHEN NO_DATA_FOUND THEN
            NULL;
        END;

        INSERT INTO GOLD.indicador_versao (cod_imovel, regra_calculo_id, hash_versao)
        VALUES (p_cod_imovel, v_regra_id, v_hash)
        RETURNING id INTO p_indicador_versao_id;

        INSERT INTO GOLD.indicador_resultado
            (indicador_versao_id, tipo_indicador_id, valor, area_afetada_ha, percentual_sobreposicao, valor_secundario_ha)
        SELECT p_indicador_versao_id, ti.id, p_valor, p_area_afetada_ha, p_percentual, p_valor_secundario_ha
          FROM GOLD.tipo_indicador ti WHERE ti.codigo = p_tipo_indicador_cod;

        IF p_fontes IS NOT NULL THEN
            FOR i IN 1 .. p_fontes.COUNT LOOP
                BEGIN
                    INSERT INTO GOLD.indicador_versao_fonte
                        (indicador_versao_id, fonte_dado_id, lote_ingestao_id)
                    VALUES (p_indicador_versao_id, p_fontes(i).fonte_dado_id, p_fontes(i).lote_ingestao_id);
                EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
                    NULL;
                END;
            END LOOP;
        END IF;
    END registrar_resultado;

    PROCEDURE p_gravar_sobreposicao_area_protegida(p_imovel_publicado_id NUMBER, p_geom_imovel MDSYS.SDO_GEOMETRY, p_area_imovel_ha NUMBER) IS
        v_geoms      GOLD.t_geom_tab := GOLD.t_geom_tab();
        v_nomes      VARCHAR2(4000);
        v_orgaos     VARCHAR2(200);
        v_fonte_id   NUMBER;
        v_lote_id    NUMBER;
        v_uniao      MDSYS.SDO_GEOMETRY;
        v_intersec   MDSYS.SDO_GEOMETRY;
        v_area_ha    NUMBER;
    BEGIN
        SELECT LISTAGG(SUBSTR(ap.nome, 1, 60), '; ') WITHIN GROUP (ORDER BY ap.id),
               LISTAGG(DISTINCT o.codigo, '/') WITHIN GROUP (ORDER BY o.codigo),
               MAX(ap.fonte_dado_id), MAX(ap.lote_ingestao_id)
          INTO v_nomes, v_orgaos, v_fonte_id, v_lote_id
          FROM SILVER.area_protegida ap
          JOIN SILVER.orgao o ON o.id = ap.orgao_id
         WHERE SDO_RELATE(ap.geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
           AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(ap.geometria, c_tolerancia_calculo) = 'TRUE';

        FOR rec IN (SELECT geometria FROM SILVER.area_protegida
                     WHERE SDO_RELATE(geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
                       AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia_calculo) = 'TRUE') LOOP
            v_geoms.EXTEND;
            v_geoms(v_geoms.LAST) := rec.geometria;
        END LOOP;
        IF v_geoms.COUNT = 0 THEN
            RETURN;
        END IF;

        v_uniao := f_dissolver(v_geoms);
        v_intersec := SDO_GEOM.SDO_INTERSECTION(p_geom_imovel, v_uniao, c_tolerancia_calculo);
        IF v_intersec IS NULL THEN
            RETURN;
        END IF;
        v_area_ha := SDO_GEOM.SDO_AREA(v_intersec, c_tolerancia_calculo, 'unit=Hectare');

        INSERT INTO GOLD.sobreposicao_publicada
            (imovel_publicado_id, tipo_restricao, nome, orgao, geometria_intersecao,
             area_intersecao_ha, percentual, fonte_dado_id, lote_ingestao_id)
        VALUES (p_imovel_publicado_id, 'AREA_PROTEGIDA', v_nomes, v_orgaos, v_intersec,
                v_area_ha, CASE WHEN p_area_imovel_ha > 0 THEN v_area_ha / p_area_imovel_ha * 100 END,
                v_fonte_id, v_lote_id);
    END p_gravar_sobreposicao_area_protegida;

    PROCEDURE p_gravar_sobreposicao_embargo(p_imovel_publicado_id NUMBER, p_geom_imovel MDSYS.SDO_GEOMETRY, p_area_imovel_ha NUMBER) IS
        v_geoms    GOLD.t_geom_tab := GOLD.t_geom_tab();
        v_nomes    VARCHAR2(4000);
        v_orgaos   VARCHAR2(200);
        v_fonte_id NUMBER;
        v_lote_id  NUMBER;
        v_uniao    MDSYS.SDO_GEOMETRY;
        v_intersec MDSYS.SDO_GEOMETRY;
        v_area_ha  NUMBER;
    BEGIN
        SELECT LISTAGG(SUBSTR(e.nome_autuado, 1, 60), '; ') WITHIN GROUP (ORDER BY e.id),
               LISTAGG(DISTINCT o.codigo, '/') WITHIN GROUP (ORDER BY o.codigo),
               MAX(e.fonte_dado_id), MAX(e.lote_ingestao_id)
          INTO v_nomes, v_orgaos, v_fonte_id, v_lote_id
          FROM SILVER.embargo_ambiental e
          JOIN SILVER.orgao o ON o.id = e.orgao_id
         WHERE e.geometria IS NOT NULL AND SDO_RELATE(e.geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
           AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(e.geometria, c_tolerancia_calculo) = 'TRUE';

        FOR rec IN (SELECT geometria FROM SILVER.embargo_ambiental
                     WHERE geometria IS NOT NULL AND SDO_RELATE(geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
                       AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia_calculo) = 'TRUE') LOOP
            v_geoms.EXTEND;
            v_geoms(v_geoms.LAST) := rec.geometria;
        END LOOP;
        IF v_geoms.COUNT = 0 THEN
            RETURN;
        END IF;

        v_uniao := f_dissolver(v_geoms);
        v_intersec := SDO_GEOM.SDO_INTERSECTION(p_geom_imovel, v_uniao, c_tolerancia_calculo);
        IF v_intersec IS NULL THEN
            RETURN;
        END IF;
        v_area_ha := SDO_GEOM.SDO_AREA(v_intersec, c_tolerancia_calculo, 'unit=Hectare');

        INSERT INTO GOLD.sobreposicao_publicada
            (imovel_publicado_id, tipo_restricao, nome, orgao, geometria_intersecao,
             area_intersecao_ha, percentual, fonte_dado_id, lote_ingestao_id)
        VALUES (p_imovel_publicado_id, 'EMBARGO', v_nomes, v_orgaos, v_intersec,
                v_area_ha, CASE WHEN p_area_imovel_ha > 0 THEN v_area_ha / p_area_imovel_ha * 100 END,
                v_fonte_id, v_lote_id);
    END p_gravar_sobreposicao_embargo;

    PROCEDURE p_gravar_sobreposicao_desmatamento(p_imovel_publicado_id NUMBER, p_geom_imovel MDSYS.SDO_GEOMETRY, p_area_imovel_ha NUMBER) IS
        v_geoms    GOLD.t_geom_tab := GOLD.t_geom_tab();
        v_fonte_id NUMBER;
        v_lote_id  NUMBER;
        v_uniao    MDSYS.SDO_GEOMETRY;
        v_intersec MDSYS.SDO_GEOMETRY;
        v_area_ha  NUMBER;
    BEGIN
        SELECT MAX(fonte_dado_id), MAX(lote_ingestao_id) INTO v_fonte_id, v_lote_id
          FROM SILVER.desmatamento
         WHERE SDO_RELATE(geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
           AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia_calculo) = 'TRUE';

        FOR rec IN (SELECT geometria FROM SILVER.desmatamento
                     WHERE SDO_RELATE(geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
                       AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia_calculo) = 'TRUE') LOOP
            v_geoms.EXTEND;
            v_geoms(v_geoms.LAST) := rec.geometria;
        END LOOP;
        IF v_geoms.COUNT = 0 THEN
            RETURN;
        END IF;

        v_uniao := f_dissolver(v_geoms);
        v_intersec := SDO_GEOM.SDO_INTERSECTION(p_geom_imovel, v_uniao, c_tolerancia_calculo);
        IF v_intersec IS NULL THEN
            RETURN;
        END IF;
        v_area_ha := SDO_GEOM.SDO_AREA(v_intersec, c_tolerancia_calculo, 'unit=Hectare');

        INSERT INTO GOLD.sobreposicao_publicada
            (imovel_publicado_id, tipo_restricao, nome, orgao, geometria_intersecao,
             area_intersecao_ha, percentual, fonte_dado_id, lote_ingestao_id)
        VALUES (p_imovel_publicado_id, 'DESMATAMENTO', 'PRODES', NULL, v_intersec,
                v_area_ha, CASE WHEN p_area_imovel_ha > 0 THEN v_area_ha / p_area_imovel_ha * 100 END,
                v_fonte_id, v_lote_id);
    END p_gravar_sobreposicao_desmatamento;

    PROCEDURE publicar_imovel(p_cod_imovel IN VARCHAR2) IS
        v_geom                MDSYS.SDO_GEOMETRY;
        v_area_ha             NUMBER;
        v_municipio_ibge      NUMBER;
        v_indicador_versao_id NUMBER;
        v_imovel_publicado_id NUMBER;
        v_ja_publicado        NUMBER;
    BEGIN
        SELECT ir.geometria, ir.area_ha, m.cod_ibge
          INTO v_geom, v_area_ha, v_municipio_ibge
          FROM SILVER.imovel_rural ir
          LEFT JOIN SILVER.municipio m ON m.id = ir.municipio_id
         WHERE ir.cod_imovel = p_cod_imovel;

        SELECT MAX(id) INTO v_indicador_versao_id
          FROM GOLD.indicador_versao WHERE cod_imovel = p_cod_imovel;

        IF v_indicador_versao_id IS NULL THEN
            RETURN;
        END IF;

        SELECT COUNT(*) INTO v_ja_publicado FROM GOLD.imovel_publicado
         WHERE cod_imovel = p_cod_imovel AND indicador_versao_id = v_indicador_versao_id;
        IF v_ja_publicado > 0 THEN
            RETURN;
        END IF;

        INSERT INTO GOLD.imovel_publicado
            (cod_imovel, indicador_versao_id, municipio_cod_ibge, area_ha, geometria,
             valor_icv, valor_irl, valor_iapp, valor_isap, valor_iae, valor_idesmat, valor_ifc,
             valor_irl_deficit_ha, valor_iapp_passivo_ha)
        VALUES (p_cod_imovel, v_indicador_versao_id, v_municipio_ibge, v_area_ha, v_geom,
                f_ultimo_valor(p_cod_imovel, 'ICV'), f_ultimo_valor(p_cod_imovel, 'IRL'),
                f_ultimo_valor(p_cod_imovel, 'IAPP'), f_ultimo_valor(p_cod_imovel, 'ISAP'),
                f_ultimo_valor(p_cod_imovel, 'IAE'), f_ultimo_valor(p_cod_imovel, 'IDESMAT'),
                f_ultimo_valor(p_cod_imovel, 'IFC'),
                f_ultimo_valor_secundario(p_cod_imovel, 'IRL'), f_ultimo_valor_secundario(p_cod_imovel, 'IAPP'))
        RETURNING id INTO v_imovel_publicado_id;

        p_gravar_sobreposicao_area_protegida(v_imovel_publicado_id, v_geom, v_area_ha);
        p_gravar_sobreposicao_embargo(v_imovel_publicado_id, v_geom, v_area_ha);
        p_gravar_sobreposicao_desmatamento(v_imovel_publicado_id, v_geom, v_area_ha);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        NULL;
    END publicar_imovel;

END pkg_gold_publicacao;
/
