-- Fecha o gap achado na auditoria contra a pag. 6 do slide oficial do desafio
-- (docs/publicacao-gold-paralela.md §auditoria, 2026-10-25): o slide pede
-- explicitamente "deficit ou excedente em hectares" pro IRL e "passivo a recuperar
-- em hectares" pro IAPP - a procedure ja calculava o percentual de cada um, mas
-- nunca guardava esse segundo numero porque nao existia coluna pra isso.
--
-- indicador_resultado e generica (1 linha por indicador por versao, via
-- tipo_indicador_id) - por isso o campo novo la e generico tambem
-- (valor_secundario_ha), usado so por IRL e IAPP, NULL pros outros 5. Ja
-- imovel_publicado tem 1 coluna fixa por indicador (mesmo padrao de VALOR_IRL/
-- VALOR_IAPP ja existentes) - por isso la sao 2 colunas especificas e nomeadas.
ALTER TABLE gold.indicador_resultado ADD valor_secundario_ha NUMBER;

-- IRL: positivo = excedente sobre o minimo exigido pelo bioma; negativo = deficit.
-- IAPP: area de APP sem vegetacao nativa (sempre >= 0, nunca pode exceder a APP total).
ALTER TABLE gold.imovel_publicado ADD (
    valor_irl_deficit_ha  NUMBER,
    valor_iapp_passivo_ha NUMBER
);
