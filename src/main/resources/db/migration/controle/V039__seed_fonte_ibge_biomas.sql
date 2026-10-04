-- Cadastra a fonte "IBGE - Limites de biomas" (gap identificado no tutorial
-- docs/tutorial-acesso-fontes-pendentes.md §0 - fonte ja validada tecnicamente desde a
-- v2 do levantamento, mas nunca tinha virado controle.fonte_dado de verdade).
--
-- DOWNLOAD_URL porque o IBGE publica um .zip estatico e estavel direto no FTP deles
-- (sem SPA/sessao no meio, diferente do CAR em consultapublica.car.gov.br) - dá pra
-- automatizar (ver IngestaoShapefileExecutor.descompactarDeDownload).
--
-- entidade_silver='bioma' explicito (nunca deixar em branco pra essa fonte): o nome
-- contem "ibge", e o heuristico de resolverEntidadeSilver (sem entidade_silver
-- setada) bateria em "ibge" antes de qualquer outra coisa e mandaria pra 'municipio'
-- por engano - ver TransicaoBronzeSilverService.resolverEntidadeSilver.
--
-- Sem cron_expressao de proposito: biomas do IBGE mudam raramente (edicao anterior a
-- 2025 era de anos atras); agendar re-download automatico nao traz valor e so gasta
-- banda - fica so disparo manual por enquanto, igual outras fontes de baixa mudanca.
INSERT INTO ${controleSchema}.fonte_dado (
    nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo, entidade_silver
) VALUES (
    'IBGE - Limites de biomas', 'IBGE', 'DOWNLOAD_URL',
    'http://geoftp.ibge.gov.br/informacoes_ambientais/estudos_ambientais/biomas/vetores/2025_Biomas-e-Sistema-Costeiro-Marinho-do-Brasil-1-250000_shp.zip',
    'SHAPEFILE', 1, 'bioma'
);

-- NM_BIOMA -> 'codigo': tabela_referencia='bioma' faz o ValidadorDominio confirmar que
-- o nome (ex.: "Mata Atlântica") é um dos 6 já semeados na V011 e TRANSFORMA o valor
-- pro codigo canonico (ex.: 'MATA_ATLANTICA') antes de chegar em
-- PersistidorSilverService.extrairParametrosBioma, que resolve o id existente por
-- esse codigo e faz MERGE por id (nunca insere linha nova - ver V038).
--
-- CD_BIOMA (1-6, codigo oficial do IBGE) so como atributo informativo por ora - nao e
-- usado pra merge nem validacao hoje, mas e o campo que
-- TransicaoBronzeSilverService.resolverChaveNatural usa como chave natural pra
-- dedup/regressao (chave do PAYLOAD BRUTO, nao do atributo transformado).
--
-- Sem mapeamento de geometria (destino_tipo=GEOMETRIA): o Shapefile ja entrega a
-- geometria separada do payload JSON (IngestaoShapefileExecutor.paraRegistro),
-- OrquestradorValidacao usa reg.getGeometriaWkt() direto sem precisar de mapeamento -
-- e, de proposito, SEM validar_recorte_pr (bioma e nacional, a maior parte de cada
-- poligono fica fora do Parana; ligar esse flag rejeitaria Amazonia/Caatinga/Pampa
-- pra quarentena por "fora do recorte", o que seria errado aqui).
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'NM_BIOMA', 'codigo', 'COLUNA', 'texto', 1, NULL, 'bioma', NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Limites de biomas'
UNION ALL
SELECT f.id, 'CD_BIOMA', 'cd_bioma', 'COLUNA', 'numero', 1, NULL, NULL, NULL, 0, 0, 0, 1
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'IBGE - Limites de biomas';
