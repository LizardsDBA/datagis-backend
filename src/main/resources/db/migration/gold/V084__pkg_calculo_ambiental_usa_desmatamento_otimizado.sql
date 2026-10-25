-- calcular_idesmat passa a ler gold.desmatamento_otimizado (V083) em vez de
-- SILVER.desmatamento - mesmas 7 linhas trocadas, resto da procedure intacto.
-- GoldPublicacaoService.publicarTudo() reconstroi a tabela otimizada (JTS, fora do
-- PGA do Oracle) antes do loop por imovel, sempre que a publicacao roda.
CREATE OR REPLACE EDITIONABLE PACKAGE BODY "GOLD"."PKG_CALCULO_AMBIENTAL" AS

    c_tolerancia CONSTANT NUMBER := 0.05; -- mesma tolerancia numerica do resto do projeto

    PROCEDURE calcular_idesmat(
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
    BEGIN
        SELECT geometria, area_ha INTO v_geom_imovel, v_area_imovel_ha
          FROM SILVER.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT geometria, fonte_dado_id, lote_ingestao_id
                      FROM GOLD.desmatamento_otimizado
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
                v_fontes(v_fontes.LAST) := GOLD.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
            END IF;
        END LOOP;

        IF v_intersecs.COUNT > 0 THEN
            v_uniao := GOLD.pkg_gold_publicacao.f_dissolver(v_intersecs);
            IF v_uniao IS NOT NULL THEN
                v_area_ha := SDO_GEOM.SDO_AREA(v_uniao, c_tolerancia, 'unit=Hectare');
                -- sanity clamp: ver V063, mesma causa raiz (geometria do imovel
                -- corrompida fazendo SDO_GEOM devolver area absurda sem erro).
                IF v_area_ha > v_area_imovel_ha THEN
                    v_area_ha := v_area_imovel_ha;
                END IF;
                IF v_area_imovel_ha > 0 THEN
                    v_percentual := v_area_ha / v_area_imovel_ha * 100;
                END IF;
            END IF;
        END IF;

        GOLD.pkg_gold_publicacao.registrar_resultado(
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
        v_fontes         GOLD.t_fonte_lote_tab := GOLD.t_fonte_lote_tab();
    BEGIN
        SELECT geometria, area_ha INTO v_geom_imovel, v_area_imovel_ha
          FROM SILVER.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT fonte_dado_id, lote_ingestao_id FROM SILVER.foco_calor
                     WHERE SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE') LOOP
            v_contagem := v_contagem + 1;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := GOLD.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
        END LOOP;

        IF v_area_imovel_ha > 0 THEN
            v_densidade := v_contagem / v_area_imovel_ha * 1000;
        END IF;

        GOLD.pkg_gold_publicacao.registrar_resultado(
            p_cod_imovel, 'IFC', p_regra_calculo_id,
            v_densidade, NULL, NULL, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL;
    END calcular_ifc;

END pkg_calculo_ambiental;
/
