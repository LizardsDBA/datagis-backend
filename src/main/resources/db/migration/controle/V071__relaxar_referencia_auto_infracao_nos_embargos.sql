-- Achado ao vivo tentando reprocessar os lotes de Embargos (11 IBAMA, 12 ICMBio)
-- apos corrigir o CPF/CNPJ (ValidadorCpfCnpj): a maioria continuava bloqueada por
-- REFERENCIA_NAO_ENCONTRADA em num_auto_infracao -> silver.auto_infracao. Cruzando
-- contra os servicos reais do IAT/PR:
--   - Embargos IBAMA (fonte 11): o campo num_auto_infracao do embargo e
--     ALFANUMERICO (ex. "DBK2XZIO"), mas o num_auto_infracao real dos Autos IBAMA
--     (fonte 13, testado ao vivo contra o servico) e SEMPRE NUMERICO PURO (ex.
--     "368471"). Formatos incompativeis - nao e o mesmo identificador, so coincide
--     o nome do campo (provavelmente um codigo interno do GIS do IAT/PR, tipo o
--     num_tad do proprio embargo). Nenhuma quantidade de dado adicional resolveria -
--     e erro de mapeamento, nao falta de cobertura.
--   - Embargos ICMBio (fonte 12): o campo de origem e numero_ai, que E o mesmo
--     formato real usado nos Autos ICMBio (fonte 14, tambem alfanumerico) - essa
--     referencia e legitima, so incompleta (melhorou de 21 pra 14 inconsistencias
--     so promovendo o lote 14) porque o recorte do circuito (3 municipios) nao
--     necessariamente inclui TODO auto que um embargo desses municipios referencia.
-- Em nenhum dos dois casos o campo e necessario pro calculo do IAE (usa a geometria
-- do embargo pra intersecao espacial, nao essa referencia) - relaxado em ambos pelo
-- mesmo motivo pratico, documentando aqui que a causa raiz e diferente em cada um.
UPDATE ${controleSchema}.mapeamento_coluna
   SET referencia_entidade_silver = NULL
 WHERE fonte_dado_id = 11 AND atributo_destino = 'num_auto_infracao';

UPDATE ${controleSchema}.mapeamento_coluna
   SET referencia_entidade_silver = NULL
 WHERE fonte_dado_id = 12 AND atributo_destino = 'num_auto_infracao';
