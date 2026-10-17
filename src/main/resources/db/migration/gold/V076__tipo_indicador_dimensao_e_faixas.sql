-- Tela de consulta de indicadores (LIZARDS-34): a tabela agrupa os 7 indicadores por
-- dimensão e pinta cada valor de verde, amarelo ou vermelho. As duas coisas são regra
-- de negócio, não desenho — se ficarem num if do Vue, ninguém mais no projeto sabe que
-- existem e mudar um limite vira alteração de código. Ficam aqui, junto do indicador,
-- e o frontend só consome.
--
-- dimensao: grupo de cabeçalho na tela (CONSERVACAO, TERRITORIAL, AMBIENTAL).
-- sentido: em ICV, IRL, IAPP e ISAP quanto MAIOR melhor. Em IAE, IDESMAT e IFC quanto
-- MENOR melhor — mais embargo, mais desmatamento e mais foco de calor significam imóvel
-- pior. A regra de faixa é a mesma nos dois casos, só muda o lado da comparação:
--   MAIOR_MELHOR: valor >= limite_bom = bom; >= limite_atencao = atenção; senão crítico
--   MENOR_MELHOR: valor <= limite_bom = bom; <= limite_atencao = atenção; senão crítico
-- limite_bom / limite_atencao: fronteiras das faixas verde e amarela.
-- ordem_exibicao: ordem das colunas na tela, da esquerda para a direita.
--
-- Sem COMMENT ON COLUMN de propósito: o usuário da aplicação não tem privilégio de
-- comentário em tabela de outro schema (ORA-41900).
ALTER TABLE ${goldSchema}.tipo_indicador ADD (
    dimensao        VARCHAR2(20),
    sentido         VARCHAR2(15),
    limite_bom      NUMBER,
    limite_atencao  NUMBER,
    ordem_exibicao  NUMBER
);

UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'CONSERVACAO', sentido = 'MAIOR_MELHOR', limite_bom = 70, limite_atencao = 40, ordem_exibicao = 1 WHERE codigo = 'ICV';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'CONSERVACAO', sentido = 'MAIOR_MELHOR', limite_bom = 70, limite_atencao = 40, ordem_exibicao = 2 WHERE codigo = 'IRL';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'CONSERVACAO', sentido = 'MAIOR_MELHOR', limite_bom = 70, limite_atencao = 40, ordem_exibicao = 3 WHERE codigo = 'IAPP';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'TERRITORIAL', sentido = 'MAIOR_MELHOR', limite_bom = 70, limite_atencao = 40, ordem_exibicao = 4 WHERE codigo = 'ISAP';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'TERRITORIAL', sentido = 'MENOR_MELHOR', limite_bom = 30, limite_atencao = 60, ordem_exibicao = 5 WHERE codigo = 'IAE';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'AMBIENTAL',   sentido = 'MENOR_MELHOR', limite_bom = 30, limite_atencao = 60, ordem_exibicao = 6 WHERE codigo = 'IDESMAT';
UPDATE ${goldSchema}.tipo_indicador SET dimensao = 'AMBIENTAL',   sentido = 'MENOR_MELHOR', limite_bom = 30, limite_atencao = 60, ordem_exibicao = 7 WHERE codigo = 'IFC';

ALTER TABLE ${goldSchema}.tipo_indicador ADD CONSTRAINT ck_tipo_indicador_dimensao
    CHECK (dimensao IN ('CONSERVACAO', 'TERRITORIAL', 'AMBIENTAL'));
ALTER TABLE ${goldSchema}.tipo_indicador ADD CONSTRAINT ck_tipo_indicador_sentido
    CHECK (sentido IN ('MAIOR_MELHOR', 'MENOR_MELHOR'));