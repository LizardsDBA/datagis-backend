-- Cache de calculo pra calcular_idesmat (pkg_calculo_ambiental) - LIZARDS otimizacao Gold.
--
-- Achado medindo o recalculo por imovel (docs/publicacao-gold-paralela.md, secao 3.2):
-- calcular_idesmat consome ~94% do tempo total de recalculo de 1 imovel (248 ms de
-- 264 ms, amostra de 30 imoveis reais), apesar de silver.desmatamento ter só 1.428
-- linhas (bem menos que silver.area_protegida, 8.361, que calcular_isap varre em
-- 9 ms/imovel com o mesmo padrao SDO_RELATE+cursor+SDO_INTERSECTION+dissolve).
--
-- Causa raiz 1 (vertices): media de vertices e MENOR que area_protegida (478 vs
-- 1.947), mas o MAXIMO e 317.235 vertices num unico poligono do PRODES - mesma classe
-- de problema do LIZARDS-50 (geometria de 634 mil ordenadas que travava
-- SDO_UTIL.SIMPLIFY por estourar PGA_AGGREGATE_LIMIT).
--
-- Causa raiz 2 (a decisiva - achada testando o cache so-simplificado e vendo que nao
-- resolveu sozinho): esse poligono monstro e um MultiPolygon fragmentado (manchas de
-- desmatamento acumuladas do PRODES, ano 2000) com MBR de ~180x130 km - maior que o
-- municipio de Maringa inteiro. O indice espacial e por FEICAO, nao por sub-elemento
-- do MultiPolygon - entao SDO_RELATE contra QUALQUER imovel cujo bounding box caia
-- dentro desse MBR gigante precisa do check exato contra a geometria inteira (mesmo
-- simplificada) pra so entao descobrir que nao intersecciona de verdade. Isso bateu
-- em quase todo o circuito de Maringa (1.843 imoveis) na 1a tentativa (so simplificar
-- vertice, sem decompor), explicando por que o ganho da 1a versao do cache nao bateu
-- com a melhoria medida isoladamente no PL/SQL.
--
-- Solucao (GoldPublicacaoService.prepararDesmatamentoOtimizado):
-- decompor cada MultiPolygon em poligonos individuais (SimplificadorGeometriaMapa.
-- decomporEsimplificar) ANTES de simplificar e cachear - cada parte ganha seu proprio
-- MBR apertado, devolvendo seletividade real ao indice. gold.desmatamento_otimizado e
-- so uma copia derivada/descartavel de silver.desmatamento (DELETE + repopula a cada
-- POST /api/gold/publicar, antes do loop por imovel), sem FK - a Silver continua a
-- fonte de verdade. desmatamento_id (nao-unico agora, 1:N com a Silver) +
-- fonte_dado_id/lote_ingestao_id copiados so pra registrar_resultado continuar com a
-- linhagem certa sem precisar voltar na Silver por linha.
CREATE TABLE ${goldSchema}.desmatamento_otimizado (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    desmatamento_id   NUMBER NOT NULL,
    fonte_dado_id     NUMBER,
    lote_ingestao_id  NUMBER,
    geometria         MDSYS.SDO_GEOMETRY
);

CREATE INDEX ${goldSchema}.ix_desmat_otim_origem ON ${goldSchema}.desmatamento_otimizado(desmatamento_id);

INSERT INTO MDSYS.SDO_GEOM_METADATA_TABLE (SDO_OWNER, SDO_TABLE_NAME, SDO_COLUMN_NAME, SDO_DIMINFO, SDO_SRID)
VALUES ('${goldSchema}', 'DESMATAMENTO_OTIMIZADO', 'GEOMETRIA',
        SDO_DIM_ARRAY(SDO_DIM_ELEMENT('X', -74, -28, 0.05), SDO_DIM_ELEMENT('Y', -34, 6, 0.05)), 4674);
COMMIT;

CREATE INDEX ${goldSchema}.ix_desmat_otim_geom ON ${goldSchema}.desmatamento_otimizado(geometria)
    INDEXTYPE IS MDSYS.SPATIAL_INDEX;
