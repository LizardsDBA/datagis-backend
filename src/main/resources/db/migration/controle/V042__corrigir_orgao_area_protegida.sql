-- Corrige o fallback de orgao_id em area_protegida: antes de hoje, qualquer registro
-- sem orgao resolvido caia sempre em "ICMBIO" (hardcoded em
-- PersistidorSilverService.extrairParametrosAreaProtegida), mislabeando em silencio
-- qualquer fonte que nao fosse ICMBio (achado real testando MMA/SFB - ver
-- docs/tutorial-acesso-fontes-pendentes.md). A partir desta versao, o fallback usa o
-- orgao real cadastrado em controle.fonte_dado pra cada fonte.
--
-- 2 codigos novos em silver.orgao: MMA (publica o CNUC, que inclui UC estadual e
-- municipal alem da federal) e SFB (publica o CNFP, consolidacao de florestas
-- publicas de varios orgaos - INCRA, FUNAI, ICMBio, secretarias estaduais e
-- municipais). Nao da pra criar um codigo de orgao pra cada uma das centenas de
-- secretarias estaduais/municipais que aparecem em texto livre nos dois datasets -
-- o texto original continua preservado em atributos_origem; o orgao_id vira o
-- publicador quando o texto declarado nao bate em nada conhecido.
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('MMA', 'Ministerio do Meio Ambiente (CNUC)');
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('SFB', 'Servico Florestal Brasileiro (CNFP)');

-- Mapeia o campo de orgao declarado em cada fonte pro atributo NAO canonico
-- 'orgao_declarado' (de proposito, nao 'orgao') - nomear exatamente 'orgao' faria
-- OrquestradorValidacao disparar validacao de dominio automatica so pelo nome do
-- atributo (ver inferirTabelaReferenciaCanonico), e boa parte do texto livre dessas
-- 2 fontes (secretarias estaduais/municipais, combinacoes "FUNAI / INCRA / Prefeitura
-- de X") nao bate em nenhum codigo - rejeitaria o lote inteiro pela regra
-- All-or-Nothing. Com o nome nao canonico, o valor flui sem validacao de dominio e
-- so e resolvido (com fallback gracioso) na persistencia, nunca bloqueia a ingestao.
INSERT INTO ${controleSchema}.mapeamento_coluna (
    fonte_dado_id, coluna_origem, atributo_destino, destino_tipo, tipo_dado, obrigatorio,
    valor_minimo, tabela_referencia, referencia_entidade_silver, validar_cpf_cnpj,
    validar_geometria, validar_recorte_pr, impedir_regressao
)
SELECT f.id, 'org_gestor', 'orgao_declarado', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'MMA - Unidades de Conservacao (CNUC nacional)'
UNION ALL
SELECT f.id, 'orgao', 'orgao_declarado', 'COLUNA', 'texto', 0, NULL, NULL, NULL, 0, 0, 0, 0
FROM ${controleSchema}.fonte_dado f WHERE f.nome = 'SFB - Cadastro Nacional de Florestas Publicas (CNFP)';
