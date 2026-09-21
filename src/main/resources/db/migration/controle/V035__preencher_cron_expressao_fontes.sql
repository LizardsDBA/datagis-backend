-- Preenche cron_expressao das fontes reais que ja existem (seed do V031), com base
-- na frequencia de atualizacao esperada de cada orgao/dataset. A fonte de upload
-- manual (CAR via Shapefile) fica sem cron de proposito: ela nao tem URL pra buscar
-- sozinha, quem dispara a ingestao dela e a pessoa subindo o arquivo.
--
-- Horarios escalonados (2h/3h/4h/5h) so pra nao empilhar tudo no mesmo minuto.

UPDATE fonte_dado SET cron_expressao = '0 0 4 1 * ?' WHERE nome = 'CAR - Imóveis (PR)';
UPDATE fonte_dado SET cron_expressao = '0 0 4 1 * ?' WHERE nome = 'ICMBio - Unidades de Conservação (PR)';
UPDATE fonte_dado SET cron_expressao = '0 0 5 1 1 ?' WHERE nome = 'INPE (PRODES) - Desmatamento Mata Atlântica (PR)';
UPDATE fonte_dado SET cron_expressao = '0 0 2 * * ?' WHERE nome = 'IAT/PR - Focos de Calor (INPE)';
UPDATE fonte_dado SET cron_expressao = '0 0 3 ? * MON' WHERE nome = 'IAT/PR - Embargos IBAMA';
UPDATE fonte_dado SET cron_expressao = '0 0 4 1 * ?' WHERE nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)';
UPDATE fonte_dado SET cron_expressao = '0 0 5 1 1 ?' WHERE nome = 'IBGE - Malha Municipal (PR)';
UPDATE fonte_dado SET cron_expressao = '0 0 5 1 1 ?' WHERE nome = 'IAT/PR - Terras Indígenas (FUNAI)';
UPDATE fonte_dado SET cron_expressao = '0 0 3 ? * MON' WHERE nome = 'IAT/PR - Embargos ICMBio';
UPDATE fonte_dado SET cron_expressao = '0 0 3 ? * MON' WHERE nome = 'IAT/PR - Autos de Infração IBAMA';
UPDATE fonte_dado SET cron_expressao = '0 0 3 ? * MON' WHERE nome = 'IAT/PR - Autos de Infração ICMBio';
-- CAR - Imóveis (PR) via Shapefile (upload): sem cron, tipoAquisicao = UPLOAD_MANUAL.