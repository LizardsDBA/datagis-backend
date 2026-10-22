-- [EFEITO PARCIAL - resto ver V003] Esta migration fazia originalmente 2 coisas:
-- (1) ajustava a url_origem do CAR pra incluir o filtro de municipio; (2) cadastrava
-- ICMBio, PRODES, Focos de Calor IAT, Embargos IBAMA e INPE CSV Queimadas. Com a
-- reordenacao do catalogo de fontes, o V003 passou a ser o seed consolidado unico
-- de controle.fonte_dado - ja nasce com a URL certa e com todas as 18 fontes, SEM
-- precisar do ajuste (1) nem dos 5 inserts de (2) aqui.
--
-- Único efeito real que sobra: completar campo_geometria_lat/lon da fonte CSV do
-- INPE. Essas 2 colunas so existem a partir desta propria migration (ADD na V005,
-- que roda logo antes desta) - por isso o V003 (que roda ANTES da V005) nao pode
-- setar isso no INSERT. Resolvido aqui, por NOME.
UPDATE ${controleSchema}.fonte_dado
   SET campo_geometria_lat = 'lat', campo_geometria_lon = 'lon'
 WHERE nome = 'INPE (Queimadas) - Focos de calor, Brasil (mensal)';
