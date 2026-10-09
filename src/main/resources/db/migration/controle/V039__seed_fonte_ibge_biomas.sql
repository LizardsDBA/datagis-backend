-- [fonte_dado ja seedada no V003 - ver la] Esta migration originalmente cadastrava
-- "IBGE - Limites de biomas" aqui (gap identificado no tutorial
-- docs/tutorial-acesso-fontes-pendentes.md §0). Com a reordenacao do catalogo de
-- fontes, o insert de fonte_dado moveu pro V003 (seed consolidado, ordem de
-- ingestao); o mapeamento_coluna abaixo continua aqui porque resolve a fonte por
-- NOME (independe de qual ID ela recebeu).
--
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
