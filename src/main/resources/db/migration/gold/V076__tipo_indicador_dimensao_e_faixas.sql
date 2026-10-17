-- Tela de consulta de indicadores (LIZARDS-34): a tabela agrupa os 7 indicadores por
-- dimensao e pinta cada valor de verde, amarelo ou vermelho. As duas coisas sao regra
-- de negocio, nao desenho. Se ficarem num if do Vue, ninguem mais no projeto sabe que
-- existem e mudar um limite vira alteracao de codigo. Ficam aqui, junto do indicador,
-- e o frontend so consome.
--
-- dimensao: grupo de cabecalho na tela (CONSERVACAO, TERRITORIAL, AMBIENTAL).
--
-- sentido: define o lado da comparacao. A regra de faixa e a mesma nos dois casos:
--   MAIOR_MELHOR: valor >= limite_bom e bom; >= limite_atencao e atencao; senao critico
--   MENOR_MELHOR: valor <= limite_bom e bom; <= limite_atencao e atencao; senao critico
-- ICV, IRL e IAPP medem cobertura: quanto maior, melhor. IAE, IDESMAT e IFC medem
-- passivo: quanto menor, melhor. O ISAP tambem e MENOR_MELHOR, apesar de citar area
-- protegida: a pkg_calculo_territorial calcula a sobreposicao do imovel com unidade de
-- conservacao, ou seja conflito territorial, e e gemeo do IAE em formula e em leitura.
--
-- limite_bom / limite_atencao: fronteiras das faixas verde e amarela. O IRL recebe 20
-- nos dois porque o percentual minimo de Reserva Legal na Mata Atlantica e 20% (Codigo
-- Florestal, Lei 12.651/2012, constante fixada na V072): abaixo disso o imovel esta em
-- desacordo com a lei, acima esta em conformidade, e nao existe faixa intermediaria a
-- ser representada. Os demais limites sao decisao de produto: o PDF do desafio define
-- formula e unidade de cada indicador, nao faixa de classificacao.
--
-- ordem_exibicao: ordem das colunas na tela, da esquerda para a direita.
--
-- Sem COMMENT ON COLUMN de proposito: o usuario da aplicacao nao tem privilegio de
-- comentario em tabela de outro schema (ORA-41900).

ALTER TABLE ${goldSchema}.tipo_indicador ADD (
    dimensao        VARCHAR2(20),
    sentido         VARCHAR2(15),
    limite_bom      NUMBER,
    limite_atencao  NUMBER,
    ordem_exibicao  NUMBER
);

UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'CONSERVACAO', sentido = 'MAIOR_MELHOR', limite_bom = 70, limite_atencao = 40, ordem_exibicao = 1 WHERE codigo = 'ICV';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'CONSERVACAO', sentido = 'MAIOR_MELHOR', limite_bom = 20, limite_atencao = 20, ordem_exibicao = 2 WHERE codigo = 'IRL';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'CONSERVACAO', sentido = 'MAIOR_MELHOR', limite_bom = 70, limite_atencao = 40, ordem_exibicao = 3 WHERE codigo = 'IAPP';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'TERRITORIAL', sentido = 'MENOR_MELHOR', limite_bom = 30, limite_atencao = 60, ordem_exibicao = 4 WHERE codigo = 'ISAP';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'TERRITORIAL', sentido = 'MENOR_MELHOR', limite_bom = 30, limite_atencao = 60, ordem_exibicao = 5 WHERE codigo = 'IAE';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'AMBIENTAL',   sentido = 'MENOR_MELHOR', limite_bom = 30, limite_atencao = 60, ordem_exibicao = 6 WHERE codigo = 'IDESMAT';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'AMBIENTAL',   sentido = 'MENOR_MELHOR', limite_bom = 30, limite_atencao = 60, ordem_exibicao = 7 WHERE codigo = 'IFC';

ALTER TABLE ${goldSchema}.tipo_indicador ADD CONSTRAINT ck_tipo_indicador_dimensao
    CHECK (dimensao IN ('CONSERVACAO', 'TERRITORIAL', 'AMBIENTAL'));
ALTER TABLE ${goldSchema}.tipo_indicador ADD CONSTRAINT ck_tipo_indicador_sentido
    CHECK (sentido IN ('MAIOR_MELHOR', 'MENOR_MELHOR'));
