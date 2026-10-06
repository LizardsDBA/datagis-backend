-- Reverte a decisao da V050 de tratar -999 como sentinela-nulo em risco_fogo.
-- O time confirmou: -999 e um valor real e significativo enviado pelo INPE
-- (foco noturno/sem calculo de risco), nao "dado ausente" - deve ficar visivel
-- na Silver como valor literal, igual ja estava configurado pra fonte 4
-- (valor_minimo=-5000, ajustado manualmente antes desta migration). Alinha a
-- fonte 6 com o mesmo criterio: so amplia o piso, sem mais nulificar o valor.
UPDATE ${controleSchema}.mapeamento_coluna
   SET valor_minimo = -5000, regras_extras = NULL
 WHERE coluna_origem = 'risco_fogo' AND atributo_destino = 'risco_fogo' AND fonte_dado_id = 6;

-- Backfill dos 237 registros que a V050 ja tinha promovido pra Silver com
-- risco_fogo NULL (fonte 6) - o valor original de -999 continua preservado em
-- atributos_origem (nunca foi perdido de verdade), so nao estava na coluna
-- tipada. Restaura a partir de la.
UPDATE ${silverSchema}.foco_calor
   SET risco_fogo = -999
 WHERE fonte_dado_id = 6
   AND risco_fogo IS NULL
   AND JSON_VALUE(atributos_origem, '$.risco_fogo') IN ('-999', '-999.0');
