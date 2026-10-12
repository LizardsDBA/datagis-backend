-- [CORRIGE A V059 - abordagem errada] A V059 tentava resolver o ISAP/IAE zerado
-- reparando a geometria invalida com SDO_UTIL.RECTIFY_GEOMETRY antes de dissolver e
-- so depois interseccionar com o imovel. Testado ao vivo contra o caso real (imovel
-- PR-4119905-903A855338244925B4730581E2C98D5D, contido por completo em 4 unidades
-- de conservacao, confirmado via SDO_GEOM.RELATE = CONTAINS): a geometria ORIGINAL
-- (nao reparada) intersecciona CORRETAMENTE com o imovel (SDO_GEOM.SDO_INTERSECTION
-- devolve a area certa, 21,31 ha = 100% do imovel) MESMO sendo "invalida" pela
-- checagem estrita do Oracle (ORA-13356, vertice duplicado) - a V046 original so
-- falhava porque filtrava essa geometria ANTES de tentar, nunca por ela realmente
-- quebrar o calculo. O RECTIFY_GEOMETRY da V059, ironicamente, e quem QUEBRA: a
-- geometria reparada passa a validacao, mas o SDO_INTERSECTION com ela devolve NULL
-- (silencioso, sem erro) - o reparo da V059 so trocou "descartado pela validacao"
-- por "descartado pela intersecao falhar depois", mesmo resultado final (zero).
--
-- O que de fato quebra e o PASSO DE DISSOLVER (SDO_AGGR_UNION, em f_dissolver) as
-- geometrias ORIGINAIS de area_protegida/embargo_ambiental juntas antes de
-- interseccionar com o imovel - isso sim da erro de verdade (ORA-13050, "unable to
-- construct spatial object") quando uma das geometrias do conjunto tem o defeito de
-- vertice duplicado. A V046 original escondia esse erro ao filtrar a geometria
-- defeituosa antes de chegar no dissolver; a V059 tentava evitar o erro reparando-a
-- antes, mas isso degradou a geometria o suficiente pra SDO_INTERSECTION falhar.
--
-- Correcao de verdade, testada ao vivo (mesmo imovel, resultado correto 21,31 ha):
-- inverte a ordem das operacoes. Em vez de (dissolver os candidatos originais) ->
-- (interseccionar com o imovel), faz (interseccionar CADA candidato original,
-- individualmente, com o imovel) -> (dissolver os PEDACOS DE INTERSECAO resultantes).
-- A geometria original "invalida" funciona bem o suficiente pro SDO_INTERSECTION
-- individual (que so trabalha numa area pequena, o recorte dentro do imovel); o
-- dissolver só ve geometrias ja recortadas e limpas (os pedacos de intersecao),
-- nunca as fontes problematicas inteiras - e isso elimina o ORA-13050 na pratica.
-- Cada candidato individual fica protegido por EXCEPTION WHEN OTHERS (pula so esse
-- pedaco se a intersecao dele especificamente falhar, nunca o imovel inteiro).
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
