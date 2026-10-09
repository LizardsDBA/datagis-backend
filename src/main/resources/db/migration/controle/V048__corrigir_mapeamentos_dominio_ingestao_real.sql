-- [IDs atualizados pra refletir a reordenacao do catalogo de fontes do V003 - ver
-- la. Numeros originais entre parenteses, mantidos pra rastreabilidade do achado.]
--
-- Corrige 2 mapeamentos de controle.mapeamento_coluna que bloqueavam ICMBio (#7,
-- era #2) e FUNAI (#8, inalterado) inteiros pela regra All-or-Nothing, achados
-- testando a ingestao real hoje (nunca tinha sido rodada com sucesso contra esses
-- dados de verdade):
--
-- 1. fonte 7 (ICMBio, era #2): 'grupouc' valida contra 'categoria_area_protegida', mas o
--    valor real e o GRUPO do SNUC ('PI'=Protecao Integral, 'US'=Uso Sustentavel),
--    nao uma categoria especifica (APA/PARNA/REBIO/...) - nunca bateria em nada
--    (confirmado: 28 de 28 UCs rejeitadas com "PI"/"US" nao encontrado). O
--    domínio certo seria 'tipo_area_protegida', mas os codigos la sao palavras
--    completas (UC_PROTECAO_INTEGRAL/UC_USO_SUSTENTAVEL), nao as siglas PI/US -
--    precisaria de sinonimo novo em ValidadorDominio pra resolver de verdade.
--
-- 2. fontes 7 e 8 (ICMBio e FUNAI, era #2 e #8): 'uf_sigla' valida contra 'unidade_federativa',
--    mas silver.area_protegida NAO TEM coluna de UF direta (só existe via tabela-
--    ponte area_protegida_uf, nao populada por este mapeamento) -
--    extrairParametrosAreaProtegida nunca le esse atributo. E validacao morta que
--    so bloqueia (achado: 1 TI da FUNAI com uf_sigla="PR,SC", multi-UF, rejeitada
--    sem necessidade).
--
-- So limpar tabela_referencia NAO resolve (achado na pratica, testando) -
-- OrquestradorValidacao.inferirTabelaReferenciaCanonico infere a validacao de
-- dominio automaticamente so pelo NOME do atributo_destino ("categoria_area_protegida"
-- e "uf" estao no switch), ignorando o que esta de verdade na coluna. Mesma
-- armadilha ja documentada pro orgao do MMA/SFB (V042) - a correcao de verdade e
-- trocar o atributo_destino pra um nome NAO canonico, que nao dispara a inferencia.
-- O valor continua preservado em atributos_origem (payload bruto); so para de
-- bloquear a ingestao. Categorizacao fina (tipo_area_protegida correto PI/US, UF
-- multi-estado) fica pra quando alguem tratar isso de verdade.
UPDATE ${controleSchema}.mapeamento_coluna
   SET atributo_destino = 'grupo_uc_bruto', tabela_referencia = NULL
 WHERE fonte_dado_id = 7 AND coluna_origem = 'grupouc';

UPDATE ${controleSchema}.mapeamento_coluna
   SET atributo_destino = 'uf_sigla_bruto', tabela_referencia = NULL
 WHERE fonte_dado_id IN (7, 8) AND coluna_origem = 'uf_sigla';

-- 3. "Sistema Costeiro-Marinho" e nomenclatura oficial do IBGE (edicao 2025, mesmo
--    dataset do shapefile de biomas - "2025_Biomas-e-Sistema-Costeiro-Marinho-do-
--    Brasil") - achado bloqueando o ICMBio (#7, era #2) porque uma UC real tem esse valor
--    no campo 'biomas' e nenhum dos 6 biomas terrestres da V011 bate. Nao e gap de
--    validacao (como grupouc/uf_sigla acima) - e um 7o valor de dominio legitimo
--    faltando. Entra como silver.bioma de verdade (sem geometria por ora, mesmo
--    estado inicial dos outros 6 antes da V040).
INSERT INTO ${silverSchema}.bioma (codigo, descricao)
SELECT 'SISTEMA_COSTEIRO_MARINHO', 'Sistema Costeiro-Marinho'
 WHERE NOT EXISTS (SELECT 1 FROM ${silverSchema}.bioma WHERE codigo = 'SISTEMA_COSTEIRO_MARINHO');

-- 4. Mesmo padrao dos itens 1/2, achado ingerindo embargos (#11/#12, era #5/#9) e
--    autos de infracao (#14, era #11) de verdade pela primeira vez: 'tipo_area'/'tipo_infra' trazem
--    a NATUREZA da infracao em texto livre ("Atividade", "Das Infracoes Contra a
--    Flora"), nao uma das 4 categorias amplas de tipo_area_embargo (DESMATAMENTO/
--    QUEIMADA/EXPLORACAO_IRREGULAR/OUTROS). 'tipo' (fonte 11) traz o mesmo tipo de
--    texto livre ("Destruicao") contra tipo_auto_infracao, que classifica o TIPO DE
--    PENALIDADE (MULTA_SIMPLES/MULTA_DIARIA/...), nao o tipo de infracao. Sao eixos
--    de classificacao diferentes do que o dominio modela - mesmo tratamento.
UPDATE ${controleSchema}.mapeamento_coluna
   SET atributo_destino = 'tipo_area_bruto', tabela_referencia = NULL
 WHERE fonte_dado_id = 11 AND coluna_origem = 'tipo_area';

UPDATE ${controleSchema}.mapeamento_coluna
   SET atributo_destino = 'tipo_infra_bruto', tabela_referencia = NULL
 WHERE fonte_dado_id = 12 AND coluna_origem = 'tipo_infra';

UPDATE ${controleSchema}.mapeamento_coluna
   SET atributo_destino = 'tipo_bruto', tabela_referencia = NULL
 WHERE fonte_dado_id = 14 AND coluna_origem = 'tipo';

-- 5. PRODES (#15, era #3): 'class_name' vem como "dAAAA" (ano prefixado, ex. "d2000",
--    "d2023" - um codigo por ano de deteccao), nao as 5 categorias genericas de
--    classe_prodes (DESMATAMENTO/FLORESTA/NAO_FLORESTA/NUVEM/HIDROGRAFIA). 100% dos
--    38.296 registros reais rejeitados por isso - confirma que e mapeamento errado,
--    nao qualidade de dado. O ano em si ja vem separado (coluna 'year' -> 'ano'),
--    entao nao se perde informacao real com esse bypass - so a flag classe_prodes_id
--    fica NULL em vez de resolver, igual aos outros itens desta migration.
UPDATE ${controleSchema}.mapeamento_coluna
   SET atributo_destino = 'class_name_bruto', tabela_referencia = NULL
 WHERE fonte_dado_id = 15 AND coluna_origem = 'class_name';

-- 6. Diferente dos itens acima (mapeamento pro dominio errado), este e dominio
--    incompleto de verdade: focos de calor reais (fonte 16, era #4, IAT/PR) trazem 3
--    satelites da rede do INPE que nunca foram semeados em silver.satelite
--    (so tinha os 7 ja conhecidos de outras fontes). Mesmo tratamento dado ao
--    bioma "Sistema Costeiro-Marinho" acima - entra como valor de dominio
--    legitimo, nao bypass de validacao.
INSERT INTO ${silverSchema}.satelite (codigo, descricao)
SELECT 'MSG-03', 'Meteosat Second Generation 3' FROM dual
 WHERE NOT EXISTS (SELECT 1 FROM ${silverSchema}.satelite WHERE codigo = 'MSG-03')
UNION ALL
SELECT 'METOP-B', 'MetOp-B (EUMETSAT)' FROM dual
 WHERE NOT EXISTS (SELECT 1 FROM ${silverSchema}.satelite WHERE codigo = 'METOP-B')
UNION ALL
SELECT 'METOP-C', 'MetOp-C (EUMETSAT)' FROM dual
 WHERE NOT EXISTS (SELECT 1 FROM ${silverSchema}.satelite WHERE codigo = 'METOP-C');
