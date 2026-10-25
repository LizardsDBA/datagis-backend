-- Blindagem de defesa-em-profundidade pra calcular_isap/calcular_iae/calcular_iapp -
-- achado investigando o ISAP=0% do imovel PR-4119905-D0759104... (docs/publicacao-
-- gold-paralela.md secao 12): SDO_GEOM.SDO_INTERSECTION NAO lanca erro contra
-- geometria topologicamente invalida (anel invertido, vertice duplicado, auto-
-- intersecao) - ele silenciosamente devolve uma area "lixo" (perto de zero), que
-- entra na soma como se fosse uma interseccao real, zerando o indicador sem
-- nenhum sinal de erro.
--
-- A causa raiz (geometria nascendo invalida) foi corrigida na entrada
-- (ValidadorGeometria agora orienta o anel na convencao do Oracle - ver mudanca
-- Java/commit desta mesma sessao) e os lotes ja ingeridos foram reprocessados.
-- Essa migration e so a rede de seguranca pro residual que a correcao de entrada
-- sozinha nao consegue resolver (auto-intersecao e vertice duplicado que o
-- GeometryFixer do JTS nao normaliza pra convencao do Oracle em 100% dos casos -
-- medido: ~8% de imovel_rural, ~33% de area_protegida continuam invalidas mesmo
-- apos a correcao). Candidato que falha SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT e
-- simplesmente pulado (CONTINUE) - fica de fora da soma, em vez de contaminar o
-- resultado com area lixo.
--
-- calcular_irl e calcular_icv NAO precisam dessa blindagem - somam area_ha
-- declarada por cod_imovel, sem nenhum SDO_GEOM.SDO_INTERSECTION.
CREATE OR REPLACE EDITIONABLE PACKAGE BODY "GOLD"."PKG_CALCULO_TERRITORIAL" AS

    c_tolerancia CONSTANT NUMBER := 0.05;

    PROCEDURE calcular_isap(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
        v_geom_imovel     MDSYS.SDO_GEOMETRY;
        v_area_imovel_ha  NUMBER;
        v_intersecs       GOLD.t_geom_tab := GOLD.t_geom_tab();
        v_fontes          GOLD.t_fonte_lote_tab := GOLD.t_fonte_lote_tab();
        v_uniao           MDSYS.SDO_GEOMETRY;
        v_intersec        MDSYS.SDO_GEOMETRY;
        v_area_ha         NUMBER := 0;
        v_percentual      NUMBER := 0;
        v_validacao       VARCHAR2(4000);
    BEGIN
        SELECT geometria, area_ha INTO v_geom_imovel, v_area_imovel_ha
          FROM SILVER.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT geometria, fonte_dado_id, lote_ingestao_id
                      FROM SILVER.area_protegida
                     WHERE SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE') LOOP
            BEGIN
                v_validacao := SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(rec.geometria, c_tolerancia);
            EXCEPTION WHEN OTHERS THEN
                v_validacao := NULL;
            END;
            IF v_validacao IS NULL OR v_validacao != 'TRUE' THEN
                CONTINUE; -- candidato ainda invalido - nunca entra na soma
            END IF;

            BEGIN
                v_intersec := SDO_GEOM.SDO_INTERSECTION(v_geom_imovel, rec.geometria, c_tolerancia);
            EXCEPTION WHEN OTHERS THEN
                v_intersec := NULL;
            END;
            IF v_intersec IS NOT NULL THEN
                v_intersecs.EXTEND;
                v_intersecs(v_intersecs.LAST) := v_intersec;
                v_fontes.EXTEND;
                v_fontes(v_fontes.LAST) := GOLD.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
            END IF;
        END LOOP;

        IF v_intersecs.COUNT > 0 THEN
            v_uniao := GOLD.pkg_gold_publicacao.f_dissolver(v_intersecs);
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

        GOLD.pkg_gold_publicacao.registrar_resultado(
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
        v_intersecs       GOLD.t_geom_tab := GOLD.t_geom_tab();
        v_fontes          GOLD.t_fonte_lote_tab := GOLD.t_fonte_lote_tab();
        v_uniao           MDSYS.SDO_GEOMETRY;
        v_intersec        MDSYS.SDO_GEOMETRY;
        v_area_ha         NUMBER := 0;
        v_percentual      NUMBER := 0;
        v_validacao       VARCHAR2(4000);
    BEGIN
        SELECT geometria, area_ha INTO v_geom_imovel, v_area_imovel_ha
          FROM SILVER.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT geometria, fonte_dado_id, lote_ingestao_id
                      FROM SILVER.embargo_ambiental
                     WHERE geometria IS NOT NULL
                       AND SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE') LOOP
            BEGIN
                v_validacao := SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(rec.geometria, c_tolerancia);
            EXCEPTION WHEN OTHERS THEN
                v_validacao := NULL;
            END;
            IF v_validacao IS NULL OR v_validacao != 'TRUE' THEN
                CONTINUE;
            END IF;

            BEGIN
                v_intersec := SDO_GEOM.SDO_INTERSECTION(v_geom_imovel, rec.geometria, c_tolerancia);
            EXCEPTION WHEN OTHERS THEN
                v_intersec := NULL;
            END;
            IF v_intersec IS NOT NULL THEN
                v_intersecs.EXTEND;
                v_intersecs(v_intersecs.LAST) := v_intersec;
                v_fontes.EXTEND;
                v_fontes(v_fontes.LAST) := GOLD.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
            END IF;
        END LOOP;

        IF v_intersecs.COUNT > 0 THEN
            v_uniao := GOLD.pkg_gold_publicacao.f_dissolver(v_intersecs);
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

        GOLD.pkg_gold_publicacao.registrar_resultado(
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
        v_fontes          GOLD.t_fonte_lote_tab := GOLD.t_fonte_lote_tab();
    BEGIN
        SELECT area_ha INTO v_area_imovel_ha
          FROM SILVER.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT area_ha, fonte_dado_id, lote_ingestao_id
                      FROM SILVER.reserva_legal
                     WHERE cod_imovel = p_cod_imovel AND area_ha IS NOT NULL) LOOP
            v_area_rl_ha := v_area_rl_ha + rec.area_ha;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := GOLD.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
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
        GOLD.pkg_gold_publicacao.registrar_resultado(
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
        v_intersecs       GOLD.t_geom_tab := GOLD.t_geom_tab();
        v_fontes          GOLD.t_fonte_lote_tab := GOLD.t_fonte_lote_tab();
        v_uniao           MDSYS.SDO_GEOMETRY;
        v_intersec        MDSYS.SDO_GEOMETRY;
        v_validacao       VARCHAR2(4000);
    BEGIN
        -- Confirma que o imovel existe (mesmo guard NO_DATA_FOUND que os outros
        -- calculadores) - o denominador do IAPP e SUM(app_declarada), nao a area
        -- do imovel, entao o valor em si e so descartado aqui.
        SELECT area_ha INTO v_area_imovel_dummy
          FROM SILVER.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR app IN (SELECT area_ha, fonte_dado_id, lote_ingestao_id
                      FROM SILVER.app_declarada
                     WHERE cod_imovel = p_cod_imovel AND area_ha IS NOT NULL) LOOP
            v_area_app_ha := v_area_app_ha + app.area_ha;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := GOLD.t_fonte_lote(app.fonte_dado_id, app.lote_ingestao_id);
        END LOOP;

        IF v_area_app_ha > 0 THEN
            -- Cruza CADA fragmento de APP com CADA fragmento de vegetacao nativa
            -- do mesmo imovel - interseccao individual (ver cabecalho da migration
            -- pro motivo de nunca dissolver os originais antes).
            FOR app IN (SELECT geometria FROM SILVER.app_declarada
                         WHERE cod_imovel = p_cod_imovel AND geometria IS NOT NULL) LOOP
                BEGIN
                    v_validacao := SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(app.geometria, c_tolerancia);
                EXCEPTION WHEN OTHERS THEN
                    v_validacao := NULL;
                END;
                IF v_validacao IS NULL OR v_validacao != 'TRUE' THEN
                    CONTINUE; -- fragmento de APP invalido - pula o par inteiro
                END IF;

                FOR veg IN (SELECT geometria FROM SILVER.vegetacao_nativa_declarada
                             WHERE cod_imovel = p_cod_imovel AND geometria IS NOT NULL) LOOP
                    BEGIN
                        v_validacao := SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(veg.geometria, c_tolerancia);
                    EXCEPTION WHEN OTHERS THEN
                        v_validacao := NULL;
                    END;
                    IF v_validacao IS NULL OR v_validacao != 'TRUE' THEN
                        CONTINUE;
                    END IF;

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
                v_uniao := GOLD.pkg_gold_publicacao.f_dissolver(v_intersecs);
                IF v_uniao IS NOT NULL THEN
                    v_area_sobreposta := SDO_GEOM.SDO_AREA(v_uniao, c_tolerancia, 'unit=Hectare');
                    IF v_area_sobreposta > v_area_app_ha THEN
                        v_area_sobreposta := v_area_app_ha;
                    END IF;
                    v_percentual := v_area_sobreposta / v_area_app_ha * 100;
                END IF;
            END IF;
        END IF;

        GOLD.pkg_gold_publicacao.registrar_resultado(
            p_cod_imovel, 'IAPP', p_regra_calculo_id,
            v_percentual, v_area_sobreposta, v_percentual, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL;
    END calcular_iapp;

END pkg_calculo_territorial;
/
