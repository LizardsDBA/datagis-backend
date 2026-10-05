-- pkg_calculo_territorial: ISAP e IAE sao reais (fonte completa hoje); IRL e IAPP
-- ficam STUB (dependem de RL/APP declaradas no CAR, bloqueadas pela API SICAR Temas
-- - ver docs/checklist-cobertura-fontes.md e docs/tutorial-acesso-fontes-pendentes.md).
-- Ver docs/gold-procedures-calculo-publicacao.md secao 2.
CREATE OR REPLACE PACKAGE ${goldSchema}.pkg_calculo_territorial AS

    -- ISAP = % de sobreposicao do imovel com silver.area_protegida (UC + TI +
    -- quilombolas + florestas publicas - ICMBio/FUNAI/MMA/SFB, cadastrados hoje).
    PROCEDURE calcular_isap(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    );

    -- IAE = % de sobreposicao do imovel com silver.embargo_ambiental federal
    -- (IBAMA/ICMBio - as unicas fontes cadastradas hoje já são federais).
    PROCEDURE calcular_iae(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    );

    -- STUB: falta a Reserva Legal declarada no CAR. O minimo do bioma (silver.bioma,
    -- via silver.municipio.bioma_predominante_id) ja e resolvivel hoje, mas IRL
    -- compara area de RL declarada x minimo do bioma - sem o primeiro insumo, nao
    -- ha o que calcular. Nao grava nada; p_indicador_versao_id fica NULL.
    PROCEDURE calcular_irl(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    );

    -- STUB: falta a APP declarada no CAR (mesma API SICAR Temas). Nao grava nada.
    PROCEDURE calcular_iapp(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    );

END pkg_calculo_territorial;
/

CREATE OR REPLACE PACKAGE BODY ${goldSchema}.pkg_calculo_territorial AS

    c_tolerancia CONSTANT NUMBER := 0.05;

    PROCEDURE calcular_isap(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
        v_geom_imovel     MDSYS.SDO_GEOMETRY;
        v_area_imovel_ha  NUMBER;
        v_geoms           ${goldSchema}.t_geom_tab := ${goldSchema}.t_geom_tab();
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
                     WHERE SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
                       AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia) = 'TRUE') LOOP
            v_geoms.EXTEND;
            v_geoms(v_geoms.LAST) := rec.geometria;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
        END LOOP;

        IF v_geoms.COUNT > 0 THEN
            v_uniao := ${goldSchema}.pkg_gold_publicacao.f_dissolver(v_geoms);
            v_intersec := SDO_GEOM.SDO_INTERSECTION(v_geom_imovel, v_uniao, c_tolerancia);
            IF v_intersec IS NOT NULL THEN
                v_area_ha := SDO_GEOM.SDO_AREA(v_intersec, c_tolerancia, 'unit=Hectare');
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
        v_geoms           ${goldSchema}.t_geom_tab := ${goldSchema}.t_geom_tab();
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
        -- SDO_ANYINTERACT, que nao aceita geometria nula.
        FOR rec IN (SELECT geometria, fonte_dado_id, lote_ingestao_id
                      FROM ${silverSchema}.embargo_ambiental
                     WHERE geometria IS NOT NULL
                       AND SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
                       AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia) = 'TRUE') LOOP
            v_geoms.EXTEND;
            v_geoms(v_geoms.LAST) := rec.geometria;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
        END LOOP;

        IF v_geoms.COUNT > 0 THEN
            v_uniao := ${goldSchema}.pkg_gold_publicacao.f_dissolver(v_geoms);
            v_intersec := SDO_GEOM.SDO_INTERSECTION(v_geom_imovel, v_uniao, c_tolerancia);
            IF v_intersec IS NOT NULL THEN
                v_area_ha := SDO_GEOM.SDO_AREA(v_intersec, c_tolerancia, 'unit=Hectare');
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
        p_indicador_versao_id := NULL; -- sem fonte (RL declarada do CAR) - stub
    END calcular_irl;

    PROCEDURE calcular_iapp(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
    BEGIN
        p_indicador_versao_id := NULL; -- sem fonte (APP declarada do CAR) - stub
    END calcular_iapp;

END pkg_calculo_territorial;
/
