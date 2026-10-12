-- Decisao de negocio: nenhum registro pode ser descartado de antemao, nem o mais
-- pesado (silver.desmatamento.id=1140, 634 mil ordenadas) - o corte de tamanho da
-- V066/V068 (c_max_ordenadas_simplify) era precaucao minha, baseada no estouro de
-- PGA quando essa geometria era processada numa SESSAO COMPARTILHADA com muitas
-- outras (V065). Nunca foi testado isoladamente - com lote=1 (Java,
-- GoldPublicacaoService) cada registro ja roda em sessao Oracle propria, sem
-- concorrencia de memoria, e com timeout bem maior (30min) pra dar tempo real de
-- terminar. Removido o guard - toda geometria tenta SDO_UTIL.SIMPLIFY de verdade;
-- só um erro de SQL genuino (ORA-13199 etc.) ou o timeout do lado Java ainda pulam
-- um registro, nunca mais uma decisao preventiva por tamanho.
CREATE OR REPLACE PACKAGE BODY ${goldSchema}.pkg_gold_publicacao AS

    c_tolerancia_calculo     CONSTANT NUMBER := 0.05;     -- fuzz numerico SDO_GEOM
    c_tolerancia_mapa        CONSTANT NUMBER := 0.00005;  -- generalizacao visual (~5m)

    FUNCTION f_dissolver(p_geoms IN ${goldSchema}.t_geom_tab) RETURN MDSYS.SDO_GEOMETRY IS
        v_resultado MDSYS.SDO_GEOMETRY;
    BEGIN
        IF p_geoms IS NULL OR p_geoms.COUNT = 0 THEN
            RETURN NULL;
        END IF;
        IF p_geoms.COUNT = 1 THEN
            RETURN p_geoms(1);
        END IF;
        SELECT SDO_AGGR_UNION(SDOAGGRTYPE(VALUE(t), c_tolerancia_calculo))
          INTO v_resultado
          FROM TABLE(p_geoms) t;
        RETURN v_resultado;
    END f_dissolver;

    FUNCTION f_ultimo_valor(p_cod_imovel VARCHAR2, p_tipo_codigo VARCHAR2) RETURN NUMBER IS
        v_valor NUMBER;
    BEGIN
        SELECT ir.valor INTO v_valor
          FROM ${goldSchema}.indicador_resultado ir
          JOIN ${goldSchema}.indicador_versao iv ON iv.id = ir.indicador_versao_id
          JOIN ${goldSchema}.tipo_indicador ti ON ti.id = ir.tipo_indicador_id
         WHERE iv.cod_imovel = p_cod_imovel AND ti.codigo = p_tipo_codigo
         ORDER BY iv.data_processamento DESC
         FETCH FIRST 1 ROW ONLY;
        RETURN v_valor;
    EXCEPTION WHEN NO_DATA_FOUND THEN
        RETURN NULL;
    END f_ultimo_valor;

    PROCEDURE registrar_resultado(
        p_cod_imovel           IN  VARCHAR2,
        p_tipo_indicador_cod   IN  VARCHAR2,
        p_regra_calculo_id     IN  NUMBER,
        p_valor                IN  NUMBER,
        p_area_afetada_ha      IN  NUMBER,
        p_percentual           IN  NUMBER,
        p_fontes               IN  ${goldSchema}.t_fonte_lote_tab,
        p_indicador_versao_id  OUT NUMBER
    ) IS
        v_regra_id  NUMBER;
        v_lotes_csv VARCHAR2(4000);
        v_base      VARCHAR2(4000);
        v_hash      VARCHAR2(64);
    BEGIN
        IF p_regra_calculo_id IS NOT NULL THEN
            v_regra_id := p_regra_calculo_id;
        ELSE
            SELECT rc.id INTO v_regra_id
              FROM ${goldSchema}.regra_calculo rc
              JOIN ${goldSchema}.tipo_indicador ti ON ti.id = rc.tipo_indicador_id
             WHERE ti.codigo = p_tipo_indicador_cod
               AND rc.vigente_de <= TRUNC(SYSDATE)
               AND (rc.vigente_ate IS NULL OR rc.vigente_ate >= TRUNC(SYSDATE))
             ORDER BY rc.versao DESC
             FETCH FIRST 1 ROW ONLY;
        END IF;

        IF p_fontes IS NOT NULL AND p_fontes.COUNT > 0 THEN
            SELECT LISTAGG(lote_ingestao_id, ',') WITHIN GROUP (ORDER BY lote_ingestao_id)
              INTO v_lotes_csv
              FROM (SELECT DISTINCT t.lote_ingestao_id FROM TABLE(p_fontes) t);
        END IF;

        v_base := p_cod_imovel || '|' || v_regra_id || '|' || NVL(v_lotes_csv, '') || '|' ||
                  p_tipo_indicador_cod || ':' || TO_CHAR(NVL(p_valor, -1)) || ':' ||
                  TO_CHAR(NVL(p_area_afetada_ha, -1)) || ':' || TO_CHAR(NVL(p_percentual, -1));
        v_hash := RAWTOHEX(DBMS_CRYPTO.HASH(UTL_I18N.STRING_TO_RAW(v_base, 'AL32UTF8'), DBMS_CRYPTO.HASH_SH256));

        BEGIN
            SELECT id INTO p_indicador_versao_id
              FROM ${goldSchema}.indicador_versao WHERE hash_versao = v_hash;
            RETURN;
        EXCEPTION WHEN NO_DATA_FOUND THEN
            NULL;
        END;

        INSERT INTO ${goldSchema}.indicador_versao (cod_imovel, regra_calculo_id, hash_versao)
        VALUES (p_cod_imovel, v_regra_id, v_hash)
        RETURNING id INTO p_indicador_versao_id;

        INSERT INTO ${goldSchema}.indicador_resultado
            (indicador_versao_id, tipo_indicador_id, valor, area_afetada_ha, percentual_sobreposicao)
        SELECT p_indicador_versao_id, ti.id, p_valor, p_area_afetada_ha, p_percentual
          FROM ${goldSchema}.tipo_indicador ti WHERE ti.codigo = p_tipo_indicador_cod;

        IF p_fontes IS NOT NULL THEN
            FOR i IN 1 .. p_fontes.COUNT LOOP
                BEGIN
                    INSERT INTO ${goldSchema}.indicador_versao_fonte
                        (indicador_versao_id, fonte_dado_id, lote_ingestao_id)
                    VALUES (p_indicador_versao_id, p_fontes(i).fonte_dado_id, p_fontes(i).lote_ingestao_id);
                EXCEPTION WHEN DUP_VAL_ON_INDEX THEN
                    NULL;
                END;
            END LOOP;
        END IF;
    END registrar_resultado;

    PROCEDURE p_gravar_sobreposicao_area_protegida(p_imovel_publicado_id NUMBER, p_geom_imovel MDSYS.SDO_GEOMETRY, p_area_imovel_ha NUMBER) IS
        v_geoms      ${goldSchema}.t_geom_tab := ${goldSchema}.t_geom_tab();
        v_nomes      VARCHAR2(4000);
        v_orgaos     VARCHAR2(200);
        v_fonte_id   NUMBER;
        v_lote_id    NUMBER;
        v_uniao      MDSYS.SDO_GEOMETRY;
        v_intersec   MDSYS.SDO_GEOMETRY;
        v_area_ha    NUMBER;
    BEGIN
        SELECT LISTAGG(SUBSTR(ap.nome, 1, 60), '; ') WITHIN GROUP (ORDER BY ap.id),
               LISTAGG(DISTINCT o.codigo, '/') WITHIN GROUP (ORDER BY o.codigo),
               MAX(ap.fonte_dado_id), MAX(ap.lote_ingestao_id)
          INTO v_nomes, v_orgaos, v_fonte_id, v_lote_id
          FROM ${silverSchema}.area_protegida ap
          JOIN ${silverSchema}.orgao o ON o.id = ap.orgao_id
         WHERE SDO_RELATE(ap.geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
           AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(ap.geometria, c_tolerancia_calculo) = 'TRUE';

        FOR rec IN (SELECT geometria FROM ${silverSchema}.area_protegida
                     WHERE SDO_RELATE(geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
                       AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia_calculo) = 'TRUE') LOOP
            v_geoms.EXTEND;
            v_geoms(v_geoms.LAST) := rec.geometria;
        END LOOP;
        IF v_geoms.COUNT = 0 THEN
            RETURN;
        END IF;

        v_uniao := f_dissolver(v_geoms);
        v_intersec := SDO_GEOM.SDO_INTERSECTION(p_geom_imovel, v_uniao, c_tolerancia_calculo);
        IF v_intersec IS NULL THEN
            RETURN;
        END IF;
        v_area_ha := SDO_GEOM.SDO_AREA(v_intersec, c_tolerancia_calculo, 'unit=Hectare');

        INSERT INTO ${goldSchema}.sobreposicao_publicada
            (imovel_publicado_id, tipo_restricao, nome, orgao, geometria_intersecao,
             area_intersecao_ha, percentual, fonte_dado_id, lote_ingestao_id)
        VALUES (p_imovel_publicado_id, 'AREA_PROTEGIDA', v_nomes, v_orgaos, v_intersec,
                v_area_ha, CASE WHEN p_area_imovel_ha > 0 THEN v_area_ha / p_area_imovel_ha * 100 END,
                v_fonte_id, v_lote_id);
    END p_gravar_sobreposicao_area_protegida;

    PROCEDURE p_gravar_sobreposicao_embargo(p_imovel_publicado_id NUMBER, p_geom_imovel MDSYS.SDO_GEOMETRY, p_area_imovel_ha NUMBER) IS
        v_geoms    ${goldSchema}.t_geom_tab := ${goldSchema}.t_geom_tab();
        v_nomes    VARCHAR2(4000);
        v_orgaos   VARCHAR2(200);
        v_fonte_id NUMBER;
        v_lote_id  NUMBER;
        v_uniao    MDSYS.SDO_GEOMETRY;
        v_intersec MDSYS.SDO_GEOMETRY;
        v_area_ha  NUMBER;
    BEGIN
        SELECT LISTAGG(SUBSTR(e.nome_autuado, 1, 60), '; ') WITHIN GROUP (ORDER BY e.id),
               LISTAGG(DISTINCT o.codigo, '/') WITHIN GROUP (ORDER BY o.codigo),
               MAX(e.fonte_dado_id), MAX(e.lote_ingestao_id)
          INTO v_nomes, v_orgaos, v_fonte_id, v_lote_id
          FROM ${silverSchema}.embargo_ambiental e
          JOIN ${silverSchema}.orgao o ON o.id = e.orgao_id
         WHERE e.geometria IS NOT NULL AND SDO_RELATE(e.geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
           AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(e.geometria, c_tolerancia_calculo) = 'TRUE';

        FOR rec IN (SELECT geometria FROM ${silverSchema}.embargo_ambiental
                     WHERE geometria IS NOT NULL AND SDO_RELATE(geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
                       AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia_calculo) = 'TRUE') LOOP
            v_geoms.EXTEND;
            v_geoms(v_geoms.LAST) := rec.geometria;
        END LOOP;
        IF v_geoms.COUNT = 0 THEN
            RETURN;
        END IF;

        v_uniao := f_dissolver(v_geoms);
        v_intersec := SDO_GEOM.SDO_INTERSECTION(p_geom_imovel, v_uniao, c_tolerancia_calculo);
        IF v_intersec IS NULL THEN
            RETURN;
        END IF;
        v_area_ha := SDO_GEOM.SDO_AREA(v_intersec, c_tolerancia_calculo, 'unit=Hectare');

        INSERT INTO ${goldSchema}.sobreposicao_publicada
            (imovel_publicado_id, tipo_restricao, nome, orgao, geometria_intersecao,
             area_intersecao_ha, percentual, fonte_dado_id, lote_ingestao_id)
        VALUES (p_imovel_publicado_id, 'EMBARGO', v_nomes, v_orgaos, v_intersec,
                v_area_ha, CASE WHEN p_area_imovel_ha > 0 THEN v_area_ha / p_area_imovel_ha * 100 END,
                v_fonte_id, v_lote_id);
    END p_gravar_sobreposicao_embargo;

    PROCEDURE p_gravar_sobreposicao_desmatamento(p_imovel_publicado_id NUMBER, p_geom_imovel MDSYS.SDO_GEOMETRY, p_area_imovel_ha NUMBER) IS
        v_geoms    ${goldSchema}.t_geom_tab := ${goldSchema}.t_geom_tab();
        v_fonte_id NUMBER;
        v_lote_id  NUMBER;
        v_uniao    MDSYS.SDO_GEOMETRY;
        v_intersec MDSYS.SDO_GEOMETRY;
        v_area_ha  NUMBER;
    BEGIN
        SELECT MAX(fonte_dado_id), MAX(lote_ingestao_id) INTO v_fonte_id, v_lote_id
          FROM ${silverSchema}.desmatamento
         WHERE SDO_RELATE(geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
           AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia_calculo) = 'TRUE';

        FOR rec IN (SELECT geometria FROM ${silverSchema}.desmatamento
                     WHERE SDO_RELATE(geometria, p_geom_imovel, 'mask=ANYINTERACT') = 'TRUE'
                       AND SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(geometria, c_tolerancia_calculo) = 'TRUE') LOOP
            v_geoms.EXTEND;
            v_geoms(v_geoms.LAST) := rec.geometria;
        END LOOP;
        IF v_geoms.COUNT = 0 THEN
            RETURN;
        END IF;

        v_uniao := f_dissolver(v_geoms);
        v_intersec := SDO_GEOM.SDO_INTERSECTION(p_geom_imovel, v_uniao, c_tolerancia_calculo);
        IF v_intersec IS NULL THEN
            RETURN;
        END IF;
        v_area_ha := SDO_GEOM.SDO_AREA(v_intersec, c_tolerancia_calculo, 'unit=Hectare');

        INSERT INTO ${goldSchema}.sobreposicao_publicada
            (imovel_publicado_id, tipo_restricao, nome, orgao, geometria_intersecao,
             area_intersecao_ha, percentual, fonte_dado_id, lote_ingestao_id)
        VALUES (p_imovel_publicado_id, 'DESMATAMENTO', 'PRODES', NULL, v_intersec,
                v_area_ha, CASE WHEN p_area_imovel_ha > 0 THEN v_area_ha / p_area_imovel_ha * 100 END,
                v_fonte_id, v_lote_id);
    END p_gravar_sobreposicao_desmatamento;

    PROCEDURE publicar_imovel(p_cod_imovel IN VARCHAR2) IS
        v_geom               MDSYS.SDO_GEOMETRY;
        v_area_ha            NUMBER;
        v_municipio_ibge     NUMBER;
        v_indicador_versao_id NUMBER;
        v_imovel_publicado_id NUMBER;
        v_ja_publicado       NUMBER;
    BEGIN
        SELECT ir.geometria, ir.area_ha, m.cod_ibge
          INTO v_geom, v_area_ha, v_municipio_ibge
          FROM ${silverSchema}.imovel_rural ir
          LEFT JOIN ${silverSchema}.municipio m ON m.id = ir.municipio_id
         WHERE ir.cod_imovel = p_cod_imovel;

        SELECT MAX(id) INTO v_indicador_versao_id
          FROM ${goldSchema}.indicador_versao WHERE cod_imovel = p_cod_imovel;

        IF v_indicador_versao_id IS NULL THEN
            RETURN;
        END IF;

        SELECT COUNT(*) INTO v_ja_publicado FROM ${goldSchema}.imovel_publicado
         WHERE cod_imovel = p_cod_imovel AND indicador_versao_id = v_indicador_versao_id;
        IF v_ja_publicado > 0 THEN
            RETURN;
        END IF;

        INSERT INTO ${goldSchema}.imovel_publicado
            (cod_imovel, indicador_versao_id, municipio_cod_ibge, area_ha, geometria,
             valor_icv, valor_irl, valor_iapp, valor_isap, valor_iae, valor_idesmat, valor_ifc)
        VALUES (p_cod_imovel, v_indicador_versao_id, v_municipio_ibge, v_area_ha, v_geom,
                f_ultimo_valor(p_cod_imovel, 'ICV'), f_ultimo_valor(p_cod_imovel, 'IRL'),
                f_ultimo_valor(p_cod_imovel, 'IAPP'), f_ultimo_valor(p_cod_imovel, 'ISAP'),
                f_ultimo_valor(p_cod_imovel, 'IAE'), f_ultimo_valor(p_cod_imovel, 'IDESMAT'),
                f_ultimo_valor(p_cod_imovel, 'IFC'))
        RETURNING id INTO v_imovel_publicado_id;

        p_gravar_sobreposicao_area_protegida(v_imovel_publicado_id, v_geom, v_area_ha);
        p_gravar_sobreposicao_embargo(v_imovel_publicado_id, v_geom, v_area_ha);
        p_gravar_sobreposicao_desmatamento(v_imovel_publicado_id, v_geom, v_area_ha);
    EXCEPTION WHEN NO_DATA_FOUND THEN
        NULL;
    END publicar_imovel;

    PROCEDURE p_logar_erro(p_publicacao_id NUMBER, p_origem_silver_id NUMBER,
                           p_identificador VARCHAR2, p_motivo VARCHAR2) IS
    BEGIN
        INSERT INTO ${goldSchema}.publicacao_camada_mapa_erro
            (publicacao_camada_mapa_id, origem_silver_id, identificador, motivo)
        VALUES (p_publicacao_id, p_origem_silver_id, p_identificador, SUBSTR(p_motivo, 1, 500));
    END p_logar_erro;

    PROCEDURE publicar_camada_mapa_lote(
        p_mapa_camada_codigo  IN  VARCHAR2,
        p_versao_publicacao   IN  NUMBER,
        p_publicacao_id       IN  NUMBER,
        p_offset              IN  NUMBER,
        p_tamanho_lote        IN  NUMBER,
        p_processados         OUT NUMBER,
        p_publicados          OUT NUMBER,
        p_pulados             OUT NUMBER
    ) IS
        v_mapa_camada_id NUMBER;
    BEGIN
        p_processados := 0;
        p_publicados  := 0;
        p_pulados     := 0;

        SELECT id INTO v_mapa_camada_id
          FROM ${goldSchema}.mapa_camada WHERE codigo = p_mapa_camada_codigo AND ativo = 1;

        -- Nenhum guard de tamanho aqui de proposito (ver cabecalho da migration) -
        -- toda geometria tenta SIMPLIFY; so erro de SQL genuino pula uma linha.
        IF p_mapa_camada_codigo = 'MUNICIPIO' THEN
            FOR r IN (SELECT cod_ibge, id, nome, geometria, fonte_dado_id, lote_ingestao_id
                        FROM ${silverSchema}.municipio WHERE geometria IS NOT NULL
                       ORDER BY id OFFSET p_offset ROWS FETCH NEXT p_tamanho_lote ROWS ONLY) LOOP
                p_processados := p_processados + 1;
                BEGIN
                    INSERT INTO ${goldSchema}.feature_publicada
                        (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge,
                         geometria, versao_publicacao, fonte_dado_id, lote_ingestao_id)
                    VALUES (v_mapa_camada_id, TO_CHAR(r.cod_ibge), r.id, r.nome, r.cod_ibge,
                            SDO_UTIL.SIMPLIFY(r.geometria, c_tolerancia_mapa), p_versao_publicacao,
                            r.fonte_dado_id, r.lote_ingestao_id);
                    p_publicados := p_publicados + 1;
                EXCEPTION WHEN OTHERS THEN
                    p_pulados := p_pulados + 1;
                    p_logar_erro(p_publicacao_id, r.id, TO_CHAR(r.cod_ibge), SQLERRM);
                END;
            END LOOP;

        ELSIF p_mapa_camada_codigo = 'AREA_PROTEGIDA' THEN
            FOR r IN (SELECT codigo_origem, id, nome, geometria, fonte_dado_id, lote_ingestao_id
                        FROM ${silverSchema}.area_protegida WHERE geometria IS NOT NULL
                       ORDER BY id OFFSET p_offset ROWS FETCH NEXT p_tamanho_lote ROWS ONLY) LOOP
                p_processados := p_processados + 1;
                BEGIN
                    INSERT INTO ${goldSchema}.feature_publicada
                        (mapa_camada_id, cod_natural, origem_silver_id, rotulo, geometria,
                         versao_publicacao, fonte_dado_id, lote_ingestao_id)
                    VALUES (v_mapa_camada_id, r.codigo_origem, r.id, r.nome,
                            SDO_UTIL.SIMPLIFY(r.geometria, c_tolerancia_mapa), p_versao_publicacao,
                            r.fonte_dado_id, r.lote_ingestao_id);
                    p_publicados := p_publicados + 1;
                EXCEPTION WHEN OTHERS THEN
                    p_pulados := p_pulados + 1;
                    p_logar_erro(p_publicacao_id, r.id, r.codigo_origem, SQLERRM);
                END;
            END LOOP;

        ELSIF p_mapa_camada_codigo = 'DESMATAMENTO' THEN
            FOR r IN (SELECT identificador_origem, id, ano, geometria, fonte_dado_id, lote_ingestao_id
                        FROM ${silverSchema}.desmatamento WHERE geometria IS NOT NULL
                       ORDER BY id OFFSET p_offset ROWS FETCH NEXT p_tamanho_lote ROWS ONLY) LOOP
                p_processados := p_processados + 1;
                BEGIN
                    INSERT INTO ${goldSchema}.feature_publicada
                        (mapa_camada_id, cod_natural, origem_silver_id, rotulo, geometria,
                         versao_publicacao, fonte_dado_id, lote_ingestao_id)
                    VALUES (v_mapa_camada_id, r.identificador_origem, r.id, 'PRODES ' || TO_CHAR(r.ano),
                            SDO_UTIL.SIMPLIFY(r.geometria, c_tolerancia_mapa), p_versao_publicacao,
                            r.fonte_dado_id, r.lote_ingestao_id);
                    p_publicados := p_publicados + 1;
                EXCEPTION WHEN OTHERS THEN
                    p_pulados := p_pulados + 1;
                    p_logar_erro(p_publicacao_id, r.id, r.identificador_origem, SQLERRM);
                END;
            END LOOP;

        ELSIF p_mapa_camada_codigo = 'EMBARGO' THEN
            FOR r IN (SELECT e.numero_termo, e.id, e.nome_autuado, m.cod_ibge, e.geometria,
                             e.fonte_dado_id, e.lote_ingestao_id
                        FROM ${silverSchema}.embargo_ambiental e
                        LEFT JOIN ${silverSchema}.municipio m ON m.id = e.municipio_id
                       WHERE e.geometria IS NOT NULL
                       ORDER BY e.id OFFSET p_offset ROWS FETCH NEXT p_tamanho_lote ROWS ONLY) LOOP
                p_processados := p_processados + 1;
                BEGIN
                    INSERT INTO ${goldSchema}.feature_publicada
                        (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge,
                         geometria, versao_publicacao, fonte_dado_id, lote_ingestao_id)
                    VALUES (v_mapa_camada_id, r.numero_termo, r.id, r.nome_autuado, r.cod_ibge,
                            SDO_UTIL.SIMPLIFY(r.geometria, c_tolerancia_mapa), p_versao_publicacao,
                            r.fonte_dado_id, r.lote_ingestao_id);
                    p_publicados := p_publicados + 1;
                EXCEPTION WHEN OTHERS THEN
                    p_pulados := p_pulados + 1;
                    p_logar_erro(p_publicacao_id, r.id, r.numero_termo, SQLERRM);
                END;
            END LOOP;

        ELSIF p_mapa_camada_codigo = 'IMOVEL_RURAL' THEN
            FOR r IN (SELECT ir.cod_imovel, ir.id, m.cod_ibge, ir.geometria,
                             ir.fonte_dado_id, ir.lote_ingestao_id
                        FROM ${silverSchema}.imovel_rural ir
                        LEFT JOIN ${silverSchema}.municipio m ON m.id = ir.municipio_id
                       WHERE ir.geometria IS NOT NULL
                       ORDER BY ir.id OFFSET p_offset ROWS FETCH NEXT p_tamanho_lote ROWS ONLY) LOOP
                p_processados := p_processados + 1;
                BEGIN
                    INSERT INTO ${goldSchema}.feature_publicada
                        (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge,
                         geometria, versao_publicacao, fonte_dado_id, lote_ingestao_id)
                    VALUES (v_mapa_camada_id, r.cod_imovel, r.id, r.cod_imovel, r.cod_ibge,
                            SDO_UTIL.SIMPLIFY(r.geometria, c_tolerancia_mapa), p_versao_publicacao,
                            r.fonte_dado_id, r.lote_ingestao_id);
                    p_publicados := p_publicados + 1;
                EXCEPTION WHEN OTHERS THEN
                    p_pulados := p_pulados + 1;
                    p_logar_erro(p_publicacao_id, r.id, r.cod_imovel, SQLERRM);
                END;
            END LOOP;

        ELSIF p_mapa_camada_codigo = 'FOCO_CALOR' THEN
            IF p_offset = 0 THEN
                INSERT INTO ${goldSchema}.feature_publicada
                    (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge,
                     geometria, versao_publicacao, fonte_dado_id, lote_ingestao_id)
                SELECT v_mapa_camada_id, f.identificador_origem, f.id,
                       'Foco ' || TO_CHAR(f.data_hora, 'DD/MM/YYYY'), m.cod_ibge,
                       f.geometria, p_versao_publicacao, f.fonte_dado_id, f.lote_ingestao_id
                  FROM ${silverSchema}.foco_calor f
                  LEFT JOIN ${silverSchema}.municipio m ON m.id = f.municipio_id
                 WHERE f.geometria IS NOT NULL;
                p_processados := SQL%ROWCOUNT;
                p_publicados := p_processados;
            END IF;
        END IF;
    EXCEPTION WHEN NO_DATA_FOUND THEN
        NULL;
    END publicar_camada_mapa_lote;

END pkg_gold_publicacao;
/
