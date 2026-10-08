-- Achado testando a ingestao real de Reserva Legal (lote 144, circuito): 3 dos 2.777
-- registros sao Reserva Legal PROPOSTA (cod_tema='ARL_PROPOSTA', des_condic='Aguardando
-- analise' ou similar) - estado real do fluxo do SICAR em que o proprietario propos a
-- RL mas ela ainda nao foi delimitada geometricamente (num_area=0, sem geometria).
-- Nao e dado ruim, e um estagio legitimo do processo. A V053 marcou geometria como
-- obrigatoria por padrao - errado pra este caso, mesmo principio ja usado em toda
-- ingestao do projeto (CLAUDE.md §5.8 item 1: "geometria ausente nao e erro").
UPDATE ${controleSchema}.mapeamento_coluna
   SET obrigatorio = 0
 WHERE atributo_destino = 'geometria'
   AND fonte_dado_id IN (
       SELECT id FROM ${controleSchema}.fonte_dado
        WHERE nome IN ('IAT/PR - APP Total (CAR)', 'IAT/PR - Reserva Legal (CAR)', 'IAT/PR - Vegetação Nativa (CAR)')
   );
