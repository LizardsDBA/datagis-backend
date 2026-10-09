-- [Numeros de fonte abaixo sao os ORIGINAIS, anteriores a reordenacao do catalogo
-- no V003 - ver la pros IDs atuais (4->16, 6->17, 9->12, 10->13, 11->14).]
--
-- Achado testando foco_calor real contra as fontes 4 e 6: o INPE usa -999 em
-- risco_fogo pra "nao calculado" (ex.: foco noturno, sem calculo de risco de fogo),
-- nao um valor de risco de verdade. A validacao de faixa minima (valor_minimo=0)
-- rejeitava isso como VALOR_ABAIXO_DO_MINIMO - 22.698 registros reais bloqueados por
-- isso. Corrigido em SanitizadorEntrada (trata como falso-nulo especifico da coluna,
-- lido daqui) - a partir de agora -999/-999.0 em risco_fogo vira NULL real (campo nao
-- obrigatorio), nao mais erro.
UPDATE ${controleSchema}.mapeamento_coluna
   SET regras_extras = '{"valores_nulos": ["-999", "-999.0"]}'
 WHERE coluna_origem IN ('riscofogo', 'risco_fogo') AND atributo_destino = 'risco_fogo';

-- Limpeza da quarentena de foco_calor/auto_infracao acumulada por FORA_DO_RECORTE_
-- TERRITORIAL antes da mudanca de comportamento (OrquestradorValidacao passou a
-- descartar esses registros silenciosamente, sem quarentena - ver
-- ResultadoOrquestracao.descartadosForaDoRecorte). Sao registros de fora do Parana
-- (focos de calor do Brasil inteiro, fonte 6 e autuacoes federais nacionais, fonte
-- 4/9/10/11) que nunca deveriam ter ido pra quarentena pelo novo desenho - mantidos
-- ali seriam so ruido morto que ninguem vai analisar.
DELETE FROM ${quarentenaSchema}.q_foco_calor
 WHERE tipo_erro_id = (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo = 'FORA_DO_RECORTE_TERRITORIAL');

DELETE FROM ${quarentenaSchema}.q_auto_infracao
 WHERE tipo_erro_id = (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo = 'FORA_DO_RECORTE_TERRITORIAL');

DELETE FROM ${quarentenaSchema}.q_embargo_ambiental
 WHERE tipo_erro_id = (SELECT id FROM ${quarentenaSchema}.tipo_erro_quarentena WHERE codigo = 'FORA_DO_RECORTE_TERRITORIAL');
