-- Cadastro das 3 fontes que resolvem IAPP/IRL/ICV, achadas e verificadas ao vivo em
-- 2026-10-07 (docs/tutorial-acesso-fontes-pendentes.md §0-bis). Servico ArcGIS publico
-- do proprio geoportal IAT/PR, SEM precisar do credenciamento SICAR Temas (§1 do
-- tutorial, que fica como registro historico - nao e mais o caminho necessario):
--
--   https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/
--   Base_Geo_Cadastro_Ambiental_rural/FeatureServer
--
-- 9 camadas no total; cadastramos aqui as 3 relevantes pros 7 indicadores oficiais
-- (layers 1, 2, 3 - Hidrografia/Area Consolidada/Pousio/Servidao/Uso Restrito/Area
-- Imovel ficam de fora por ora, fora do escopo ou redundantes com fontes ja
-- cadastradas - ver comparacao de confiabilidade feita ao vivo mesma sessao: o WFS
-- SICAR (fonte #1) e mais confiavel que a camada "Area Imovel" deste mesmo servico
-- pra perimetro de imovel, entao nao cadastramos essa aqui).
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
-- entidade_silver = NULL de proposito: estas 3 camadas sao ATRIBUTOS do imovel
-- (RL/APP/vegetacao declaradas), nao uma entidade de fato nova como embargo_
-- ambiental - a modelagem Silver (tabelas novas tipo silver.reserva_legal,
-- silver.app_declarada, silver.vegetacao_nativa_declarada, cada uma com FK pra
-- imovel_rural) ainda NAO foi decidida (tutorial §0-bis, passo 3, em aberto).
-- Rodar ingestao agora grava tudo no Bronze normalmente, mas a transicao Bronze->
-- Silver fica sem handler ate a modelagem ser feita e PersistidorSilverService
-- ganhar os casos novos - esperado, nao e bug.
--
-- ativo=1 mas SEM cron_expressao: fica disponivel pra disparo manual
-- (POST /api/fontes/{id}/executar) sem agendamento automatico ainda - mesmo
-- principio de fontes novas recem-cadastradas (MMA/SFB, V041).
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
INSERT INTO ${controleSchema}.fonte_dado (
    nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo, entidade_silver
) VALUES (
    'IAT/PR - APP Total (CAR)', 'SFB/SICAR (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/1/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1, NULL
);

INSERT INTO ${controleSchema}.fonte_dado (
    nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo, entidade_silver
) VALUES (
    'IAT/PR - Reserva Legal (CAR)', 'SFB/SICAR (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/2/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1, NULL
);

INSERT INTO ${controleSchema}.fonte_dado (
    nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo, entidade_silver
) VALUES (
    'IAT/PR - Vegetação Nativa (CAR)', 'SFB/SICAR (via IAT/PR)', 'API_REST',
    'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/3/query?where=1=1&outFields=*&f=geojson&outSR=4674',
    'GEOJSON', 1, NULL
);

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
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'geometria', 1, NULL, NULL, NULL, 0, 1, 1, 0
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
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'geometria', 1, NULL, NULL, NULL, 0, 1, 1, 0
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
SELECT f.id, 'geometry', 'geometria', 'GEOMETRIA', 'geometria', 1, NULL, NULL, NULL, 0, 1, 1, 0
  FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IAT/PR - Vegetação Nativa (CAR)';
