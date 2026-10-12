-- ICV deixa de ser stub - fonte ja ingerida e confirmada (silver.vegetacao_nativa_declarada
-- 3.182 registros, CLAUDE.md §9.36/§9.37). E o mais simples dos 7: razao direta de
-- area, sem interseccao espacial (SUM(area_ha) declarado / area do imovel) - nao tem
-- a complicacao do IAPP (cruzamento de geometrias) nem a decisao de escopo do IRL
-- (minimo por bioma). cod_imovel nao e chave unica em vegetacao_nativa_declarada (um
-- imovel pode ter varios fragmentos, CLAUDE.md §9.36) - soma todos, nao pega so o
-- primeiro.
CREATE OR REPLACE PACKAGE BODY ${goldSchema}.pkg_calculo_conservacao AS

    PROCEDURE calcular_icv(
        p_cod_imovel          IN  VARCHAR2,
        p_regra_calculo_id    IN  NUMBER,
        p_indicador_versao_id OUT NUMBER
    ) IS
        v_area_imovel_ha NUMBER;
        v_area_veg_ha     NUMBER := 0;
        v_percentual      NUMBER := 0;
        v_fontes          ${goldSchema}.t_fonte_lote_tab := ${goldSchema}.t_fonte_lote_tab();
    BEGIN
        SELECT area_ha INTO v_area_imovel_ha
          FROM ${silverSchema}.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT area_ha, fonte_dado_id, lote_ingestao_id
                      FROM ${silverSchema}.vegetacao_nativa_declarada
                     WHERE cod_imovel = p_cod_imovel AND area_ha IS NOT NULL) LOOP
            v_area_veg_ha := v_area_veg_ha + rec.area_ha;
            v_fontes.EXTEND;
            v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
        END LOOP;

        IF v_area_imovel_ha > 0 THEN
            -- sanity clamp (mesmo motivo da V063/V064): soma de fragmentos
            -- declarados nao pode exceder a area do proprio imovel.
            IF v_area_veg_ha > v_area_imovel_ha THEN
                v_area_veg_ha := v_area_imovel_ha;
            END IF;
            v_percentual := v_area_veg_ha / v_area_imovel_ha * 100;
        END IF;

        ${goldSchema}.pkg_gold_publicacao.registrar_resultado(
            p_cod_imovel, 'ICV', p_regra_calculo_id,
            v_percentual, v_area_veg_ha, v_percentual, v_fontes, p_indicador_versao_id);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        p_indicador_versao_id := NULL;
    END calcular_icv;

END pkg_calculo_conservacao;
/
