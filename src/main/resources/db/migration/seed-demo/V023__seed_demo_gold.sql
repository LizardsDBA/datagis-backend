-- ============================================================================
-- DADOS DE DEMONSTRACAO - GOLD. FALSOS. Pra o time desenvolver mapa / painel /
-- detalhe do imovel / relatorio antes da transformacao e do calculo existirem.
-- Depende do V021 (seed da Silver).
-- Marcadores de limpeza:
--   gold.regra_calculo.descricao         LIKE '[SEED]%'
--   gold.feature_publicada               versao_publicacao = 0
--   gold.indicador_versao.hash_versao    LIKE 'SEEDDEMO%'
--   (imovel_publicado / sobreposicao_publicada / indicador_resultado /
--    indicador_versao_fonte saem junto por FK das linhas acima)
-- ============================================================================

-- ---------- regra_calculo: 1 por indicador, versao 1 ----------
INSERT INTO ${goldSchema}.regra_calculo (tipo_indicador_id, versao, expressao, descricao, vigente_de)
SELECT id, 1, 'DEMO: valor = f(sobreposicao, area_imovel) -- formula real na pag. 6 do desafio',
       '[SEED] regra de demonstracao do indicador ' || codigo, DATE '2024-01-01'
FROM ${goldSchema}.tipo_indicador;

-- ---------- indicador_versao: 1 por imovel demo (6 dos 9) ----------
INSERT INTO ${goldSchema}.indicador_versao (cod_imovel, regra_calculo_id, hash_versao, publicado)
SELECT ir.cod_imovel,
       (SELECT rc.id FROM ${goldSchema}.regra_calculo rc
        JOIN ${goldSchema}.tipo_indicador ti ON ti.id = rc.tipo_indicador_id
        WHERE ti.codigo = 'ISAP' AND rc.versao = 1),
       'SEEDDEMO-' || SUBSTR(ir.cod_imovel, -8),
       1
FROM ${silverSchema}.imovel_rural ir
WHERE ir.hash_origem = 'SEED_DEMO'
  AND ir.cod_imovel IN ('PR-4106902-DEMO0001','PR-4106902-DEMO0002','PR-4115200-DEMO0001',
                        'PR-4115200-DEMO0003','PR-4119905-DEMO0001','PR-4119905-DEMO0003');

-- ---------- indicador_resultado: os 4 calculaveis por versao (ISAP/IAE/IDESMAT/IFC) ----------
INSERT INTO ${goldSchema}.indicador_resultado (indicador_versao_id, tipo_indicador_id, valor, area_afetada_ha, percentual_sobreposicao)
SELECT iv.id, ti.id, r.valor, r.area_afetada, r.pct
FROM ${goldSchema}.indicador_versao iv
CROSS JOIN (
    SELECT 'ISAP'    codigo, 0.18 valor, 6.20  area_afetada, 18.0 pct FROM dual
    UNION ALL SELECT 'IAE',     0.05, 1.80, 5.0  FROM dual
    UNION ALL SELECT 'IDESMAT', 0.11, 3.90, 11.0 FROM dual
    UNION ALL SELECT 'IFC',     0.40, NULL, NULL FROM dual
) r
JOIN ${goldSchema}.tipo_indicador ti ON ti.codigo = r.codigo
WHERE iv.hash_versao LIKE 'SEEDDEMO%';

-- ---------- indicador_versao_fonte: linhagem (fontes que "entraram" no calculo) ----------
INSERT INTO ${goldSchema}.indicador_versao_fonte (indicador_versao_id, fonte_dado_id, lote_ingestao_id)
SELECT iv.id, f.fonte, 0
FROM ${goldSchema}.indicador_versao iv
CROSS JOIN (SELECT 1 fonte FROM dual UNION ALL SELECT 3 FROM dual
            UNION ALL SELECT 4 FROM dual UNION ALL SELECT 6 FROM dual) f
WHERE iv.hash_versao LIKE 'SEEDDEMO%';

-- ---------- feature_publicada: tudo que o mapa desenha (copia das linhas demo da Silver) ----------
-- municipios
INSERT INTO ${goldSchema}.feature_publicada (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge, geometria, propriedades, versao_publicacao, fonte_dado_id, lote_ingestao_id)
SELECT (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo='MUNICIPIO'),
       TO_CHAR(m.cod_ibge), m.id, m.nome, m.cod_ibge, m.geometria,
       JSON_OBJECT('nome' VALUE m.nome, 'area_km2' VALUE m.area_km2), 0, m.fonte_dado_id, 0
FROM ${silverSchema}.municipio m WHERE m.hash_origem = 'SEED_DEMO';
-- imoveis
INSERT INTO ${goldSchema}.feature_publicada (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge, geometria, propriedades, versao_publicacao, fonte_dado_id, lote_ingestao_id)
SELECT (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo='IMOVEL_RURAL'),
       ir.cod_imovel, ir.id, ir.cod_imovel, mu.cod_ibge, ir.geometria,
       JSON_OBJECT('cod_imovel' VALUE ir.cod_imovel, 'area_ha' VALUE ir.area_ha), 0, ir.fonte_dado_id, 0
FROM ${silverSchema}.imovel_rural ir
LEFT JOIN ${silverSchema}.municipio mu ON mu.id = ir.municipio_id
WHERE ir.hash_origem = 'SEED_DEMO';
-- areas protegidas
INSERT INTO ${goldSchema}.feature_publicada (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge, geometria, propriedades, versao_publicacao, fonte_dado_id, lote_ingestao_id)
SELECT (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo='AREA_PROTEGIDA'),
       ap.codigo_origem, ap.id, ap.nome, NULL, ap.geometria,
       JSON_OBJECT('nome' VALUE ap.nome, 'area_ha' VALUE ap.area_ha), 0, ap.fonte_dado_id, 0
FROM ${silverSchema}.area_protegida ap WHERE ap.hash_origem = 'SEED_DEMO';
-- embargos (so os que tem geometria)
INSERT INTO ${goldSchema}.feature_publicada (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge, geometria, propriedades, versao_publicacao, fonte_dado_id, lote_ingestao_id)
SELECT (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo='EMBARGO'),
       e.numero_termo, e.id, e.nome_autuado, mu.cod_ibge, e.geometria,
       JSON_OBJECT('numero_termo' VALUE e.numero_termo, 'area_embargada_ha' VALUE e.area_embargada_ha), 0, e.fonte_dado_id, 0
FROM ${silverSchema}.embargo_ambiental e
LEFT JOIN ${silverSchema}.municipio mu ON mu.id = e.municipio_id
WHERE e.hash_origem = 'SEED_DEMO' AND e.geometria IS NOT NULL;
-- desmatamento
INSERT INTO ${goldSchema}.feature_publicada (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge, geometria, propriedades, versao_publicacao, fonte_dado_id, lote_ingestao_id)
SELECT (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo='DESMATAMENTO'),
       d.identificador_origem, d.id, 'PRODES ' || d.ano, NULL, d.geometria,
       JSON_OBJECT('ano' VALUE d.ano, 'area_ha' VALUE d.area_ha), 0, d.fonte_dado_id, 0
FROM ${silverSchema}.desmatamento d WHERE d.hash_origem = 'SEED_DEMO';
-- focos de calor
INSERT INTO ${goldSchema}.feature_publicada (mapa_camada_id, cod_natural, origem_silver_id, rotulo, municipio_cod_ibge, geometria, propriedades, versao_publicacao, fonte_dado_id, lote_ingestao_id)
SELECT (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo='FOCO_CALOR'),
       fc.identificador_origem, fc.id, 'Foco ' || TO_CHAR(fc.data_hora, 'YYYY-MM-DD'), mu.cod_ibge, fc.geometria,
       JSON_OBJECT('data_hora' VALUE TO_CHAR(fc.data_hora,'YYYY-MM-DD"T"HH24:MI:SS'), 'risco_fogo' VALUE fc.risco_fogo, 'frp' VALUE fc.frp), 0, fc.fonte_dado_id, 0
FROM ${silverSchema}.foco_calor fc
LEFT JOIN ${silverSchema}.municipio mu ON mu.id = fc.municipio_id
WHERE fc.hash_origem = 'SEED_DEMO';

-- ---------- imovel_publicado: boletim por imovel (6, casando com indicador_versao) ----------
INSERT INTO ${goldSchema}.imovel_publicado (cod_imovel, indicador_versao_id, municipio_cod_ibge, area_ha, geometria, valor_icv, valor_irl, valor_iapp, valor_isap, valor_iae, valor_idesmat, valor_ifc)
SELECT iv.cod_imovel, iv.id, mu.cod_ibge, ir.area_ha, ir.geometria,
       NULL, NULL, NULL,   -- ICV/IRL/IAPP sem fonte
       0.18, 0.05, 0.11, 0.40
FROM ${goldSchema}.indicador_versao iv
JOIN ${silverSchema}.imovel_rural ir ON ir.cod_imovel = iv.cod_imovel
LEFT JOIN ${silverSchema}.municipio mu ON mu.id = ir.municipio_id
WHERE iv.hash_versao LIKE 'SEEDDEMO%';

-- ---------- sobreposicao_publicada: recortes que produziram os indicadores ----------
INSERT INTO ${goldSchema}.sobreposicao_publicada (imovel_publicado_id, tipo_restricao, nome, orgao, geometria_intersecao, area_intersecao_ha, percentual, fonte_dado_id, lote_ingestao_id)
SELECT ip.id, 'AREA_PROTEGIDA', 'PARNA Demonstracao Curitiba', 'ICMBIO',
       SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.33,-25.52, -49.32,-25.52, -49.32,-25.51, -49.33,-25.51, -49.33,-25.52)),
       2.20, 18.0, 3, 0
FROM ${goldSchema}.imovel_publicado ip WHERE ip.cod_imovel = 'PR-4106902-DEMO0001'
UNION ALL
SELECT ip.id, 'EMBARGO', 'JOAO DEMO DA SILVA', 'IBAMA',
       SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.335,-25.525, -49.325,-25.525, -49.325,-25.515, -49.335,-25.515, -49.335,-25.525)),
       0.80, 5.0, 6, 0
FROM ${goldSchema}.imovel_publicado ip WHERE ip.cod_imovel = 'PR-4106902-DEMO0001'
UNION ALL
SELECT ip.id, 'DESMATAMENTO', 'PRODES 2023', 'INPE',
       SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.305,-25.475, -49.295,-25.475, -49.295,-25.465, -49.305,-25.465, -49.305,-25.475)),
       3.90, 11.0, 4, 0
FROM ${goldSchema}.imovel_publicado ip WHERE ip.cod_imovel = 'PR-4106902-DEMO0002'
UNION ALL
SELECT ip.id, 'DESMATAMENTO', 'PRODES 2021', 'INPE',
       SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.085,-25.045, -50.055,-25.045, -50.055,-25.015, -50.085,-25.015, -50.085,-25.045)),
       5.34, 6.1, 4, 0
FROM ${goldSchema}.imovel_publicado ip WHERE ip.cod_imovel = 'PR-4119905-DEMO0003'
UNION ALL
SELECT ip.id, 'EMBARGO', 'FAZENDA DEMO SA', 'IBAMA',
       SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.09,-25.04, -50.05,-25.04, -50.05,-25.00, -50.09,-25.00, -50.09,-25.04)),
       12.70, 6.2, 6, 0
FROM ${goldSchema}.imovel_publicado ip WHERE ip.cod_imovel = 'PR-4119905-DEMO0003'
UNION ALL
SELECT ip.id, 'AREA_PROTEGIDA', 'TI Demonstracao Maringa', 'FUNAI',
       SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-52.02,-23.46, -51.99,-23.46, -51.99,-23.43, -52.02,-23.43, -52.02,-23.46)),
       9.10, 22.5, 24, 0
FROM ${goldSchema}.imovel_publicado ip WHERE ip.cod_imovel = 'PR-4115200-DEMO0001';

COMMIT;
