-- Achado testando calcular_isap/calcular_iae contra o dado real em escala (16.086
-- registros de area_protegida, ICMBio+FUNAI+MMA+SFB): 0 de 4.444 imoveis publicados
-- deram ISAP/IAE > 0, mesmo com sobreposicao espacial REAL confirmada ao vivo (ex.:
-- varios imoveis de Ponta Grossa dentro da "Area de Protecao Ambiental Estadual da
-- Escarpa Devoniana", ids 2754/3841).
--
-- Causa raiz: o WHERE da V046 exige
--   SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia) = 'TRUE'
-- antes de considerar a geometria candidata - e numa amostra real, so ~2,8% passa
-- (a maioria falha com ORA-13356, vertice duplicado). Como o filtro vive no WHERE,
-- a geometria invalida e descartada SILENCIOSAMENTE antes de qualquer tentativa de
-- interseccao - e com area_protegida tendo so 16 mil linhas no total hoje (nacional,
-- nao vai crescer com o circuito), praticamente nenhum imovel sobra com candidato
-- valido pra comparar.
--
-- Correcao: mesmo principio ja usado na ingestao (ValidadorGeometria.tentarReparar,
-- GeometryFixer) e na publicacao de mapa (publicar_camada_mapa, FOR+EXCEPTION) -
-- reparar automaticamente em vez de so validar-e-descartar. Tira o filtro de
-- validade do WHERE (que buscava TODOS os candidatos que realmente interseccionam,
-- validos ou nao) e, pra cada candidato invalido, tenta SDO_UTIL.RECTIFY_GEOMETRY
-- antes de desistir dele. So pula o candidato individual se nem o reparo resolver -
-- nunca derruba o calculo do imovel inteiro por isso.
CREATE OR REPLACE PACKAGE BODY ${goldSchema}.pkg_calculo_territorial AS

    c_tolerancia CONSTANT NUMBER := 0.05;

    -- Valida a geometria candidata; se invalida, tenta reparar com
    -- SDO_UTIL.RECTIFY_GEOMETRY antes de desistir. Devolve NULL só quando nem o
    -- reparo resolve (candidato genuinamente problemático - pula só ele).
    FUNCTION f_validar_ou_reparar(p_geom IN MDSYS.SDO_GEOMETRY) RETURN MDSYS.SDO_GEOMETRY IS
        v_geom MDSYS.SDO_GEOMETRY := p_geom;
    BEGIN
        IF SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(v_geom, c_tolerancia) = 'TRUE' THEN
            RETURN v_geom;
        END IF;
        BEGIN
            v_geom := SDO_UTIL.RECTIFY_GEOMETRY(p_geom, c_tolerancia);
        EXCEPTION WHEN OTHERS THEN
            RETURN NULL;
        END;
        IF v_geom IS NOT NULL AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(v_geom, c_tolerancia) = 'TRUE' THEN
            RETURN v_geom;
        END IF;
        RETURN NULL;
    END f_validar_ou_reparar;

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
        v_geom_ok         MDSYS.SDO_GEOMETRY;
    BEGIN
        SELECT geometria, area_ha INTO v_geom_imovel, v_area_imovel_ha
          FROM ${silverSchema}.imovel_rural WHERE cod_imovel = p_cod_imovel;

        FOR rec IN (SELECT geometria, fonte_dado_id, lote_ingestao_id
                      FROM ${silverSchema}.area_protegida
                     WHERE SDO_RELATE(geometria, v_geom_imovel, 'mask=ANYINTERACT') = 'TRUE') LOOP
            v_geom_ok := f_validar_ou_reparar(rec.geometria);
            IF v_geom_ok IS NOT NULL THEN
                v_geoms.EXTEND;
                v_geoms(v_geoms.LAST) := v_geom_ok;
                v_fontes.EXTEND;
                v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
            END IF;
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
        v_geom_ok         MDSYS.SDO_GEOMETRY;
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
            v_geom_ok := f_validar_ou_reparar(rec.geometria);
            IF v_geom_ok IS NOT NULL THEN
                v_geoms.EXTEND;
                v_geoms(v_geoms.LAST) := v_geom_ok;
                v_fontes.EXTEND;
                v_fontes(v_fontes.LAST) := ${goldSchema}.t_fonte_lote(rec.fonte_dado_id, rec.lote_ingestao_id);
            END IF;
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
