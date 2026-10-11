-- IRL e IAPP deixam de ser stub - fonte ja ingerida e confirmada (silver.reserva_legal
-- 2.777, silver.app_declarada 3.180, silver.vegetacao_nativa_declarada 3.182,
-- CLAUDE.md §9.36/§9.37). calcular_isap/calcular_iae permanecem identicas a V063 (nao
-- mudaram, so repetidas aqui porque CREATE OR REPLACE PACKAGE BODY substitui o corpo
-- inteiro).
--
-- IRL - decisao de escopo registrada aqui e em CLAUDE.md §9.37: o percentual minimo
-- de Reserva Legal exigido por lei (Codigo Florestal, Lei 12.651/2012) varia por
-- bioma (Mata Atlantica = 20%, Amazonia Legal = 80% em floresta, etc.). O schema
-- (silver.bioma + silver.municipio.bioma_predominante_id) foi desenhado pra suportar
-- isso via JOIN, mas NENHUM municipio tem bioma_predominante_id preenchido (nunca foi
-- populado) e silver.bioma nao tem coluna de percentual minimo - popular isso de
-- verdade e trabalho de engenharia de dado (join espacial municipio x bioma), nao so
-- trocar 1 numero. FIXADO COMO CONSTANTE de proposito: o recorte territorial do
-- desafio oficialmente E Parana, bioma predominante Mata Atlantica (CLAUDE.md §1) -
-- 20% e o numero certo pro escopo deste projeto. Se um dia sair desse recorte, trocar
-- por JOIN em silver.bioma - mudanca isolada dentro desta procedure, nao afeta mais
-- nada (nem o schema de indicador_resultado, nem os outros 6 indicadores).
-- "valor" armazenado = percentual real de RL (mesmo padrao de ISAP/IAE/IDesmat/ICV),
-- nao uma razao contra o minimo - c_percentual_minimo_mata_atlantica fica disponivel
-- como referencia documentada pra quem for comparar depois, sem precisar recalcular.
--
-- IAPP - unica das 3 que precisa de interseccao espacial: a "APP com vegetacao
-- nativa" e a interseccao entre app_declarada e vegetacao_nativa_declarada do MESMO
-- imovel, dividido pela area total de app_declarada do imovel. Mesmo padrao
-- corrigido em V060/V064 (nao repetir o bug): nunca dissolver os fragmentos
-- ORIGINAIS antes de interseccionar (geometria com defeito de vertice duplicado
-- quebra SDO_AGGR_UNION mesmo funcionando bem num SDO_INTERSECTION direto) - cruza
-- CADA fragmento de APP com CADA fragmento de vegetacao nativa do imovel
-- individualmente (protegido por EXCEPTION WHEN OTHERS, pula so o par problematico),
-- dissolve so os PEDACOS DE INTERSECAO resultantes no final. cod_imovel nao e chave
-- unica nessas tabelas (um imovel pode ter varios fragmentos, CLAUDE.md §9.36) -
-- por isso o cruzamento e todos-contra-todos, nao "a primeira linha de cada".
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
        -- Ver cabecalho da migration pra justificativa completa.
        c_percentual_minimo_mata_atlantica CONSTANT NUMBER := 20;

        v_area_imovel_ha NUMBER;
        v_area_rl_ha      NUMBER := 0;
        v_percentual      NUMBER := 0;
        v_fontes          ${goldSchema}.t_fonte_lote_tab := ${goldSchema}.t_fonte_lote_tab();
    BEGIN
        SELECT area_ha INTO v_area_imovel_ha
          FROM ${silverSchema}.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT area_ha, fonte_dado_id, lote_ingestao_id
                      FROM ${silverSchema}.reserva_legal
                     WHERE cod_imovel = p_cod_imovel AND area_ha IS NOT NULL) LOOP
            v_area_rl_ha := v_area_rl_ha + rec.area_ha;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
        END LOOP;

        IF v_area_imovel_ha > 0 THEN
            -- sanity clamp (mesmo motivo da V063/V064): soma de fragmentos
            -- declarados nao pode exceder a area do proprio imovel.
            IF v_area_rl_ha > v_area_imovel_ha THEN
                v_area_rl_ha := v_area_imovel_ha;
            END IF;
            v_percentual := v_area_rl_ha / v_area_imovel_ha * 100;
        END IF;

        -- c_percentual_minimo_mata_atlantica nao entra no "valor" armazenado -
        -- valor = percentual real de RL (mesmo padrao de ISAP/IAE/IDesmat/ICV),
        -- fica so como referencia documentada pra quem for comparar depois.
        ${goldSchema}.pkg_gold_publicacao.registrar_resultado(
            p_cod_imovel, 'IRL', p_regra_calculo_id,
            v_percentual, v_area_rl_ha, v_percentual, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL;
    END calcular_irl;

    PROCEDURE calcular_iapp(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
        v_area_imovel_dummy NUMBER;
        v_area_app_ha     NUMBER := 0;
        v_area_sobreposta NUMBER := 0;
        v_percentual      NUMBER := 0;
        v_intersecs       ${goldSchema}.t_geom_tab := ${goldSchema}.t_geom_tab();
        v_fontes          ${goldSchema}.t_fonte_lote_tab := ${goldSchema}.t_fonte_lote_tab();
        v_uniao           MDSYS.SDO_GEOMETRY;
        v_intersec        MDSYS.SDO_GEOMETRY;
    BEGIN
        -- Confirma que o imovel existe (mesmo guard NO_DATA_FOUND que os outros
        -- calculadores) - o denominador do IAPP e SUM(app_declarada), nao a area
        -- do imovel, entao o valor em si e so descartado aqui.
        SELECT area_ha INTO v_area_imovel_dummy
          FROM ${silverSchema}.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR app IN (SELECT area_ha, fonte_dado_id, lote_ingestao_id
                      FROM ${silverSchema}.app_declarada
                     WHERE cod_imovel = p_cod_imovel AND area_ha IS NOT NULL) LOOP
            v_area_app_ha := v_area_app_ha + app.area_ha;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(app.fonte_dado_id, app.lote_ingestao_id);
        END LOOP;

        IF v_area_app_ha > 0 THEN
            -- Cruza CADA fragmento de APP com CADA fragmento de vegetacao nativa
            -- do mesmo imovel - interseccao individual (ver cabecalho da migration
            -- pro motivo de nunca dissolver os originais antes).
            FOR app IN (SELECT geometria FROM ${silverSchema}.app_declarada
                         WHERE cod_imovel = p_cod_imovel AND geometria IS NOT NULL) LOOP
                FOR veg IN (SELECT geometria FROM ${silverSchema}.vegetacao_nativa_declarada
                             WHERE cod_imovel = p_cod_imovel AND geometria IS NOT NULL) LOOP
                    BEGIN
                        v_intersec := SDO_GEOM.SDO_INTERSECTION(app.geometria, veg.geometria, c_tolerancia);
                    EXCEPTION WHEN OTHERS THEN
                        v_intersec := NULL;
                    END;
                    IF v_intersec IS NOT NULL THEN
                        v_intersecs.EXTEND;
                        v_intersecs(v_intersecs.LAST) := v_intersec;
                    END IF;
                END LOOP;
            END LOOP;

            IF v_intersecs.COUNT > 0 THEN
                v_uniao := ${goldSchema}.pkg_gold_publicacao.f_dissolver(v_intersecs);
                IF v_uniao IS NOT NULL THEN
                    v_area_sobreposta := SDO_GEOM.SDO_AREA(v_uniao, c_tolerancia, 'unit=Hectare');
                    IF v_area_sobreposta > v_area_app_ha THEN
                        v_area_sobreposta := v_area_app_ha;
                    END IF;
                    v_percentual := v_area_sobreposta / v_area_app_ha * 100;
                END IF;
            END IF;
        END IF;

        ${goldSchema}.pkg_gold_publicacao.registrar_resultado(
            p_cod_imovel, 'IAPP', p_regra_calculo_id,
            v_percentual, v_area_sobreposta, v_percentual, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL;
    END calcular_iapp;

END pkg_calculo_territorial;
/
