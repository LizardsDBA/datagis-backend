-- ============================================================================
-- DADOS DE DEMONSTRACAO - SILVER. FALSOS. Nao vieram de transformacao nenhuma.
-- Existe pra o time desenvolver mapa / painel / telas que leem a Silver antes da
-- transformacao Bronze->Silver existir.
-- Toda linha tem hash_origem = 'SEED_DEMO' -> limpeza: DELETE ... WHERE hash_origem='SEED_DEMO'.
-- lote_ingestao_id / registro_bruto_id = 0 (nao ha lote real por tras).
-- Geometrias sao retangulos pequenos perto de coordenadas reais do PR - servem pro
-- Leaflet desenhar algo, nao sao os limites de verdade.
-- ============================================================================

-- ---------- municipio (circuito: Curitiba, Maringa, Ponta Grossa) ----------
INSERT INTO ${silverSchema}.municipio (cod_ibge, nome, unidade_federativa_id, bioma_predominante_id, area_km2, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
VALUES (4106902, 'Curitiba',
        (SELECT id FROM ${silverSchema}.unidade_federativa WHERE codigo='PR'),
        (SELECT id FROM ${silverSchema}.bioma WHERE codigo='MATA_ATLANTICA'),
        435.0,
        SDO_GEOMETRY(2003, 4674, NULL, SDO_ELEM_INFO_ARRAY(1,1003,1), SDO_ORDINATE_ARRAY(-49.40,-25.60, -49.15,-25.60, -49.15,-25.35, -49.40,-25.35, -49.40,-25.60)),
        23, 0, 0, DATE '2023-01-01', 'SEED_DEMO');
INSERT INTO ${silverSchema}.municipio (cod_ibge, nome, unidade_federativa_id, bioma_predominante_id, area_km2, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
VALUES (4115200, 'Maringa',
        (SELECT id FROM ${silverSchema}.unidade_federativa WHERE codigo='PR'),
        (SELECT id FROM ${silverSchema}.bioma WHERE codigo='MATA_ATLANTICA'),
        487.0,
        SDO_GEOMETRY(2003, 4674, NULL, SDO_ELEM_INFO_ARRAY(1,1003,1), SDO_ORDINATE_ARRAY(-52.05,-23.50, -51.85,-23.50, -51.85,-23.30, -52.05,-23.30, -52.05,-23.50)),
        23, 0, 0, DATE '2023-01-01', 'SEED_DEMO');
INSERT INTO ${silverSchema}.municipio (cod_ibge, nome, unidade_federativa_id, bioma_predominante_id, area_km2, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
VALUES (4119905, 'Ponta Grossa',
        (SELECT id FROM ${silverSchema}.unidade_federativa WHERE codigo='PR'),
        (SELECT id FROM ${silverSchema}.bioma WHERE codigo='MATA_ATLANTICA'),
        2054.0,
        SDO_GEOMETRY(2003, 4674, NULL, SDO_ELEM_INFO_ARRAY(1,1003,1), SDO_ORDINATE_ARRAY(-50.35,-25.25, -49.95,-25.25, -49.95,-24.95, -50.35,-24.95, -50.35,-25.25)),
        23, 0, 0, DATE '2023-01-01', 'SEED_DEMO');

-- ---------- imovel_rural (3 por municipio) ----------
INSERT INTO ${silverSchema}.imovel_rural (cod_imovel, situacao_imovel_id, condicao_imovel_id, tipo_imovel_id, municipio_id, data_registro, area_ha, modulos_fiscais, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
SELECT x.cod, s.id, c.id, t.id, m.id, x.dt, x.area, x.mf, x.geom, 1, 0, 0, DATE '2024-06-01', 'SEED_DEMO'
FROM (
    SELECT 'PR-4106902-DEMO0001' cod, 4106902 mun, DATE '2016-03-10' dt, 12.5 area, 2.5 mf, 'AT' sit, 'ANALISADO_SEM_PENDENCIAS' con, 'IRU' tip,
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.34,-25.52, -49.32,-25.52, -49.32,-25.50, -49.34,-25.50, -49.34,-25.52)) geom FROM dual
    UNION ALL SELECT 'PR-4106902-DEMO0002', 4106902, DATE '2017-08-22', 48.0, 9.6, 'AT', 'ANALISADO_COM_PENDENCIAS', 'IRU',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.30,-25.48, -49.26,-25.48, -49.26,-25.44, -49.30,-25.44, -49.30,-25.48)) FROM dual
    UNION ALL SELECT 'PR-4106902-DEMO0003', 4106902, DATE '2019-01-15', 7.2, 1.4, 'PE', 'AGUARDANDO_ANALISE', 'IRU',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.22,-25.40, -49.20,-25.40, -49.20,-25.38, -49.22,-25.38, -49.22,-25.40)) FROM dual
    UNION ALL SELECT 'PR-4115200-DEMO0001', 4115200, DATE '2015-11-05', 130.0, 26.0, 'AT', 'ANALISADO_SEM_PENDENCIAS', 'IRU',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-52.02,-23.46, -51.96,-23.46, -51.96,-23.40, -52.02,-23.40, -52.02,-23.46)) FROM dual
    UNION ALL SELECT 'PR-4115200-DEMO0002', 4115200, DATE '2018-04-30', 22.5, 4.5, 'AT', 'EM_ANALISE', 'IRU',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-51.94,-23.38, -51.90,-23.38, -51.90,-23.34, -51.94,-23.34, -51.94,-23.38)) FROM dual
    UNION ALL SELECT 'PR-4115200-DEMO0003', 4115200, DATE '2020-09-12', 310.0, 62.0, 'SU', 'ANALISADO_COM_PENDENCIAS', 'IRU',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-51.99,-23.35, -51.93,-23.35, -51.93,-23.31, -51.99,-23.31, -51.99,-23.35)) FROM dual
    UNION ALL SELECT 'PR-4119905-DEMO0001', 4119905, DATE '2016-07-19', 88.0, 17.6, 'AT', 'ANALISADO_SEM_PENDENCIAS', 'IRU',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.30,-25.20, -50.24,-25.20, -50.24,-25.14, -50.30,-25.14, -50.30,-25.20)) FROM dual
    UNION ALL SELECT 'PR-4119905-DEMO0002', 4119905, DATE '2017-02-08', 15.0, 3.0, 'AT', 'AGUARDANDO_ANALISE', 'IRU',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.18,-25.10, -50.14,-25.10, -50.14,-25.06, -50.18,-25.06, -50.18,-25.10)) FROM dual
    UNION ALL SELECT 'PR-4119905-DEMO0003', 4119905, DATE '2021-05-25', 205.0, 41.0, 'AT', 'ANALISADO_COM_PENDENCIAS', 'IRU',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.10,-25.05, -50.02,-25.05, -50.02,-24.99, -50.10,-24.99, -50.10,-25.05)) FROM dual
) x
JOIN ${silverSchema}.situacao_imovel  s ON s.codigo = x.sit
JOIN ${silverSchema}.condicao_imovel  c ON c.codigo = x.con
JOIN ${silverSchema}.tipo_imovel      t ON t.codigo = x.tip
JOIN ${silverSchema}.municipio        m ON m.cod_ibge = x.mun;

-- ---------- area_protegida (1 UC PI, 1 UC US, 1 TI) - sobrepoem alguns imoveis ----------
INSERT INTO ${silverSchema}.area_protegida (codigo_origem, nome, tipo_area_protegida_id, categoria_area_protegida_id, orgao_id, bioma_id, ato_legal, area_ha, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
VALUES ('DEMO-UC-001', 'PARNA Demonstracao Curitiba',
        (SELECT id FROM ${silverSchema}.tipo_area_protegida WHERE codigo='UC_PROTECAO_INTEGRAL'),
        (SELECT id FROM ${silverSchema}.categoria_area_protegida WHERE codigo='PARNA'),
        (SELECT id FROM ${silverSchema}.orgao WHERE codigo='ICMBIO'),
        (SELECT id FROM ${silverSchema}.bioma WHERE codigo='MATA_ATLANTICA'),
        'DEC DEMO 1.000 de 01/01/2000', 3200.0,
        SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.33,-25.53, -49.27,-25.53, -49.27,-25.47, -49.33,-25.47, -49.33,-25.53)),
        3, 0, 0, DATE '2023-01-01', 'SEED_DEMO');
INSERT INTO ${silverSchema}.area_protegida (codigo_origem, nome, tipo_area_protegida_id, categoria_area_protegida_id, orgao_id, bioma_id, ato_legal, area_ha, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
VALUES ('DEMO-UC-002', 'APA Demonstracao Ponta Grossa',
        (SELECT id FROM ${silverSchema}.tipo_area_protegida WHERE codigo='UC_USO_SUSTENTAVEL'),
        (SELECT id FROM ${silverSchema}.categoria_area_protegida WHERE codigo='APA'),
        (SELECT id FROM ${silverSchema}.orgao WHERE codigo='ICMBIO'),
        (SELECT id FROM ${silverSchema}.bioma WHERE codigo='MATA_ATLANTICA'),
        'DEC DEMO 2.000 de 01/01/2005', 15000.0,
        SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.32,-25.22, -50.20,-25.22, -50.20,-25.10, -50.32,-25.10, -50.32,-25.22)),
        3, 0, 0, DATE '2023-01-01', 'SEED_DEMO');
INSERT INTO ${silverSchema}.area_protegida (codigo_origem, nome, tipo_area_protegida_id, categoria_area_protegida_id, orgao_id, bioma_id, ato_legal, area_ha, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
VALUES ('DEMO-TI-001', 'TI Demonstracao Maringa',
        (SELECT id FROM ${silverSchema}.tipo_area_protegida WHERE codigo='TERRA_INDIGENA'),
        (SELECT id FROM ${silverSchema}.categoria_area_protegida WHERE codigo='TI_TRADICIONALMENTE_OCUPADA'),
        (SELECT id FROM ${silverSchema}.orgao WHERE codigo='FUNAI'),
        (SELECT id FROM ${silverSchema}.bioma WHERE codigo='MATA_ATLANTICA'),
        'Portaria DEMO 100 de 01/01/2010', 4200.0,
        SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-52.03,-23.47, -51.97,-23.47, -51.97,-23.41, -52.03,-23.41, -52.03,-23.47)),
        24, 0, 0, DATE '2023-01-01', 'SEED_DEMO');

INSERT INTO ${silverSchema}.area_protegida_uf (area_protegida_id, unidade_federativa_id)
SELECT ap.id, (SELECT id FROM ${silverSchema}.unidade_federativa WHERE codigo='PR')
FROM ${silverSchema}.area_protegida ap WHERE ap.codigo_origem LIKE 'DEMO-%';

-- ---------- auto_infracao ----------
INSERT INTO ${silverSchema}.auto_infracao (numero_auto, orgao_id, tipo_auto_infracao_id, tipo_infracao_id, tipo_pessoa_id, status_auto_infracao_id, municipio_id, cpf_cnpj_infrator, nome_infrator, data_auto, num_processo, valor_multa, houve_embargo, houve_apreensao, descricao, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
SELECT x.na, o.id, ta.id, ti.id, tp.id, st.id, m.id, x.doc, x.nm, x.dt, x.proc, x.val, x.emb, x.apr, x.descr, x.geom, 26, 0, 0, x.dt, 'SEED_DEMO'
FROM (
    SELECT 'DEMO-AI-001' na, 'IBAMA' org, 'MULTA_SIMPLES' tac, 'CONTRA_FLORA' tic, 'PF' tpc, 'LAVRADO' stc, 4106902 mun, '111.222.333-44' doc, 'JOAO DEMO DA SILVA' nm, DATE '2022-03-15' dt, '02017.000001/2022-01' proc, 5000 val, 1 emb, 0 apr, 'Supressao de vegetacao sem autorizacao (demo).' descr,
           CAST(NULL AS SDO_GEOMETRY) geom FROM dual
    UNION ALL SELECT 'DEMO-AI-002', 'IBAMA', 'MULTA_DIARIA', 'POLUICAO', 'PJ', 'HOMOLOGADO', 4115200, '11.222.333/0001-44', 'AGRO DEMO LTDA', DATE '2021-09-01', '02017.000002/2021-02', 25000, 0, 1, 'Lancamento de efluente sem tratamento (demo).',
           SDO_GEOMETRY(2001,4674,SDO_POINT_TYPE(-51.95,-23.40,NULL),NULL,NULL) FROM dual
    UNION ALL SELECT 'DEMO-AI-003', 'IBAMA', 'ADVERTENCIA', 'CONTRA_ADMIN_AMBIENTAL', 'PF', 'JULGADO', 4119905, '222.333.444-55', 'MARIA DEMO SOUZA', DATE '2020-01-20', '02017.000003/2020-03', 0, 0, 0, 'Nao apresentacao de documentacao ambiental (demo).',
           CAST(NULL AS SDO_GEOMETRY) FROM dual
    UNION ALL SELECT 'DEMO-AI-004', 'IBAMA', 'EMBARGO', 'CONTRA_FLORA', 'PJ', 'LAVRADO', 4119905, '22.333.444/0001-55', 'FAZENDA DEMO SA', DATE '2023-06-10', '02017.000004/2023-04', 80000, 1, 0, 'Desmatamento em area de preservacao (demo).',
           SDO_GEOMETRY(2001,4674,SDO_POINT_TYPE(-50.06,-25.02,NULL),NULL,NULL) FROM dual
) x
JOIN ${silverSchema}.orgao                o  ON o.codigo  = x.org
JOIN ${silverSchema}.tipo_auto_infracao   ta ON ta.codigo = x.tac
JOIN ${silverSchema}.tipo_infracao        ti ON ti.codigo = x.tic
JOIN ${silverSchema}.tipo_pessoa          tp ON tp.codigo = x.tpc
JOIN ${silverSchema}.status_auto_infracao st ON st.codigo = x.stc
JOIN ${silverSchema}.municipio            m  ON m.cod_ibge = x.mun;

-- ---------- embargo_ambiental (2 ligados a auto) ----------
INSERT INTO ${silverSchema}.embargo_ambiental (numero_termo, orgao_id, auto_infracao_id, tipo_area_embargo_id, municipio_id, cpf_cnpj, nome_autuado, data_embargo, num_processo, num_auto_infracao, area_embargada_ha, descricao, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
SELECT x.nt, o.id, ai.id, tae.id, m.id, x.doc, x.nm, x.dt, x.proc, x.nai, x.area, x.descr, x.geom, 6, 0, 0, x.dt, 'SEED_DEMO'
FROM (
    SELECT 'DEMO-EMB-001' nt, 'IBAMA' org, 'DEMO-AI-001' aik, 'DESMATAMENTO' taec, 4106902 mun, '111.222.333-44' doc, 'JOAO DEMO DA SILVA' nm, DATE '2022-03-20' dt, '02017.000001/2022-01' proc, '3938' nai, 4.2 area, 'Fica embargada a area do poligono descrito (demo).' descr,
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.335,-25.525, -49.325,-25.525, -49.325,-25.515, -49.335,-25.515, -49.335,-25.525)) geom FROM dual
    UNION ALL SELECT 'DEMO-EMB-002', 'IBAMA', 'DEMO-AI-004', 'DESMATAMENTO', 4119905, '22.333.444/0001-55', 'FAZENDA DEMO SA', DATE '2023-06-15', '02017.000004/2023-04', NULL, 12.7, 'Embargo por corte raso (demo).',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.09,-25.04, -50.05,-25.04, -50.05,-25.00, -50.09,-25.00, -50.09,-25.04)) FROM dual
    UNION ALL SELECT 'DEMO-EMB-003', 'ICMBIO', NULL, 'EXPLORACAO_IRREGULAR', 4119905, '333.444.555-66', 'PEDRO DEMO LIMA', DATE '2021-11-02', '02127.000010/2021-05', NULL, 2.0, 'Embargo em entorno de UC (demo).',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.28,-25.18, -50.26,-25.18, -50.26,-25.16, -50.28,-25.16, -50.28,-25.18)) FROM dual
) x
JOIN ${silverSchema}.orgao             o   ON o.codigo = x.org
LEFT JOIN ${silverSchema}.auto_infracao ai ON ai.numero_auto = x.aik
JOIN ${silverSchema}.tipo_area_embargo tae ON tae.codigo = x.taec
JOIN ${silverSchema}.municipio         m   ON m.cod_ibge = x.mun;

-- ---------- desmatamento ----------
INSERT INTO ${silverSchema}.desmatamento (identificador_origem, ano, classe_prodes_id, satelite_id, sensor_id, area_ha, data_imagem, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
SELECT x.ident, x.ano, cp.id, s.id, se.id, x.area, x.dt, x.geom, 4, 0, 0, x.dt, 'SEED_DEMO'
FROM (
    SELECT 'DEMO-DES-001' ident, 2021 ano, 'DESMATAMENTO' cpc, 'LANDSAT8' satc, 'OLI' senc, 5.34 area, DATE '2021-07-24' dt,
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-50.085,-25.045, -50.055,-25.045, -50.055,-25.015, -50.085,-25.015, -50.085,-25.045)) geom FROM dual
    UNION ALL SELECT 'DEMO-DES-002', 2022, 'DESMATAMENTO', 'LANDSAT8', 'OLI', 1.10, DATE '2022-08-11',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-51.995,-23.355, -51.975,-23.355, -51.975,-23.335, -51.995,-23.335, -51.995,-23.355)) FROM dual
    UNION ALL SELECT 'DEMO-DES-003', 2023, 'DESMATAMENTO', 'LANDSAT8', 'OLI', 8.90, DATE '2023-06-30',
           SDO_GEOMETRY(2003,4674,NULL,SDO_ELEM_INFO_ARRAY(1,1003,1),SDO_ORDINATE_ARRAY(-49.305,-25.475, -49.285,-25.475, -49.285,-25.455, -49.305,-25.455, -49.305,-25.475)) FROM dual
) x
JOIN ${silverSchema}.classe_prodes cp ON cp.codigo = x.cpc
JOIN ${silverSchema}.satelite      s  ON s.codigo  = x.satc
JOIN ${silverSchema}.sensor        se ON se.codigo = x.senc;

-- ---------- foco_calor (12 pontos espalhados) ----------
INSERT INTO ${silverSchema}.foco_calor (identificador_origem, data_hora, satelite_id, bioma_id, municipio_id, latitude, longitude, risco_fogo, dias_sem_chuva, precipitacao_mm, frp, geometria, fonte_dado_id, lote_ingestao_id, registro_bruto_id, data_competencia, hash_origem)
SELECT 'DEMO-FC-' || LPAD(LEVEL,3,'0'),
       TIMESTAMP '2024-01-01 12:00:00' + NUMTODSINTERVAL(LEVEL*7, 'HOUR'),
       (SELECT id FROM ${silverSchema}.satelite WHERE codigo='AQUA_M-T'),
       (SELECT id FROM ${silverSchema}.bioma WHERE codigo='MATA_ATLANTICA'),
       (SELECT id FROM ${silverSchema}.municipio WHERE cod_ibge = CASE MOD(LEVEL,3) WHEN 0 THEN 4106902 WHEN 1 THEN 4115200 ELSE 4119905 END),
       -25.10 - (LEVEL*0.02),
       -50.10 - (LEVEL*0.03),
       ROUND(0.3 + MOD(LEVEL,7)/10, 2),
       MOD(LEVEL,15),
       ROUND(MOD(LEVEL,5) * 1.7, 1),
       ROUND(10 + MOD(LEVEL,40) * 2.5, 1),
       SDO_GEOMETRY(2001, 4674, SDO_POINT_TYPE(-50.10 - (LEVEL*0.03), -25.10 - (LEVEL*0.02), NULL), NULL, NULL),
       5, 0, 0, DATE '2024-01-15', 'SEED_DEMO'
FROM dual CONNECT BY LEVEL <= 12;

COMMIT;
