-- Mapa de Cobertura (LIZARDS-35): 3 camadas que faltavam - Reserva Legal, APP
-- declarada e Vegetacao nativa declarada do CAR (silver.reserva_legal/app_declarada/
-- vegetacao_nativa_declarada, V054). Os indicadores IRL/IAPP/ICV ja calculam de verdade
-- contra esse dado (V072/V073) - o que faltava era so a camada de MAPA: sem view nem
-- linha em mapa_camada, a tela mostrava "Camada ainda nao publicada no mapa" pros 3
-- (CLAUDE.md §9.45, "[EM ABERTO]").
--
-- Mesmo padrao da V080: view fina sobre vw_mapa_feature_vigente, filtrada por codigo
-- de camada, + metadado espacial (MDSYS.SDO_GEOM_METADATA_TABLE) pro GeoServer.
--
-- Sem municipio_cod_ibge: as 3 tabelas Silver de origem nao tem FK pra municipio (um
-- imovel pode ter varios fragmentos de RL/APP/vegetacao nativa, nao contiguos - por
-- isso tambem nao ha FK pra imovel_rural, so o atributo cod_imovel em texto, V054).
-- Mesmo padrao ja usado em AREA_PROTEGIDA (V080), que tambem nao tem essa coluna.
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('RESERVA_LEGAL', 'Reserva Legal declarada (CAR)', 'TEMATICA', 'POLIGONO', '#0d9488', 42);
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('APP_DECLARADA', 'APP declarada (CAR)', 'TEMATICA', 'POLIGONO', '#0891b2', 44);
INSERT INTO ${goldSchema}.mapa_camada (codigo, rotulo, tipo_camada, geometria_tipo, cor_padrao, ordem_z)
    VALUES ('VEGETACAO_NATIVA', 'Vegetação nativa declarada (CAR)', 'TEMATICA', 'POLIGONO', '#65a30d', 46);

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_reserva_legal AS
SELECT id, cod_natural, rotulo, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'RESERVA_LEGAL';

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_app_declarada AS
SELECT id, cod_natural, rotulo, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'APP_DECLARADA';

CREATE OR REPLACE VIEW ${goldSchema}.vw_mapa_vegetacao_nativa AS
SELECT id, cod_natural, rotulo, geometria
  FROM ${goldSchema}.vw_mapa_feature_vigente WHERE camada = 'VEGETACAO_NATIVA';

INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (SDO_OWNER, SDO_TABLE_NAME, SDO_COLUMN_NAME, SDO_DIMINFO, SDO_SRID)
SELECT UPPER('${goldSchema}'), v.nome, 'GEOMETRIA',
       SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674
  FROM (SELECT 'VW_MAPA_RESERVA_LEGAL' AS nome FROM dual
        UNION ALL SELECT 'VW_MAPA_APP_DECLARADA' FROM dual
        UNION ALL SELECT 'VW_MAPA_VEGETACAO_NATIVA' FROM dual) v;

COMMIT;
