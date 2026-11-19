-- API para sistemas externos (LIZARDS-57, US12): view dos imoveis que a API
-- /api/externo/v1 le.
--
-- Por que view e nao as tabelas direto: a API e um contrato com quem esta fora do
-- projeto. As tabelas da Gold mudam conforme o calculo evolui (a V086 ja criou coluna nova
-- em imovel_publicado); a view absorve essas mudancas e o consumidor externo nao quebra.
-- Mesmo motivo das vw_mapa_* (V080) para o GeoServer.
--
-- Uma linha por imovel, com os 7 indicadores em colunas (o mesmo formato de
-- gold.imovel_publicado e da tela de Indicadores). O nome do municipio vem da camada de
-- mapa MUNICIPIO, para a API ler so a Gold. As outras camadas (area protegida, embargo,
-- desmatamento, focos, reserva legal, APP, vegetacao nativa) a API le pelas vw_mapa_*
-- que ja existem, sem view nova.
--
-- Dois cuidados, os mesmos do portal, para a API nunca mostrar numero diferente da tela:
--   1. imovel_publicado guarda uma linha por versao publicada (historico de auditoria):
--      so a mais recente de cada imovel entra (data_publicacao, id para desempatar -
--      mesmo criterio do ConsultaIndicadoresService.versaoAtualDeCadaImovel). Sem isso,
--      imovel republicado sai repetido.
--   2. feature_publicada guarda todas as versoes de todas as camadas (so a versao que
--      falha e apagada). O municipio vem de vw_mapa_municipio, que ja filtra a ultima
--      publicacao CONCLUIDA da camada (V080). Ler feature_publicada direto repetiria cada
--      imovel uma vez por publicacao da camada. A camada e escolhida pelo codigo
--      'MUNICIPIO' dentro da view, nao pelo id, que e gerado pelo banco.
--
-- LEFT JOIN de proposito: imovel cujo municipio nao esta na camada publicada continua
-- saindo na API, so sem o nome.

CREATE OR REPLACE VIEW ${goldSchema}.vw_api_imovel AS
WITH imovel_vigente AS (
    SELECT p.*,
           ROW_NUMBER() OVER (PARTITION BY p.cod_imovel
                              ORDER BY p.data_publicacao DESC, p.id DESC) AS ordem_versao
      FROM ${goldSchema}.imovel_publicado p
)
SELECT i.cod_imovel,
       i.municipio_cod_ibge,
       m.rotulo AS municipio,
       i.area_ha,
       i.geometria,
       i.valor_icv,
       i.valor_irl,
       i.valor_irl_deficit_ha,
       i.valor_iapp,
       i.valor_iapp_passivo_ha,
       i.valor_isap,
       i.valor_iae,
       i.valor_idesmat,
       i.valor_ifc,
       i.data_publicacao
  FROM imovel_vigente i
  LEFT JOIN ${goldSchema}.vw_mapa_municipio m ON m.municipio_cod_ibge = i.municipio_cod_ibge
 WHERE i.ordem_versao = 1;
