-- Mapa de Cobertura (LIZARDS-35): views que o GeoServer publica + camada com o
-- contorno do Parana.
--
-- Por que views e nao a tabela direto: gold.feature_publicada guarda TODAS as versoes
-- de TODAS as camadas (V018). A versao valida de uma camada nunca e
-- MAX(versao_publicacao) direto - e a versao mais recente com status 'CONCLUIDA' em
-- gold.publicacao_camada_mapa (V067), pra uma publicacao que falhou no meio nunca
-- aparecer pela metade. A regra fica aqui, num lugar so, e o GeoServer so enxerga
-- "a camada vigente", sem saber de versao.
--
-- Uma view por camada (vw_mapa_<camada>) porque o proxy do backend libera camada por
-- nome, numa lista fechada. Camada nova no mapa = linha em gold.mapa_camada + 1 view
-- de 3 linhas aqui embaixo + metadado espacial dela.
--
-- O metadado espacial das views (MDSYS.SDO_GEOM_METADATA_TABLE) e exigido pelo
-- GeoServer pra reconhecer a coluna de geometria e o SRID. Mesmo bbox/tolerancia da
-- V020.

-- Contorno do Parana: base do mapa (mascara que esconde o que esta fora do estado e
-- limita o arraste). Publicado pelo GoldPublicacaoService como as outras camadas, a
-- partir da uniao dos 399 municipios da Silver - medido no Oracle local: 1 poligono
-- valido, sem buraco, 199.891 km2, ~14 s.
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('LIMITE_PR', 'Limite do Parana', 'BASE', 'POLIGONO', '#0f172a', -10);

-- Base comum: so as features da versao vigente (ultima CONCLUIDA) de cada camada.
CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_feature_vigente AS
SELECT f.id,
       m.codigo AS camada,
       f.cod_natural,
       f.rotulo,
       f.municipio_cod_ibge,
       f.versao_publicacao,
       f.geometria
  FROM ${goldSchema}.feature_publicada f
  JOIN ${goldSchema}.mapa_camada m ON m.id = f.mapa_camada_id
 WHERE f.versao_publicacao = (SELECT MAX(p.versao_publicacao)
                                FROM ${goldSchema}.publicacao_camada_mapa p
                               WHERE p.mapa_camada_id = f.mapa_camada_id
                                 AND p.status = 'CONCLUIDA');

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_limite_pr AS
SELECT id, cod_natural, rotulo, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'LIMITE_PR';

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_municipio AS
SELECT id, cod_natural, rotulo, municipio_cod_ibge, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'MUNICIPIO';

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_imovel AS
SELECT id, cod_natural, rotulo, municipio_cod_ibge, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'IMOVEL_RURAL';

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_area_protegida AS
SELECT id, cod_natural, rotulo, municipio_cod_ibge, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'AREA_PROTEGIDA';

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_embargo AS
SELECT id, cod_natural, rotulo, municipio_cod_ibge, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'EMBARGO';

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_desmatamento AS
SELECT id, cod_natural, rotulo, municipio_cod_ibge, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'DESMATAMENTO';

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_foco_calor AS
SELECT id, cod_natural, rotulo, municipio_cod_ibge, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'FOCO_CALOR';

INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (SDO_OWNER, SDO_TABLE_NAME, SDO_COLUMN_NAME, SDO_DIMINFO, SDO_SRID)
SELECT UPPER('${goldSchema}'), v.nome, 'GEOMETRIA',
       SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674
  FROM (SELECT 'VW_MAPA_LIMITE_PR' AS nome FROM dual
        UNION ALL SELECT 'VW_MAPA_MUNICIPIO' FROM dual
        UNION ALL SELECT 'VW_MAPA_IMOVEL' FROM dual
        UNION ALL SELECT 'VW_MAPA_AREA_PROTEGIDA' FROM dual
        UNION ALL SELECT 'VW_MAPA_EMBARGO' FROM dual
        UNION ALL SELECT 'VW_MAPA_DESMATAMENTO' FROM dual
        UNION ALL SELECT 'VW_MAPA_FOCO_CALOR' FROM dual) v;

COMMIT;
