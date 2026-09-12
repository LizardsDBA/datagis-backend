-- ============================================================================
-- CORRECAO de dado de demonstracao (V021-V023). NAO adiciona dado novo, so
-- conserta fonte_dado_id errado nas linhas SEED_DEMO/[SEED]/SEEDDEMO ja gravadas.
--
-- Achado (2026-09-11), preparando o seed do Bronze (V027): V021/V022/V023 foram
-- escritas contra uma numeracao de fonte_dado que nao existe mais (referenciavam
-- id 23, 24, 26 - a semeadura real de fontes, V006/V009, so vai ate o id 11).
-- Como silver/quarentena/gold guardam fonte_dado_id como Long solto, sem FK real
-- pra controle.fonte_dado (decisao de arquitetura, CLAUDE.md 2.5/2.6 - cross-schema
-- sem FK), o INSERT nunca falhou: ficou like/pendurado num id que nao existe, ou
-- pior, num id que existe mas e de outra fonte (ex.: embargo apontando pra
-- "Focos de calor"). Sem essa correcao, o seed do Bronze (V027) ia criar lotes
-- corretos mas as linhas de Silver/Quarentena/Gold continuariam citando a fonte
-- errada - a linhagem ficaria "conectada", mas mentindo qual fonte gerou o que.
--
-- Numeracao real (controle.fonte_dado, checada ao vivo em 2026-09-11):
--   1 CAR-Imoveis(PR) | 2 ICMBio-UCs(PR) | 3 PRODES-DesmatMataAtlantica(PR) |
--   4 Focos de Calor IAT/PR | 5 Embargos IBAMA IAT/PR |
--   6 Focos de Calor INPE Brasil (CSV mensal) | 7 IBGE-MalhaMunicipal(PR) |
--   8 Terras Indigenas FUNAI IAT/PR | 9 Embargos ICMBio IAT/PR |
--   10 Autos de Infracao IBAMA IAT/PR | 11 Autos de Infracao ICMBio IAT/PR
-- ============================================================================

-- ---------- SILVER ----------
UPDATE ${silverSchema}.municipio SET fonte_dado_id = 7
 WHERE hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.area_protegida SET fonte_dado_id = 2
 WHERE hash_origem = 'SEED_DEMO' AND codigo_origem LIKE 'DEMO-UC-%';
UPDATE ${silverSchema}.area_protegida SET fonte_dado_id = 8
 WHERE hash_origem = 'SEED_DEMO' AND codigo_origem LIKE 'DEMO-TI-%';

UPDATE ${silverSchema}.auto_infracao SET fonte_dado_id = 10
 WHERE hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.embargo_ambiental SET fonte_dado_id = 5
 WHERE hash_origem = 'SEED_DEMO' AND orgao_id = (SELECT id FROM ${silverSchema}.orgao WHERE codigo = 'IBAMA');
UPDATE ${silverSchema}.embargo_ambiental SET fonte_dado_id = 9
 WHERE hash_origem = 'SEED_DEMO' AND orgao_id = (SELECT id FROM ${silverSchema}.orgao WHERE codigo = 'ICMBIO');

UPDATE ${silverSchema}.desmatamento SET fonte_dado_id = 3
 WHERE hash_origem = 'SEED_DEMO';

UPDATE ${silverSchema}.foco_calor SET fonte_dado_id = 4
 WHERE hash_origem = 'SEED_DEMO';

-- ---------- QUARENTENA ----------
-- q_imovel_rural ja usa fonte_dado_id=1 (CAR), correto - nao mexe.

UPDATE ${quarentenaSchema}.q_area_protegida SET fonte_dado_id = 2
 WHERE motivo_rejeicao LIKE '[SEED]%';

UPDATE ${quarentenaSchema}.q_embargo_ambiental SET fonte_dado_id = 5
 WHERE motivo_rejeicao LIKE '[SEED]%' AND orgao = 'IBAMA';

UPDATE ${quarentenaSchema}.q_auto_infracao SET fonte_dado_id = 10
 WHERE motivo_rejeicao LIKE '[SEED]%';

UPDATE ${quarentenaSchema}.q_foco_calor SET fonte_dado_id = 4
 WHERE motivo_rejeicao LIKE '[SEED]%' AND identificador_origem = 'DEMO-FC-9001';
UPDATE ${quarentenaSchema}.q_foco_calor SET fonte_dado_id = 6
 WHERE motivo_rejeicao LIKE '[SEED]%' AND identificador_origem = 'DEMO-FC-9002';

-- ---------- GOLD ----------
-- feature_publicada foi gravada em V023 com uma COPIA do fonte_dado_id da Silver
-- no momento do insert - corrigir a Silver (acima) nao alcanca essas copias.
UPDATE ${goldSchema}.feature_publicada SET fonte_dado_id = 7
 WHERE versao_publicacao = 0 AND mapa_camada_id = (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo = 'MUNICIPIO');

UPDATE ${goldSchema}.feature_publicada SET fonte_dado_id = 2
 WHERE versao_publicacao = 0 AND mapa_camada_id = (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo = 'AREA_PROTEGIDA')
   AND cod_natural LIKE 'DEMO-UC-%';
UPDATE ${goldSchema}.feature_publicada SET fonte_dado_id = 8
 WHERE versao_publicacao = 0 AND mapa_camada_id = (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo = 'AREA_PROTEGIDA')
   AND cod_natural LIKE 'DEMO-TI-%';

UPDATE ${goldSchema}.feature_publicada SET fonte_dado_id = 5
 WHERE versao_publicacao = 0 AND mapa_camada_id = (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo = 'EMBARGO')
   AND cod_natural IN ('DEMO-EMB-001', 'DEMO-EMB-002');
UPDATE ${goldSchema}.feature_publicada SET fonte_dado_id = 9
 WHERE versao_publicacao = 0 AND mapa_camada_id = (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo = 'EMBARGO')
   AND cod_natural = 'DEMO-EMB-003';

UPDATE ${goldSchema}.feature_publicada SET fonte_dado_id = 3
 WHERE versao_publicacao = 0 AND mapa_camada_id = (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo = 'DESMATAMENTO');

UPDATE ${goldSchema}.feature_publicada SET fonte_dado_id = 4
 WHERE versao_publicacao = 0 AND mapa_camada_id = (SELECT id FROM ${goldSchema}.mapa_camada WHERE codigo = 'FOCO_CALOR');

-- indicador_versao_fonte: linhagem generica (CAR + PRODES + Embargos IBAMA + UCs)
-- que "alimentou" cada indicador_versao demo. Tinha 1,3,4,6 - 4 e 6 nao faziam
-- sentido nesse papel (Focos de calor / Focos de calor Brasil). 1 e 3 ja
-- estavam certos (CAR, PRODES) - vira 1,3,5,2 (CAR, PRODES, Embargos IBAMA, UCs).
UPDATE ${goldSchema}.indicador_versao_fonte SET fonte_dado_id = 5
 WHERE fonte_dado_id = 4
   AND indicador_versao_id IN (SELECT id FROM ${goldSchema}.indicador_versao WHERE hash_versao LIKE 'SEEDDEMO%');
UPDATE ${goldSchema}.indicador_versao_fonte SET fonte_dado_id = 2
 WHERE fonte_dado_id = 6
   AND indicador_versao_id IN (SELECT id FROM ${goldSchema}.indicador_versao WHERE hash_versao LIKE 'SEEDDEMO%');

-- sobreposicao_publicada: cada recorte tem um fonte_dado_id hardcoded errado.
-- "nome" e unico por linha nesse seed - usado so pra identificar qual e qual.
UPDATE ${goldSchema}.sobreposicao_publicada SET fonte_dado_id = 2  WHERE nome = 'PARNA Demonstracao Curitiba';
UPDATE ${goldSchema}.sobreposicao_publicada SET fonte_dado_id = 5  WHERE nome = 'JOAO DEMO DA SILVA';
UPDATE ${goldSchema}.sobreposicao_publicada SET fonte_dado_id = 3  WHERE nome IN ('PRODES 2023', 'PRODES 2021');
UPDATE ${goldSchema}.sobreposicao_publicada SET fonte_dado_id = 5  WHERE nome = 'FAZENDA DEMO SA';
UPDATE ${goldSchema}.sobreposicao_publicada SET fonte_dado_id = 8  WHERE nome = 'TI Demonstracao Maringa';

COMMIT;
