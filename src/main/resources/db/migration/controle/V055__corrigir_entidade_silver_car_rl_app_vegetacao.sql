-- Corrige um bug real achado ANTES de rodar a ingestao das 3 fontes da V053: como
-- entidade_silver ficou NULL la, TransicaoBronzeSilverService.resolverEntidadeSilver
-- cai no fallback por NOME da fonte - e como os 3 nomes contem "(CAR)", o fallback
-- resolvia pra 'imovel_rural' (ver o metodo: "if (nome.contains("car")...) return
-- imovel_rural"). Isso faria o MERGE tentar gravar poligonos de Reserva Legal/APP/
-- Vegetacao Nativa DENTRO de silver.imovel_rural usando cod_imovel como chave -
-- arriscando sobrescrever os imoveis reais ja la (mesmo cod_imovel, area/geometria
-- erradas). Achado por inspecao de codigo antes de rodar qualquer ingestao real,
-- nao em producao.
UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'app_declarada'
 WHERE nome = 'IAT/PR - APP Total (CAR)';

UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'reserva_legal'
 WHERE nome = 'IAT/PR - Reserva Legal (CAR)';

UPDATE ${controleSchema}.fonte_dado SET entidade_silver = 'vegetacao_nativa_declarada'
 WHERE nome = 'IAT/PR - Vegetação Nativa (CAR)';

-- Mapeamento que faltou na V053: 'objectid' -> identificador_origem. E a CHAVE DE
-- DEDUPLICACAO/UNICIDADE de cada registro (mesmo papel que identificador_origem ja
-- tem em desmatamento/foco_calor) - NAO pode ser cod_imovel, porque um imovel pode
-- ter varios poligonos de Reserva Legal nao-contiguos (confirmado ao vivo contra o
-- servico real: existem casos reais com 2+ poligonos pro mesmo cod_imovel). Usar
-- cod_imovel como chave natural faria o DeduplicadorRegistros descartar fragmentos
-- reais como se fossem duplicata do mesmo registro.
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)';
