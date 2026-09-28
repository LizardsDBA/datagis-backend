-- Novo tipo de erro pra dedup Bronze->Silver: quando um campo estava preenchido num
-- lote anterior e veio nulo/vazio no lote mais recente da mesma chave natural, a
-- transformacao NAO sobrescreve em silencio - manda a linha pra quarentena pro
-- Operador decidir (RN007). Ver docs/modelagem-silver-gold-quarentena.md secao 3.5.
-- Nao da pra semear na V015 (ja aplicada) - muda o checksum do Flyway.
INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao)
VALUES ('REGRESSAO_DE_VALOR', 'Campo preenchido em lote anterior veio nulo/vazio no lote mais recente');
