-- pkg_calculo_ambiental: IDesmat e IFC - os 2 indicadores da task "Ambiental", ambos
-- com fonte completa hoje (PRODES, focos de calor). Ver
-- docs/gold-procedures-calculo-publicacao.md secao 2.
CREATE OR REPLACE PACKAGE ${goldSchema}.pkg_calculo_ambiental AS

    -- IDesmat = area (ha) e % de sobreposicao do imovel com silver.desmatamento
    -- (total agregado nesta v1 - quebra pelos 3 marcos temporais do desafio fica
    -- pra v2 da regra_calculo). p_regra_calculo_id NULL = usa a regra vigente.
    PROCEDURE calcular_idesmat(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    );

    -- IFC = focos de calor dentro do imovel (silver.foco_calor) / area do imovel em
    -- ha, x 1000. Densidade de pontos, nao sobreposicao de area - area_afetada_ha e
    -- percentual_sobreposicao ficam NULL no resultado (nao se aplicam aqui).
    PROCEDURE calcular_ifc(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    );

END pkg_calculo_ambiental;
/

CREATE OR REPLACE PACKAGE BODY ${goldSchema}.pkg_calculo_ambiental AS

    c_tolerancia CONSTANT NUMBER := 0.05; -- mesma tolerancia numerica do resto do projeto

    PROCEDURE calcular_idesmat(
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
                      FROM ${silverSchema}.desmatamento
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
            p_cod_imovel, 'IDESMAT', p_regra_calculo_id,
            v_percentual, v_area_ha, v_percentual, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL; -- imovel nao encontrado na silver.imovel_rural
    END calcular_idesmat;

    PROCEDURE calcular_ifc(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
        v_geom_imovel    MDSYS.SDO_GEOMETRY;
        v_area_imovel_ha NUMBER;
        v_contagem       NUMBER := 0;
        v_densidade      NUMBER := 0;
        v_fontes         ${goldSchema}.t_fonte_lote_tab := ${goldSchema}.t_fonte_lote_tab();
    BEGIN
        SELECT geometria, area_ha INTO v_geom_imovel, v_area_imovel_ha
          FROM ${silverSchema}.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT fonte_dado_id, lote_ingestao_id FROM ${silverSchema}.foco_calor
                     WHERE SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE') LOOP
            v_contagem := v_contagem + 1;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
        END LOOP;

        IF v_area_imovel_ha > 0 THEN
            v_densidade := v_contagem / v_area_imovel_ha * 1000;
        END IF;

        ${goldSchema}.pkg_gold_publicacao.registrar_resultado(
            p_cod_imovel, 'IFC', p_regra_calculo_id,
            v_densidade, NULL, NULL, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL;
    END calcular_ifc;

END pkg_calculo_ambiental;
/
