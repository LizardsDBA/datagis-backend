-- ============================================================================
-- DADOS DE DEMONSTRACAO - BRONZE. FALSOS, mas ligados de verdade ao resto.
--
-- V021-V023 (Silver/Gold/Quarentena) sempre tiveram lote_ingestao_id=0 e
-- registro_bruto_id=0 - places-holder, sem lote nem registro bruto por tras.
-- Pedido explicito (2026-09-11): a zona Bronze tambem precisa ter dado
-- fake, e Bronze<->Silver<->Quarentena<->Gold precisam estar interligados
-- exatamente como estariam se um lote de verdade tivesse rodado - do jeito
-- que a arquitetura de linhagem (CLAUDE.md 2.5/2.6, RN016-RN019) promete.
--
-- Depende do V026 (corrige fonte_dado_id errado nas linhas SEED_DEMO/[SEED]/
-- SEEDDEMO - sem isso, este script ligaria Silver a Bronze usando a fonte
-- errada).
--
-- Estrategia:
--   1) 1 lote_ingestao "demo" por fonte_dado usada (marcador: hash_conteudo
--      comeca com 'SEED_DEMO-').
--   2) 1 bronze.registro_bruto por linha demo da Silver - payload reconstruido
--      com nomes de campo parecidos com o que a fonte real manda (WFS do CAR,
--      shapefile do ICMBio/FUNAI, CSV do INPE etc - CLAUDE.md 5.6/2.2/docs/
--      fontes-de-dados.md), geometria convertida da MDSYS.SDO_GEOMETRY ja
--      seedada pra WKT via SDO_UTIL.TO_WKTGEOMETRY (mesma geometria dos dois
--      lados, coerente com nao ter reprojecao nessa fonte - CLAUDE.md 5.6).
--   3) 1 bronze.registro_bruto por linha demo da Quarentena, reaproveitando
--      o payload_original/geometria_wkt que ela ja guardava (e literalmente
--      o que teria virado bronze.payload na hora que o registro foi rejeitado).
--   4) UPDATE em Silver/Quarentena ligando lote_ingestao_id/registro_bruto_id
--      aos ids reais criados aqui, correlacionando por chave natural (nao por
--      ordem de insercao - mais seguro).
--   5) UPDATE em Gold (feature_publicada/indicador_versao_fonte/
--      sobreposicao_publicada) ligando lote_ingestao_id pelo fonte_dado_id
--      (ja corrigido no V026).
--   6) lote_ingestao.quantidade_registros fechado por contagem real.
--
-- Limpeza, se precisar tirar tudo isso depois:
--   DELETE FROM bronze.registro_bruto WHERE lote_ingestao_id IN
--     (SELECT id FROM controle.lote_ingestao WHERE hash_conteudo LIKE 'SEED_DEMO-%');
--   DELETE FROM controle.lote_ingestao WHERE hash_conteudo LIKE 'SEED_DEMO-%';
--   (V021/V022/V023 ja documentam como limpar Silver/Quarentena/Gold)
-- ============================================================================

-- ============================================================
-- 1) LOTE_INGESTAO demo, 1 por fonte usada nos seeds
-- ============================================================
INSERT INTO ${controleSchema}.lote_ingestao (fonte_dado_id, origem_referencia, hash_conteudo, status, data_execucao)
SELECT id, url_origem, 'SEED_DEMO-' || id, 'CONCLUIDO', TIMESTAMP '2024-06-01 09:00:00'
FROM ${controleSchema}.fonte_dado
WHERE id IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

-- ============================================================
-- 2) BRONZE a partir da SILVER (payload reconstruido, geometria convertida)
-- ============================================================

-- ---------- municipio (fonte 7 - IBGE Malha Municipal) ----------
INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = m.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       m.fonte_dado_id,
       ROW_NUMBER() OVER (ORDER BY m.id),
       JSON_OBJECT('CD_MUN' VALUE TO_CHAR(m.cod_ibge), 'NM_MUN' VALUE m.nome, 'SIGLA_UF' VALUE 'PR', 'AREA_KM2' VALUE m.area_km2),
       SDO_UTIL.TO_WKTGEOMETRY(m.geometria),
       SYSTIMESTAMP
FROM ${silverSchema}.municipio m
WHERE m.hash_origem = 'SEED_DEMO';

-- ---------- imovel_rural (fonte 1 - CAR/SICAR via WFS, campos reais do 5.6) ----------
INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = ir.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       ir.fonte_dado_id,
       ROW_NUMBER() OVER (ORDER BY ir.id),
       JSON_OBJECT(
           'cod_imovel' VALUE ir.cod_imovel,
           'status_imovel' VALUE si.codigo,
           'dat_criacao' VALUE TO_CHAR(ir.data_registro, 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
           'area' VALUE ir.area_ha,
           'condicao' VALUE ci.descricao,
           'uf' VALUE 'PR',
           'municipio' VALUE mu.nome,
           'cod_municipio_ibge' VALUE mu.cod_ibge,
           'm_fiscal' VALUE ir.modulos_fiscais,
           'tipo_imovel' VALUE ti.codigo
       ),
       SDO_UTIL.TO_WKTGEOMETRY(ir.geometria),
       SYSTIMESTAMP
FROM ${silverSchema}.imovel_rural ir
JOIN ${silverSchema}.situacao_imovel si ON si.id = ir.situacao_imovel_id
JOIN ${silverSchema}.condicao_imovel ci ON ci.id = ir.condicao_imovel_id
JOIN ${silverSchema}.tipo_imovel ti ON ti.id = ir.tipo_imovel_id
LEFT JOIN ${silverSchema}.municipio mu ON mu.id = ir.municipio_id
WHERE ir.hash_origem = 'SEED_DEMO';

-- ---------- area_protegida (fonte 2 - ICMBio UC, ou fonte 8 - FUNAI TI) ----------
INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = ap.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       ap.fonte_dado_id,
       ROW_NUMBER() OVER (PARTITION BY ap.fonte_dado_id ORDER BY ap.id),
       JSON_OBJECT(
           'nome_uc' VALUE ap.nome,
           'categoria' VALUE cat.codigo,
           'ato_legal' VALUE ap.ato_legal,
           'area_ha' VALUE ap.area_ha,
           'orgao' VALUE org.codigo
       ),
       SDO_UTIL.TO_WKTGEOMETRY(ap.geometria),
       SYSTIMESTAMP
FROM ${silverSchema}.area_protegida ap
JOIN ${silverSchema}.categoria_area_protegida cat ON cat.id = ap.categoria_area_protegida_id
JOIN ${silverSchema}.orgao org ON org.id = ap.orgao_id
WHERE ap.hash_origem = 'SEED_DEMO';

-- ---------- auto_infracao (fonte 10 - Autos de Infracao IBAMA) ----------
INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = ai.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       ai.fonte_dado_id,
       ROW_NUMBER() OVER (ORDER BY ai.id),
       JSON_OBJECT(
           'numero_auto' VALUE ai.numero_auto,
           'tipo_auto_infracao' VALUE tac.codigo,
           'tipo_infracao' VALUE tic.codigo,
           'tipo_pessoa' VALUE tpc.codigo,
           'status_auto_infracao' VALUE stc.codigo,
           'cpf_cnpj_infrator' VALUE ai.cpf_cnpj_infrator,
           'nome_infrator' VALUE ai.nome_infrator,
           'data_auto' VALUE TO_CHAR(ai.data_auto, 'YYYY-MM-DD'),
           'num_processo' VALUE ai.num_processo,
           'valor_multa' VALUE ai.valor_multa,
           'cod_municipio_ibge' VALUE mu.cod_ibge
       ),
       SDO_UTIL.TO_WKTGEOMETRY(ai.geometria),
       SYSTIMESTAMP
FROM ${silverSchema}.auto_infracao ai
JOIN ${silverSchema}.tipo_auto_infracao tac ON tac.id = ai.tipo_auto_infracao_id
LEFT JOIN ${silverSchema}.tipo_infracao tic ON tic.id = ai.tipo_infracao_id
JOIN ${silverSchema}.tipo_pessoa tpc ON tpc.id = ai.tipo_pessoa_id
JOIN ${silverSchema}.status_auto_infracao stc ON stc.id = ai.status_auto_infracao_id
LEFT JOIN ${silverSchema}.municipio mu ON mu.id = ai.municipio_id
WHERE ai.hash_origem = 'SEED_DEMO';

-- ---------- embargo_ambiental (fonte 5 - IBAMA, ou fonte 9 - ICMBio) ----------
INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = e.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       e.fonte_dado_id,
       ROW_NUMBER() OVER (PARTITION BY e.fonte_dado_id ORDER BY e.id),
       JSON_OBJECT(
           'numero_termo' VALUE e.numero_termo,
           'orgao' VALUE org.codigo,
           'tipo_area_embargo' VALUE tae.codigo,
           'cpf_cnpj' VALUE e.cpf_cnpj,
           'nome_autuado' VALUE e.nome_autuado,
           'data_embargo' VALUE TO_CHAR(e.data_embargo, 'YYYY-MM-DD'),
           'num_processo' VALUE e.num_processo,
           'num_auto_infracao' VALUE e.num_auto_infracao,
           'area_embargada_ha' VALUE e.area_embargada_ha,
           'cod_municipio_ibge' VALUE mu.cod_ibge
       ),
       SDO_UTIL.TO_WKTGEOMETRY(e.geometria),
       SYSTIMESTAMP
FROM ${silverSchema}.embargo_ambiental e
JOIN ${silverSchema}.orgao org ON org.id = e.orgao_id
LEFT JOIN ${silverSchema}.tipo_area_embargo tae ON tae.id = e.tipo_area_embargo_id
LEFT JOIN ${silverSchema}.municipio mu ON mu.id = e.municipio_id
WHERE e.hash_origem = 'SEED_DEMO';

-- ---------- desmatamento (fonte 3 - PRODES Mata Atlantica) ----------
INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = d.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       d.fonte_dado_id,
       ROW_NUMBER() OVER (ORDER BY d.id),
       JSON_OBJECT(
           'identificador' VALUE d.identificador_origem,
           'ano' VALUE d.ano,
           'classe' VALUE cp.codigo,
           'satelite' VALUE s.codigo,
           'sensor' VALUE se.codigo,
           'area_ha' VALUE d.area_ha,
           'data_imagem' VALUE TO_CHAR(d.data_imagem, 'YYYY-MM-DD')
       ),
       SDO_UTIL.TO_WKTGEOMETRY(d.geometria),
       SYSTIMESTAMP
FROM ${silverSchema}.desmatamento d
LEFT JOIN ${silverSchema}.classe_prodes cp ON cp.id = d.classe_prodes_id
LEFT JOIN ${silverSchema}.satelite s ON s.id = d.satelite_id
LEFT JOIN ${silverSchema}.sensor se ON se.id = d.sensor_id
WHERE d.hash_origem = 'SEED_DEMO';

-- ---------- foco_calor (fonte 4 - Focos de Calor IAT/PR) ----------
INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = fc.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       fc.fonte_dado_id,
       ROW_NUMBER() OVER (ORDER BY fc.id),
       JSON_OBJECT(
           'id_foco' VALUE fc.identificador_origem,
           'data_hora_gmt' VALUE TO_CHAR(fc.data_hora, 'YYYY-MM-DD"T"HH24:MI:SS'),
           'satelite' VALUE sat.codigo,
           'bioma' VALUE bio.codigo,
           'municipio' VALUE mu.nome,
           'lat' VALUE fc.latitude,
           'lon' VALUE fc.longitude,
           'risco_fogo' VALUE fc.risco_fogo,
           'dias_sem_chuva' VALUE fc.dias_sem_chuva,
           'precipitacao' VALUE fc.precipitacao_mm,
           'frp' VALUE fc.frp
       ),
       SDO_UTIL.TO_WKTGEOMETRY(fc.geometria),
       SYSTIMESTAMP
FROM ${silverSchema}.foco_calor fc
LEFT JOIN ${silverSchema}.satelite sat ON sat.id = fc.satelite_id
LEFT JOIN ${silverSchema}.bioma bio ON bio.id = fc.bioma_id
LEFT JOIN ${silverSchema}.municipio mu ON mu.id = fc.municipio_id
WHERE fc.hash_origem = 'SEED_DEMO';

-- ============================================================
-- 3) BRONZE a partir da QUARENTENA (reaproveita payload_original/geometria_wkt -
--    e literalmente o que a fonte mandou antes de ser rejeitado). numero_linha
--    comeca em 901 pra nunca colidir com as linhas "boas" do mesmo lote (acima).
-- ============================================================

INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = q.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       q.fonte_dado_id,
       900 + ROW_NUMBER() OVER (PARTITION BY q.fonte_dado_id ORDER BY q.id),
       q.payload_original, q.geometria_wkt, SYSTIMESTAMP
FROM ${quarentenaSchema}.q_imovel_rural q WHERE q.motivo_rejeicao LIKE '[SEED]%';

INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = q.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       q.fonte_dado_id,
       900 + ROW_NUMBER() OVER (PARTITION BY q.fonte_dado_id ORDER BY q.id),
       q.payload_original, q.geometria_wkt, SYSTIMESTAMP
FROM ${quarentenaSchema}.q_area_protegida q WHERE q.motivo_rejeicao LIKE '[SEED]%';

INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = q.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       q.fonte_dado_id,
       900 + ROW_NUMBER() OVER (PARTITION BY q.fonte_dado_id ORDER BY q.id),
       q.payload_original, q.geometria_wkt, SYSTIMESTAMP
FROM ${quarentenaSchema}.q_embargo_ambiental q WHERE q.motivo_rejeicao LIKE '[SEED]%';

INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = q.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       q.fonte_dado_id,
       900 + ROW_NUMBER() OVER (PARTITION BY q.fonte_dado_id ORDER BY q.id),
       q.payload_original, q.geometria_wkt, SYSTIMESTAMP
FROM ${quarentenaSchema}.q_auto_infracao q WHERE q.motivo_rejeicao LIKE '[SEED]%';

INSERT INTO ${bronzeSchema}.registro_bruto (lote_ingestao_id, fonte_dado_id, numero_linha, payload, geometria_wkt, data_ingestao)
SELECT (SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = q.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'),
       q.fonte_dado_id,
       900 + ROW_NUMBER() OVER (PARTITION BY q.fonte_dado_id ORDER BY q.id),
       q.payload_original, q.geometria_wkt, SYSTIMESTAMP
FROM ${quarentenaSchema}.q_foco_calor q WHERE q.motivo_rejeicao LIKE '[SEED]%';

-- ============================================================
-- 4) SILVER <- liga lote_ingestao_id/registro_bruto_id ao Bronze recem-criado,
--    correlacionando por chave natural (nao por ordem de insercao).
-- ============================================================

UPDATE ${silverSchema}.municipio m
   SET (lote_ingestao_id, registro_bruto_id) = (
       SELECT rb.lote_ingestao_id, rb.id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = m.fonte_dado_id AND JSON_VALUE(rb.payload, '$.CD_MUN') = TO_CHAR(m.cod_ibge)
   )
 WHERE m.hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.imovel_rural ir
   SET (lote_ingestao_id, registro_bruto_id) = (
       SELECT rb.lote_ingestao_id, rb.id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = ir.fonte_dado_id AND JSON_VALUE(rb.payload, '$.cod_imovel') = ir.cod_imovel
   )
 WHERE ir.hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.area_protegida ap
   SET (lote_ingestao_id, registro_bruto_id) = (
       SELECT rb.lote_ingestao_id, rb.id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = ap.fonte_dado_id AND JSON_VALUE(rb.payload, '$.nome_uc') = ap.nome
   )
 WHERE ap.hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.auto_infracao ai
   SET (lote_ingestao_id, registro_bruto_id) = (
       SELECT rb.lote_ingestao_id, rb.id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = ai.fonte_dado_id AND JSON_VALUE(rb.payload, '$.numero_auto') = ai.numero_auto
   )
 WHERE ai.hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.embargo_ambiental e
   SET (lote_ingestao_id, registro_bruto_id) = (
       SELECT rb.lote_ingestao_id, rb.id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = e.fonte_dado_id AND JSON_VALUE(rb.payload, '$.numero_termo') = e.numero_termo
   )
 WHERE e.hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.desmatamento d
   SET (lote_ingestao_id, registro_bruto_id) = (
       SELECT rb.lote_ingestao_id, rb.id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = d.fonte_dado_id AND JSON_VALUE(rb.payload, '$.identificador') = d.identificador_origem
   )
 WHERE d.hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.foco_calor fc
   SET (lote_ingestao_id, registro_bruto_id) = (
       SELECT rb.lote_ingestao_id, rb.id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = fc.fonte_dado_id AND JSON_VALUE(rb.payload, '$.id_foco') = fc.identificador_origem
   )
 WHERE fc.hash_origem = 'SEED_DEMO';

-- ============================================================
-- 5) QUARENTENA <- mesma logica, correlacionando pela chave natural que ja
--    existia dentro do proprio payload_original.
-- ============================================================

UPDATE ${quarentenaSchema}.q_imovel_rural q
   SET (registro_bruto_id, lote_ingestao_id) = (
       SELECT rb.id, rb.lote_ingestao_id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = q.fonte_dado_id AND JSON_VALUE(rb.payload, '$.cod_imovel') = q.cod_imovel
   )
 WHERE q.motivo_rejeicao LIKE '[SEED]%';

UPDATE ${quarentenaSchema}.q_area_protegida q
   SET (registro_bruto_id, lote_ingestao_id) = (
       SELECT rb.id, rb.lote_ingestao_id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = q.fonte_dado_id AND JSON_VALUE(rb.payload, '$.cnuc') = q.codigo_origem
   )
 WHERE q.motivo_rejeicao LIKE '[SEED]%';

UPDATE ${quarentenaSchema}.q_embargo_ambiental q
   SET (registro_bruto_id, lote_ingestao_id) = (
       SELECT rb.id, rb.lote_ingestao_id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = q.fonte_dado_id AND JSON_VALUE(rb.payload, '$.numero_emb') = q.numero_termo
   )
 WHERE q.motivo_rejeicao LIKE '[SEED]%';

UPDATE ${quarentenaSchema}.q_auto_infracao q
   SET (registro_bruto_id, lote_ingestao_id) = (
       SELECT rb.id, rb.lote_ingestao_id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = q.fonte_dado_id AND JSON_VALUE(rb.payload, '$.num_auto_infracao') = q.numero_auto
   )
 WHERE q.motivo_rejeicao LIKE '[SEED]%';

UPDATE ${quarentenaSchema}.q_foco_calor q
   SET (registro_bruto_id, lote_ingestao_id) = (
       SELECT rb.id, rb.lote_ingestao_id FROM ${bronzeSchema}.registro_bruto rb
       WHERE rb.fonte_dado_id = q.fonte_dado_id AND JSON_VALUE(rb.payload, '$.id') = q.identificador_origem
   )
 WHERE q.motivo_rejeicao LIKE '[SEED]%';

-- ============================================================
-- 6) GOLD <- lote_ingestao_id pelo fonte_dado_id (ja corrigido no V026)
-- ============================================================

UPDATE ${goldSchema}.feature_publicada fp
   SET fp.lote_ingestao_id = (
       SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = fp.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'
   )
 WHERE fp.versao_publicacao = 0;

UPDATE ${goldSchema}.indicador_versao_fonte ivf
   SET ivf.lote_ingestao_id = (
       SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = ivf.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'
   )
 WHERE ivf.indicador_versao_id IN (SELECT id FROM ${goldSchema}.indicador_versao WHERE hash_versao LIKE 'SEEDDEMO%');

UPDATE ${goldSchema}.sobreposicao_publicada sp
   SET sp.lote_ingestao_id = (
       SELECT id FROM ${controleSchema}.lote_ingestao WHERE fonte_dado_id = sp.fonte_dado_id AND hash_conteudo LIKE 'SEED_DEMO-%'
   )
 WHERE sp.fonte_dado_id IN (2, 3, 5, 8);

-- ============================================================
-- 7) Fecha quantidade_registros dos lotes demo com a contagem real
-- ============================================================
UPDATE ${controleSchema}.lote_ingestao li
   SET li.quantidade_registros = (SELECT COUNT(*) FROM ${bronzeSchema}.registro_bruto rb WHERE rb.lote_ingestao_id = li.id)
 WHERE li.hash_conteudo LIKE 'SEED_DEMO-%';

COMMIT;
