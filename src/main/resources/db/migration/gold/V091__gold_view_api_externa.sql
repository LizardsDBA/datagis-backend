-- API para sistemas externos (LIZARDS-57, US12): view que a API /api/externo/v1 le.
--
-- Por que view e nao as tabelas direto: a API e um contrato com quem esta fora do
-- projeto. As tabelas da Gold mudam conforme o calculo evolui (V086 e V087 ja criaram
-- coluna nova em imovel_publicado e indicador_resultado); a view absorve essas mudancas
-- e o consumidor externo nao quebra. Mesmo motivo das vw_mapa_* (V080) para o GeoServer.
--
-- Uma view so para os 7 indicadores, com uma linha por imovel e indicador, em vez de uma
-- view por indicador: as 7 teriam as mesmas colunas e so mudaria o filtro, e coluna nova
-- teria que ser repetida 7 vezes. O endpoint por indicador filtra a coluna "indicador".
--
-- O que e "vigente" - duas regras, as mesmas que o portal ja usa, para a API e a tela
-- nunca mostrarem numeros diferentes:
--   1. Imovel: a versao mais recente de gold.imovel_publicado (data_publicacao, id para
--      desempatar) - mesmo criterio do ConsultaIndicadoresService.versaoAtualDeCadaImovel.
--      A tabela guarda uma linha por versao publicada (historico de auditoria).
--   2. Indicador: o resultado mais recente daquele indicador para o imovel ate o momento
--      da publicacao - e exatamente o que pkg_gold_publicacao.f_ultimo_valor gravou nas
--      colunas valor_* da publicacao. Um recalculo feito depois, ainda nao publicado,
--      nao aparece na API antes de aparecer no portal.
-- Imovel sem resultado de um indicador nao gera linha daquele indicador.
--
-- faixa: BOM / ATENCAO / CRITICO pela mesma regra da tela (V076 e IndicadoresView.vue):
--   MENOR_MELHOR: valor <= limite_bom e BOM; <= limite_atencao e ATENCAO; senao CRITICO
--   MAIOR_MELHOR: valor >= limite_bom e BOM; >= limite_atencao e ATENCAO; senao CRITICO
-- Sem valor ou sem limite cadastrado, a faixa fica nula.
--
-- Sem geometria de proposito: quem integra quer o numero, e a geometria do imovel pesa
-- mais que o resto da linha inteira. Se um consumidor precisar dela, entra numa view
-- propria, sem pesar esta.
--
-- Leitura cross-schema (SILVER.municipio): o dono da view (GOLD) ja tem SELECT ANY TABLE
-- direto (infra/oracle-init/01-criar-schemas.sh), que e o que o Oracle exige para criar
-- view sobre tabela de outro schema.

CREATE OR REPLACE VIEW ${goldSchema}.vw_api_indicador_vigente AS
WITH imovel_vigente AS (
    SELECT p.*,
           ROW_NUMBER() OVER (PARTITION BY p.cod_imovel
                              ORDER BY p.data_publicacao DESC, p.id DESC) AS ordem_versao
      FROM ${goldSchema}.imovel_publicado p
)
SELECT i.cod_imovel,
       i.municipio_cod_ibge,
       m.nome                    AS municipio_nome,
       i.area_ha                 AS area_imovel_ha,
       t.codigo                  AS indicador,
       t.descricao               AS indicador_descricao,
       t.dimensao,
       t.unidade,
       t.unidade_simbolo,
       t.sentido,
       r.valor,
       CASE
           WHEN r.valor IS NULL OR t.limite_bom IS NULL OR t.limite_atencao IS NULL THEN NULL
           WHEN t.sentido = 'MENOR_MELHOR' THEN
               CASE WHEN r.valor <= t.limite_bom     THEN 'BOM'
                    WHEN r.valor <= t.limite_atencao THEN 'ATENCAO'
                    ELSE 'CRITICO' END
           ELSE
               CASE WHEN r.valor >= t.limite_bom     THEN 'BOM'
                    WHEN r.valor >= t.limite_atencao THEN 'ATENCAO'
                    ELSE 'CRITICO' END
       END                       AS faixa,
       r.area_afetada_ha,
       r.percentual_sobreposicao,
       -- Deficit de Reserva Legal (IRL) e passivo de APP (IAPP), em hectares (V086).
       r.valor_secundario_ha,
       r.indicador_versao_id,
       r.hash_versao,
       r.regra_calculo_versao,
       r.data_processamento,
       i.data_publicacao
  FROM imovel_vigente i
 CROSS JOIN ${goldSchema}.tipo_indicador t
 CROSS APPLY (
        SELECT ir.valor,
               ir.area_afetada_ha,
               ir.percentual_sobreposicao,
               ir.valor_secundario_ha,
               iv.id     AS indicador_versao_id,
               iv.hash_versao,
               rc.versao AS regra_calculo_versao,
               iv.data_processamento
          FROM ${goldSchema}.indicador_versao iv
          JOIN ${goldSchema}.indicador_resultado ir ON ir.indicador_versao_id = iv.id
          JOIN ${goldSchema}.regra_calculo rc ON rc.id = iv.regra_calculo_id
         WHERE iv.cod_imovel = i.cod_imovel
           AND ir.tipo_indicador_id = t.id
           AND iv.data_processamento <= i.data_publicacao
         ORDER BY iv.data_processamento DESC, iv.id DESC
         FETCH FIRST 1 ROW ONLY
       ) r
  -- LEFT JOIN de proposito: imovel com municipio ainda nao semeado na Silver continua
  -- saindo na API, so sem o nome (mesma escolha do portal).
  LEFT JOIN ${silverSchema}.municipio m ON m.cod_ibge = i.municipio_cod_ibge
 WHERE i.ordem_versao = 1
   AND t.ativo = 1;
