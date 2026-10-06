-- Achado real: embargo_ambiental (fontes 5 e 9) referencia auto_infracao via
-- num_auto_infracao/numero_ai (integridade cruzada). Os crons de embargos e de
-- autos de infracao disparavam no MESMO instante (0 0 3 ? * MON) - o Quartz nao
-- garante ordem de execucao entre jobs diferentes com o mesmo trigger, entao o
-- embargo podia rodar e validar ANTES do auto_infracao correspondente estar na
-- Silver, derrubando o lote inteiro por REFERENCIA_NAO_ENCONTRADA (achado nesta
-- sessao: 2.263 dos 2.316 registros de embargo em quarentena eram exatamente isso).
--
-- Correcao: atrasa os embargos em 30min (3h30 em vez de 3h), dando folga de sobra
-- pros autos de infracao (fontes bem menores, nao chegam perto do volume que
-- levaria minutos pra processar) terminarem ingestao + validacao + promocao antes.
UPDATE ${controleSchema}.fonte_dado
   SET cron_expressao = '0 30 3 ? * MON'
 WHERE id IN (5, 9);
