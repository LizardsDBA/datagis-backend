-- Mapeamento de colunas das 3 fontes que resolvem IAPP/IRL/ICV, achadas e
-- verificadas ao vivo em 2026-10-07 (docs/tutorial-acesso-fontes-pendentes.md
-- §0-bis). Servico ArcGIS publico do proprio geoportal IAT/PR, SEM precisar do
-- credenciamento SICAR Temas (§1 do tutorial, que fica como registro historico -
-- nao e mais o caminho necessario):
--
--   https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/
--   Base_Geo_Cadastro_Ambiental_rural/FeatureServer
--
-- 9 camadas no total; as 3 relevantes pros 7 indicadores oficiais sao as layers 1,
-- 2, 3 (Hidrografia/Area Consolidada/Pousio/Servidao/Uso Restrito/Area Imovel ficam
-- de fora por ora, fora do escopo ou redundantes com fontes ja cadastradas - ver
-- comparacao de confiabilidade feita ao vivo mesma sessao: o WFS SICAR e mais
-- confiavel que a camada "Area Imovel" deste mesmo servico pra perimetro de imovel,
-- entao nao cadastramos essa aqui). As fontes em si (nome/orgao/url/entidade_silver)
-- estao seedadas no V003 (seed consolidado do catalogo, reordenado por pedido do
-- time) - este arquivo so cuida do mapeamento_coluna, que resolve por NOME.
--
-- Verificado ao vivo via curl direto no ArcGIS REST antes deste cadastro:
--   - APP Total (layer 1): 381.962 poligonos
--   - Reserva Legal (layer 2): 347.867 poligonos
--   - Vegetacao Nativa (layer 3): 393.153 poligonos
--   - outSR=4674 funciona (geometria MultiPolygon real, nao resumo estatistico sem
--     geometria como o dead-end anterior Vegetacao_Nativa_IAT_2021, documentado no
--     tutorial §2 como historico superado)
--   - cod_imovel no mesmo formato de silver.imovel_rural.cod_imovel (testado:
--     PR-4101903-E8F398DBF54149049CFA55AAFB51CD16 bate exato entre WFS e este servico,
--     mesma area, mesma geometria - 24 vertices nos dois)
--   - des_condic traz o mesmo texto do SICAR que ValidadorDominio ja resolve
--     (CLAUDE.md §9.17), so sem acentuacao (ex. "notificacao" em vez de "notificação")
--
-- formato_esperado=GEOJSON, mesma familia ArcGIS REST ja suportada por
-- IngestaoGeoJsonExecutor + PaginadorUrlGeoJson (CLAUDE.md §5.8) - paginacao
-- automatica por maxRecordCount=2000, sem codigo novo necessario.
--
-- Mapeamento DELIBERADAMENTE minimo, mesmo motivo do V041 (MMA/SFB): cod_imovel
-- SEM referencia_entidade_silver='imovel_rural' ativada ainda - essas 3 camadas
-- cobrem o Parana inteiro (~350-390 mil registros cada), mas a Silver hoje so tem
-- os ~4.608 imoveis do circuito de homologacao (Curitiba/Maringa/Ponta Grossa)
-- ingeridos - ativar a integridade cruzada agora faria o lote inteiro cair na
-- quarentena pela regra All-or-Nothing (quase todo cod_imovel nao existiria ainda
-- na Silver). ind_status/des_condic mapeados pra atributos _bruto (nao canonicos),
-- mesma licao aprendida nesta sessao (V048) sobre inferirTabelaReferenciaCanonico
-- disparar validacao de dominio so pelo NOME do atributo, nao pelo que esta
-- configurado de fato.
--
-- geometria com obrigatorio=0 (nao 1): achado testando a ingestao real (lote 144,
-- circuito) - 3 dos 2.777 registros de Reserva Legal sao RL "proposta"
-- (cod_tema=ARL_PROPOSTA, des_condic="Aguardando analise" ou similar), estagio
-- legitimo do fluxo do SICAR em que o proprietario propos a RL mas ela ainda nao
-- foi delimitada geometricamente (num_area=0, sem geometria) - nao e dado ruim,
-- mesmo principio ja usado em toda ingestao do projeto (CLAUDE.md §5.8 item 1:
-- "geometria ausente nao e erro").
--
-- objectid -> identificador_origem: a CHAVE DE DEDUPLICACAO/UNICIDADE de cada
-- registro (mesmo papel que identificador_origem ja tem em desmatamento/
-- foco_calor) - NAO pode ser cod_imovel, porque um imovel pode ter varios
-- poligonos de Reserva Legal nao-contiguos (confirmado ao vivo contra o servico
-- real: existem casos reais com 2+ poligonos pro mesmo cod_imovel). Usar
-- cod_imovel como chave natural faria o DeduplicadorRegistros descartar
-- fragmentos reais como se fossem duplicata do mesmo registro.
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'num_area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'ind_status', 'situacao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'des_condic', 'condicao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'geometria', 0, NULL, NULL, NULL, 0, 1, 1, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - APP Total (CAR)'
UNION ALL
SELECT f.id, 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'num_area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'ind_status', 'situacao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'des_condic', 'condicao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'geometria', 0, NULL, NULL, NULL, 0, 1, 1, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Reserva Legal (CAR)'
UNION ALL
SELECT f.id, 'cod_imovel', 'cod_imovel', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'num_area', 'area_ha', 'COLUNA', 'numero', 0, 0.0, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'ind_status', 'situacao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'des_condic', 'condicao_bruto', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'geometria', 0, NULL, NULL, NULL, 0, 1, 1, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)'
UNION ALL
SELECT f.id, 'objectid', 'identificador_origem', 'COLUNA', 'texto', 1, NULL, NULL, NULL, 0, 0, 0, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)';
