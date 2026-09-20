-- Ate aqui o parser de CSV (IngestaoCsvExecutor) so entendia virgula como separador de
-- campo, hard-coded. Fontes reais de teste vieram com ";" e "|" tambem (INPE Queimadas
-- tem variacoes assim entre provedores) - sem isso, cada linha virava uma unica coluna
-- gigante em vez de ser dividida certo, silenciosamente (sem erro nenhum).
--
-- DEFAULT ',' NOT NULL preenche as fontes CSV ja cadastradas com o unico separador que
-- sempre foi suportado ate agora, sem quebrar ingestao que ja funcionava.
ALTER TABLE ${controleSchema}.fonte_dado ADD (
    separador_csv VARCHAR2(5) DEFAULT ',' NOT NULL
);
