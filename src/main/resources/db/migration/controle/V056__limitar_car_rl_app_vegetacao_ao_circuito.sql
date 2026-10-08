-- Achado ao vivo tentando ingerir pela primeira vez: o banco Oracle Free tem limite
-- RIGIDO de 12GB de dados (licenca, nao e config ajustavel) - ja estava em 13,7GB
-- (BRONZE sozinho com 9,18GB) antes mesmo de tentar estas 3 fontes. A primeira
-- tentativa de ingerir Reserva Legal (347.867 registros, estado inteiro) falhou com
-- ORA-12954 (banco cheio) depois de ~22min de streaming (o OutOfMemoryError de uma
-- tentativa anterior ja tinha sido corrigido separadamente - ver IngestaoGeoJsonExecutor).
--
-- Mesmo que o espaco coubesse, nao faria sentido pratico: a Silver hoje so tem os
-- ~4.608 imoveis do "circuito" de homologacao (Curitiba/Maringa/Ponta Grossa,
-- CLAUDE.md §5.2) - ingerir Reserva Legal/APP/Vegetacao Nativa pros outros ~545 mil
-- imoveis do estado inteiro deixaria >98% desses registros orfaos (sem imovel
-- correspondente na Silver pro calculo do indicador juntar via cod_imovel).
--
-- Solucao: mesmo filtro de circuito ja usado pro CAR (cod_imovel comeca com
-- "PR-{codigo_ibge}-"), aplicado via where= do ArcGIS REST - testado ao vivo contra
-- o servico real antes desta migration:
--   Reserva Legal:      347.867 -> 2.777 registros
--   APP Total:          381.962 -> 3.180 registros
--   Vegetacao Nativa:   393.153 -> 3.182 registros
-- Total ~9.1 mil registros, ordem de grandeza compativel com o espaco que sobra no
-- banco. Expandir o circuito depois (quando mais municipios entrarem na Silver) e
-- so editar esta URL de novo, mesmo principio de "lista de codigos e configuracao"
-- do §5.2 - nao precisa de migration de codigo.
UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/1/query?where=cod_imovel+LIKE+%27PR-4106902-%25%27+OR+cod_imovel+LIKE+%27PR-4115200-%25%27+OR+cod_imovel+LIKE+%27PR-4119905-%25%27&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - APP Total (CAR)';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/2/query?where=cod_imovel+LIKE+%27PR-4106902-%25%27+OR+cod_imovel+LIKE+%27PR-4115200-%25%27+OR+cod_imovel+LIKE+%27PR-4119905-%25%27&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - Reserva Legal (CAR)';

UPDATE ${controleSchema}.fonte_dado
   SET url_origem = 'https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer/3/query?where=cod_imovel+LIKE+%27PR-4106902-%25%27+OR+cod_imovel+LIKE+%27PR-4115200-%25%27+OR+cod_imovel+LIKE+%27PR-4119905-%25%27&outFields=*&f=geojson&outSR=4674'
 WHERE nome = 'IAT/PR - Vegetação Nativa (CAR)';
