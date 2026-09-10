-- View unificada das "restricoes territoriais" que se cruzam com o perimetro do
-- imovel pra calcular indicador. Os calculadores de ISAP/IAE/IDesmat leem esta view,
-- nao as 3 tabelas separadas. Ver docs/modelagem-silver-gold-quarentena.md secao 3.4.

CREATE OR REPLACE VIEW ${silverSchema}.vw_restricao_territorial AS
SELECT
    'AREA_PROTEGIDA'              AS origem_tabela,
    ap.id                        AS origem_id,
    tap.codigo                   AS tipo_restricao,       -- ex.: TERRA_INDIGENA, UC_PROTECAO_INTEGRAL
    cap.codigo                   AS subtipo_restricao,    -- ex.: PARNA, TI_TRADICIONALMENTE_OCUPADA
    ap.nome                      AS descricao,
    ap.geometria                 AS geometria,
    ap.area_ha                   AS area_ha,
    ap.data_competencia          AS data_competencia,
    ap.fonte_dado_id             AS fonte_dado_id,
    ap.lote_ingestao_id          AS lote_ingestao_id
FROM ${silverSchema}.area_protegida ap
JOIN ${silverSchema}.tipo_area_protegida tap ON tap.id = ap.tipo_area_protegida_id
LEFT JOIN ${silverSchema}.categoria_area_protegida cap ON cap.id = ap.categoria_area_protegida_id
UNION ALL
SELECT
    'EMBARGO_AMBIENTAL'          AS origem_tabela,
    e.id                        AS origem_id,
    'EMBARGO'                   AS tipo_restricao,
    o.codigo                    AS subtipo_restricao,     -- IBAMA / ICMBIO
    e.nome_autuado              AS descricao,
    e.geometria                 AS geometria,
    e.area_embargada_ha         AS area_ha,
    e.data_embargo              AS data_competencia,
    e.fonte_dado_id             AS fonte_dado_id,
    e.lote_ingestao_id          AS lote_ingestao_id
FROM ${silverSchema}.embargo_ambiental e
JOIN ${silverSchema}.orgao o ON o.id = e.orgao_id
UNION ALL
SELECT
    'DESMATAMENTO'              AS origem_tabela,
    d.id                       AS origem_id,
    'DESMATAMENTO'             AS tipo_restricao,
    cp.codigo                  AS subtipo_restricao,
    'PRODES ' || d.ano         AS descricao,
    d.geometria                AS geometria,
    d.area_ha                  AS area_ha,
    d.data_competencia         AS data_competencia,
    d.fonte_dado_id            AS fonte_dado_id,
    d.lote_ingestao_id         AS lote_ingestao_id
FROM ${silverSchema}.desmatamento d
LEFT JOIN ${silverSchema}.classe_prodes cp ON cp.id = d.classe_prodes_id;
