-- ============================================================================
-- DADOS DE DEMONSTRACAO - QUARENTENA. FALSOS. Pra o time desenvolver a tela de
-- analise de quarentena antes da transformacao Bronze->Silver existir.
-- Marcador: motivo_rejeicao comeca com '[SEED]' -> limpeza:
--   DELETE FROM quarentena.q_* WHERE motivo_rejeicao LIKE '[SEED]%'
-- ============================================================================

-- ---------- q_imovel_rural ----------
INSERT INTO ${quarentenaSchema}.q_imovel_rural (cod_imovel, situacao_imovel, condicao_imovel, tipo_imovel, cod_municipio, data_registro, area, modulos_fiscais, geometria_wkt, registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, campo_problema, valor_recebido, motivo_rejeicao, status_analise_id)
SELECT 'PR-4106902-DEMO9001', 'AT', 'Aguardando analise', 'IRU', '4106902', '2016-01-27', 'oito virgula oito', '1.7771', NULL, 0, 0, 1,
       JSON('{"cod_imovel":"PR-4106902-DEMO9001","area":"oito virgula oito"}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='TIPO_DE_DADO_INVALIDO'),
       'area', 'oito virgula oito', '[SEED] area nao converte pra numero',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='PENDENTE') FROM dual
UNION ALL
SELECT 'PR-4199999-DEMO9002', 'AT', 'Analisado sem pendencias', 'IRU', '4199999', '2018-05-10', '25.0', '5.0', 'MULTIPOLYGON (((-49.3 -25.5, -49.29 -25.5, -49.29 -25.49, -49.3 -25.49, -49.3 -25.5)))', 0, 0, 1,
       JSON('{"cod_imovel":"PR-4199999-DEMO9002","cod_municipio_ibge":4199999}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='REFERENCIA_NAO_ENCONTRADA'),
       'cod_municipio_ibge', '4199999', '[SEED] municipio 4199999 nao existe em silver.municipio',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='PENDENTE') FROM dual
UNION ALL
SELECT 'PR-4115200-DEMO9003', 'AT', NULL, 'IRU', '4115200', '2019-02-01', NULL, '3.0', 'MULTIPOLYGON (((-51.95 -23.4, -51.94 -23.4, -51.94 -23.39, -51.95 -23.39, -51.95 -23.4)))', 0, 0, 1,
       JSON('{"cod_imovel":"PR-4115200-DEMO9003","area_lote_anterior":22.5,"area":null}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='REGRESSAO_DE_VALOR'),
       'area', NULL, '[SEED] area estava 22.5 no lote anterior e veio nula no lote atual',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='EM_ANALISE') FROM dual;

-- ---------- q_area_protegida ----------
INSERT INTO ${quarentenaSchema}.q_area_protegida (codigo_origem, nome, tipo_area_protegida, categoria_area_protegida, orgao, bioma, uf, ato_legal, area, geometria_wkt, registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, campo_problema, valor_recebido, motivo_rejeicao, status_analise_id)
SELECT 'DEMO-UC-9001', 'UC Demo Sem Geometria', 'UC_PROTECAO_INTEGRAL', 'PARNA', 'ICMBIO', 'Mata Atlantica', 'PR', 'DEC DEMO', '3200', NULL, 0, 0, 3,
       JSON('{"cnuc":"DEMO-UC-9001","geometry":null}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='GEOMETRIA_NULA'),
       'geometria', NULL, '[SEED] area protegida sem geometria - obrigatoria pra ISAP',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='PENDENTE') FROM dual
UNION ALL
SELECT 'DEMO-UC-9002', 'UC Demo Categoria Invalida', 'UC_USO_SUSTENTAVEL', 'XPTO', 'ICMBIO', 'Mata Atlantica', 'PR', 'DEC DEMO', '900', 'MULTIPOLYGON (((-50.3 -25.2, -50.2 -25.2, -50.2 -25.1, -50.3 -25.1, -50.3 -25.2)))', 0, 0, 3,
       JSON('{"cnuc":"DEMO-UC-9002","categoria":"XPTO"}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='VALOR_FORA_DO_DOMINIO'),
       'categoria_area_protegida', 'XPTO', '[SEED] categoria XPTO nao existe em silver.categoria_area_protegida',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='PENDENTE') FROM dual;

-- ---------- q_embargo_ambiental ----------
INSERT INTO ${quarentenaSchema}.q_embargo_ambiental (numero_termo, orgao, tipo_area_embargo, cod_municipio, cpf_cnpj, nome_autuado, data_embargo, num_processo, num_auto_infracao, area_embargada, descricao, geometria_wkt, registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, campo_problema, valor_recebido, motivo_rejeicao, status_analise_id)
SELECT 'DEMO-EMB-9001', 'IBAMA', 'Desmatamento', '4106902', '111.222.333-44', 'JOAO DEMO', '1520899200000', '02017.000009/2022-09', '9999', '4.2', 'Embargo demo com data em epoch', 'MULTIPOLYGON (((-49.33 -25.52, -49.32 -25.52, -49.32 -25.51, -49.33 -25.51, -49.33 -25.52)))', 0, 0, 6,
       JSON('{"numero_emb":"DEMO-EMB-9001","data":1520899200000}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='TIPO_DE_DADO_INVALIDO'),
       'data_embargo', '1520899200000', '[SEED] data em epoch millis, esperado DATE - falta regra epoch_millis no mapeamento',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='CORRIGIDO_REPROCESSADO') FROM dual;

-- ---------- q_auto_infracao ----------
INSERT INTO ${quarentenaSchema}.q_auto_infracao (numero_auto, orgao, tipo_auto_infracao, tipo_infracao, tipo_pessoa, status_auto_infracao, cod_municipio, cpf_cnpj_infrator, nome_infrator, data_auto, num_processo, valor_multa, houve_embargo, houve_apreensao, descricao, geometria_wkt, registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, campo_problema, valor_recebido, motivo_rejeicao, status_analise_id)
SELECT 'DEMO-AI-9001', 'IBAMA', 'Multa simples', NULL, 'PF', 'Lavrado', '4115200', NULL, 'ANONIMO DEMO', '1983-12-30', '02017.000099/1984-99', '250000', 'N', 'N', 'Auto demo sem CPF do infrator', NULL, 0, 0, 26,
       JSON('{"num_auto_infracao":"DEMO-AI-9001","cpf_cnpj_infrator":null}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='CAMPO_OBRIGATORIO_AUSENTE'),
       'cpf_cnpj_infrator', NULL, '[SEED] auto de infracao sem identificacao do infrator',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='DESCARTADO') FROM dual;

-- ---------- q_foco_calor ----------
INSERT INTO ${quarentenaSchema}.q_foco_calor (identificador_origem, data_hora, satelite, bioma, cod_municipio, latitude, longitude, risco_fogo, dias_sem_chuva, precipitacao, frp, geometria_wkt, registro_bruto_id, lote_ingestao_id, fonte_dado_id, payload_original, tipo_erro_id, campo_problema, valor_recebido, motivo_rejeicao, status_analise_id)
SELECT 'DEMO-FC-9001', '2024/03/11 17:19:00', 'AQUA_M-T', 'Mata Atlantica', 'LAPA', '-25.723', '-49.923', '0.2', '2', '0', ' ', NULL, 0, 0, 5,
       JSON('{"id":"DEMO-FC-9001","municipio":"LAPA"}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='REFERENCIA_NAO_ENCONTRADA'),
       'municipio', 'LAPA', '[SEED] fonte traz so o nome do municipio; join espacial ainda nao resolveu',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='PENDENTE') FROM dual
UNION ALL
SELECT 'DEMO-FC-9002', '2024-04-01 00:06:16', 'GOES-16', 'Caatinga', 'LENCOIS', '-12.5662', '-41.4611', '0.83', '9', '1.73', '81.4', NULL, 0, 0, 7,
       JSON('{"id":"DEMO-FC-9002","estado":"BAHIA"}'),
       (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo='FORA_DO_RECORTE_TERRITORIAL'),
       'estado', 'BAHIA', '[SEED] foco fora do Parana (CSV nacional do INPE traz o Brasil todo)',
       (SELECT id FROM ${quarentenaSchema}.status_analise_quarentena WHERE codigo='DESCARTADO') FROM dual;

COMMIT;
