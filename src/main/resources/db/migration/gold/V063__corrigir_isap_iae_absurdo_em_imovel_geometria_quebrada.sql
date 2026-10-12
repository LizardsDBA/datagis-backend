-- Achado testando ISAP/IAE contra os 4.611 imoveis reais do circuito (apos V061):
-- alguns imoveis deram percentual absurdo (ate 12 milhoes%, area_afetada_ha de
-- ~21.299 ha pra um imovel de 0,18 ha). Nao e bug de tolerancia (testado com
-- tolerancia 1e-7 no lugar de 0.05 - resultado idêntico) - a causa raiz e mais
-- simples e mais grave: a geometria do PROPRIO imovel, quando alimentada direto no
-- SDO_GEOM.SDO_AREA, devolve um valor absurdo (12,75 BILHOES de hectares pro mesmo
-- imovel cujo silver.imovel_rural.area_ha, calculado em Java/JTS na ingestao, diz
-- corretamente 0,1772 ha) - a geometria tem vertice duplicado (ORA-13356) que a
-- torna invalida o suficiente pra SDO_GEOM devolver area/intersecao sem sentido,
-- mesmo sem lancar erro. Como silver.imovel_rural.area_ha JA e confiavel (vem de
-- JTS, nao de SDO_GEOM), a correcao e um sanity clamp bem simples e
-- matematicamente inquestionavel: a area de uma intersecao NUNCA pode exceder a
-- area do proprio imovel. Se SDO_GEOM devolver mais que isso (por geometria
-- corrompida, de qualquer natureza), trunca pra area_imovel_ha - nunca propaga um
-- percentual impossivel pra frente.
CREATE OR REPLACE PACKAGE BODY ${goldSchema}.pkg_calculo_territorial AS

    c_tolerancia CONSTANT NUMBER := 0.05;

    PROCEDURE calcular_isap(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
        v_geom_imovel     MDSYS.SDO_GEOMETRY;
        v_area_imovel_ha  NUMBER;
        v_intersecs       ${goldSchema}.t_geom_tab := ${goldSchema}.t_geom_tab();
        v_fontes          ${goldSchema}.t_fonte_lote_tab := ${goldSchema}.t_fonte_lote_tab();
        v_uniao           MDSYS.SDO_GEOMETRY;
        v_intersec        MDSYS.SDO_GEOMETRY;
        v_area_ha         NUMBER := 0;
        v_percentual      NUMBER := 0;
    BEGIN
        SELECT geometria, area_ha INTO v_geom_imovel, v_area_imovel_ha
          FROM ${silverSchema}.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT geometria, fonte_dado_id, lote_ingestao_id
                      FROM ${silverSchema}.area_protegida
                     WHERE SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE') LOOP
            BEGIN
                v_intersec := SDO_GEOM.SDO_INTERSECTION(v_geom_imovel, rec.geometria, c_tolerancia);
            EXCEPTION WHEN OTHERS THEN
                v_intersec := NULL; -- geometria individual genuinamente problematica - pula so ela
            END;
            IF v_intersec IS NOT NULL THEN
                v_intersecs.EXTEND;
                v_intersecs(v_intersecs.LAST) := v_intersec;
                v_fontes.EXTEND;
                v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
            END IF;
        END LOOP;

        IF v_intersecs.COUNT > 0 THEN
            v_uniao := ${goldSchema}.pkg_gold_publicacao.f_dissolver(v_intersecs);
            IF v_uniao IS NOT NULL THEN
                v_area_ha := SDO_GEOM.SDO_AREA(v_uniao, c_tolerancia, 'unit=Hectare');
                -- sanity clamp: intersecao nunca pode exceder a area do proprio imovel
                -- (ver cabecalho da migration - geometria corrompida pode fazer o
                -- SDO_GEOM devolver area absurda sem lancar erro nenhum)
                IF v_area_ha > v_area_imovel_ha THEN
                    v_area_ha := v_area_imovel_ha;
                END IF;
                IF v_area_imovel_ha > 0 THEN
                    v_percentual := v_area_ha / v_area_imovel_ha * 100;
                END IF;
            END IF;
        END IF;

        ${goldSchema}.pkg_gold_publicacao.registrar_resultado(
            p_cod_imovel, 'ISAP', p_regra_calculo_id,
            v_percentual, v_area_ha, v_percentual, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL;
    END calcular_isap;

    PROCEDURE calcular_iae(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
        v_geom_imovel     MDSYS.SDO_GEOMETRY;
        v_area_imovel_ha  NUMBER;
        v_intersecs       ${goldSchema}.t_geom_tab := ${goldSchema}.t_geom_tab();
        v_fontes          ${goldSchema}.t_fonte_lote_tab := ${goldSchema}.t_fonte_lote_tab();
        v_uniao           MDSYS.SDO_GEOMETRY;
        v_intersec        MDSYS.SDO_GEOMETRY;
        v_area_ha         NUMBER := 0;
        v_percentual      NUMBER := 0;
    BEGIN
        SELECT geometria, area_ha INTO v_geom_imovel, v_area_imovel_ha
          FROM ${silverSchema}.imovel_rural WHERE cod_imovel = p_cod_imovel;

        -- embargo_ambiental.geometria e NULL-avel (IBAMA quase sempre vem sem
        -- geometria, CLAUDE.md modelagem) - excluir explicitamente antes do
        -- SDO_RELATE, que nao aceita geometria nula.
        FOR rec IN (SELECT geometria, fonte_dado_id, lote_ingestao_id
                      FROM ${silverSchema}.embargo_ambiental
                     WHERE geometria IS NOT NULL
                       AND SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE') LOOP
            BEGIN
                v_intersec := SDO_GEOM.SDO_INTERSECTION(v_geom_imovel, rec.geometria, c_tolerancia);
            EXCEPTION WHEN OTHERS THEN
                v_intersec := NULL;
            END;
            IF v_intersec IS NOT NULL THEN
                v_intersecs.EXTEND;
                v_intersecs(v_intersecs.LAST) := v_intersec;
                v_fontes.EXTEND;
                v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
            END IF;
        END LOOP;

        IF v_intersecs.COUNT > 0 THEN
            v_uniao := ${goldSchema}.pkg_gold_publicacao.f_dissolver(v_intersecs);
            IF v_uniao IS NOT NULL THEN
                v_area_ha := SDO_GEOM.SDO_AREA(v_uniao, c_tolerancia, 'unit=Hectare');
                IF v_area_ha > v_area_imovel_ha THEN
                    v_area_ha := v_area_imovel_ha;
                END IF;
                IF v_area_imovel_ha > 0 THEN
                    v_percentual := v_area_ha / v_area_imovel_ha * 100;
                END IF;
            END IF;
        END IF;

        ${goldSchema}.pkg_gold_publicacao.registrar_resultado(
            p_cod_imovel, 'IAE', p_regra_calculo_id,
            v_percentual, v_area_ha, v_percentual, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL;
    END calcular_iae;

    PROCEDURE calcular_irl(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
    BEGIN
        p_indicador_versao_id := NULL; -- sem fonte modelada ainda - stub (CLAUDE.md §9.34)
    END calcular_irl;

    PROCEDURE calcular_iapp(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
    BEGIN
        p_indicador_versao_id := NULL; -- sem fonte modelada ainda - stub (CLAUDE.md §9.34)
    END calcular_iapp;

END pkg_calculo_territorial;
/
