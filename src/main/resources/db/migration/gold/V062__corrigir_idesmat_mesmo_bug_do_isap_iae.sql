-- [MESMO BUG DA V059/V060, outro package] Testado ao vivo apos ingerir o PRODES
-- filtrado pelo circuito (V061): IDESMAT saiu 0 pra TODOS os 4.611 imoveis, apesar de
-- silver.desmatamento ter 1.428 poligonos reais e 2.993 imoveis terem intersecao
-- espacial bruta de verdade (SDO_RELATE confirma). Causa raiz identica a V060:
-- calcular_idesmat (pkg_calculo_ambiental, V045) filtra os candidatos com
-- "AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, 0.05) = 'TRUE'" ANTES de
-- tentar dissolver/interseccionar - e ZERO dos 1.428 poligonos de desmatamento
-- passam essa checagem estrita (confirmado por COUNT direto). O fix da V060 (que
-- resolveu o mesmo problema em pkg_calculo_territorial.calcular_isap/calcular_iae)
-- nunca foi replicado aqui, porque pkg_calculo_ambiental e um package separado.
--
-- Mesma correcao, mesmo padrao: tira o filtro de validacao previa, intersecciona
-- CADA candidato individualmente com o imovel (geometria original, sem rectify),
-- cada um protegido por EXCEPTION WHEN OTHERS (pula so aquele candidato se a
-- intersecao dele especificamente falhar), dissolve so os PEDACOS DE INTERSECAO
-- resultantes no final. calcular_ifc nao tem esse problema (e so contagem de pontos
-- via SDO_RELATE, sem validacao/dissolve) - fica como estava.
CREATE OR REPLACE PACKAGE BODY ${goldSchema}.pkg_calculo_ambiental AS

    c_tolerancia CONSTANT NUMBER := 0.05; -- mesma tolerancia numerica do resto do projeto

    PROCEDURE calcular_idesmat(
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
                      FROM ${silverSchema}.desmatamento
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
