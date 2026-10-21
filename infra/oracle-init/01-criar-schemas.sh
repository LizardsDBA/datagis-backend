#!/bin/bash
# Roda uma vez so, na primeira inicializacao do container Oracle (banco vazio),
# conectado como SYS.
#
# [ATUALIZADO - troca pra Oracle Enterprise Edition, ver docs/tutorial-oracle-
# enterprise-dev-local.md] A imagem gvenzl/oracle-free criava o APP_USER
# automaticamente a partir de APP_USER/APP_USER_PASSWORD - a imagem oficial da
# Oracle (container-registry.oracle.com/database/enterprise) NAO tem esse
# mecanismo, entao agora este script tambem cria o APP_USER.
#
# Ideia: 7 schemas, 1 usuario de conexao so.
#   - APP_USER (CONTROLE no .env) e o schema de controle e tambem quem o Spring
#     usa pra conectar no banco.
#   - BRONZE, SILVER, GOLD, QUARENTENA, QUARTZ, BATCH sao criados aqui so pra
#     serem donos de tabela, sem login. O APP_USER ganha privilegio ANY pra
#     criar/ler/escrever nesses schemas (ex.: bronze.registro_bruto).
#   - Isso e ok pra projeto de faculdade. Num sistema de producao cada schema
#     teria seu proprio usuario e permissao restrita.
set -euo pipefail

: "${APP_USER:?defina APP_USER no .env}"
: "${APP_USER_PASSWORD:?defina APP_USER_PASSWORD no .env}"
: "${SCHEMA_PASSWORD:?defina SCHEMA_PASSWORD no .env}"

sqlplus -s "/ as sysdba" <<EOSQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
-- PGA_AGGREGATE_LIMIT default (2G) nao basta pra construir o indice espacial
-- (CREATE INDEX ... INDEXTYPE IS MDSYS.SPATIAL_INDEX, V049) de silver.area_protegida
-- (16 mil poligonos, alguns nacionais complexos do MMA/SFB) - deu ORA-04036 na
-- pratica. O limite e por container: precisa subir no CDB\$ROOT ANTES de poder subir
-- no PDB (senao da ORA-56747, "o limite do PDB nao pode exceder o do CDB\$ROOT").
-- Setado aqui (nao so via ALTER SYSTEM manual) pra sobreviver a um reset de volume.
ALTER SYSTEM SET PGA_AGGREGATE_LIMIT = 8G SCOPE = BOTH;

ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SYSTEM SET PGA_AGGREGATE_LIMIT = 8G SCOPE = BOTH;

-- A imagem Enterprise Edition nao cria o usuario de conexao do Spring
-- automaticamente (diferente da gvenzl/oracle-free, que criava via
-- APP_USER/APP_USER_PASSWORD) - criado aqui na mao. CONNECT+RESOURCE cobre
-- CREATE SESSION e criar objeto (tabela/sequence/procedure/trigger/view) no
-- PROPRIO schema (CONTROLE); os GRANT ... ANY ... abaixo cobrem os outros 6
-- schemas.
CREATE USER ${APP_USER} IDENTIFIED BY "${APP_USER_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
GRANT CONNECT, RESOURCE TO ${APP_USER};
GRANT UNLIMITED TABLESPACE TO ${APP_USER};

CREATE USER bronze     IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER silver     IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER gold       IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER quarentena IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER quartz     IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER batch      IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;

GRANT CREATE ANY TABLE, ALTER ANY TABLE, DROP ANY TABLE                      TO ${APP_USER};
GRANT SELECT ANY TABLE, INSERT ANY TABLE, UPDATE ANY TABLE, DELETE ANY TABLE TO ${APP_USER};
GRANT CREATE ANY INDEX, CREATE ANY SEQUENCE, CREATE ANY TRIGGER, CREATE ANY VIEW TO ${APP_USER};
-- Toda tabela com IDENTITY cria uma sequence escondida no schema dono dela.
-- Sem SELECT ANY SEQUENCE, criar tabela com IDENTITY fora do schema CONTROLE
-- da erro (testamos e confirmamos isso).
GRANT SELECT ANY SEQUENCE TO ${APP_USER};
-- Procedures/packages da camada Gold (Silver->Gold em PL/SQL, docs/gold-procedures-
-- calculo-publicacao.md). CREATE ANY PROCEDURE deixa o Flyway (conectado como
-- CONTROLE) criar o pacote dentro do schema GOLD; mas o pacote e DONO pelo GOLD, e
-- PL/SQL definer's rights roda com o privilegio de quem é o DONO do codigo, nao de
-- quem chama - por isso o GOLD tambem precisa do seu proprio SELECT ANY TABLE pra
-- conseguir ler silver.* de dentro de uma procedure (testado e confirmado: sem isso
-- da ORA-00942 mesmo com CONTROLE tendo SELECT ANY TABLE).
GRANT CREATE ANY PROCEDURE, DROP ANY PROCEDURE, EXECUTE ANY PROCEDURE TO ${APP_USER};
-- Tipos SQL de colecao (ex.: TABLE OF SDO_GEOMETRY) pra passar listas de geometria/
-- fonte entre SQL e PL/SQL - um tipo PL/SQL declarado dentro de uma package nao e
-- visivel pro mecanismo TABLE() do SQL, precisa ser um CREATE TYPE de verdade.
GRANT CREATE ANY TYPE, DROP ANY TYPE, EXECUTE ANY TYPE TO ${APP_USER};
GRANT SELECT ANY TABLE TO gold;
-- DBMS_CRYPTO (hash_versao, mesmo algoritmo SHA-256 do hash_conteudo em Java) e
-- UTL_I18N nao sao executaveis por PUBLIC nesta imagem - sem este grant, qualquer
-- package do GOLD que chame DBMS_CRYPTO.HASH falha pra compilar com
-- "PLS-00201: identifier DBMS_CRYPTO must be declared" (confirmado na pratica).
GRANT EXECUTE ON DBMS_CRYPTO TO gold;
GRANT EXECUTE ON UTL_I18N TO gold;
-- Nao existe "REFERENCES ANY TABLE" no Oracle. Pra fazer FK de um schema pro
-- outro (ex.: bronze -> controle) precisa de GRANT REFERENCES na tabela,
-- feito pelo dono dela. Isso fica nas migrations do Flyway, nao aqui, porque
-- aqui ainda nao existe tabela nenhuma.

-- Indice espacial (V049, silver/V049__indices_espaciais_calculo.sql): CREATE INDEX
-- ... INDEXTYPE IS MDSYS.SPATIAL_INDEX roda rotina ODCI interna que cria
-- tabela/sequence proprias do indice R-tree COMO SE FOSSE O DONO DO SCHEMA (SILVER)
-- fazendo isso - "CREATE ANY TABLE/SEQUENCE" concedido ao CONTROLE nao serve, porque
-- quem precisa do privilegio ali e o SILVER em si, direto (nao "ANY"). Sem isso dá
-- ORA-01031/ORA-29855 mesmo com CONTROLE tendo privilegio de sobra (confirmado na
-- pratica). O mesmo vale pra DROP (precisou conectar como SILVER de verdade pra
-- dropar um indice de dominio que falhou no meio - DROP ANY INDEX do CONTROLE nao
-- bastou). CREATE SESSION fica concedido permanentemente pra SILVER por simplicidade
-- (projeto academico) - permite login direto quando for preciso recriar/ajustar um
-- indice espacial manualmente.
GRANT CREATE TABLE, CREATE SEQUENCE, UNLIMITED TABLESPACE, CREATE SESSION TO silver;
GRANT DROP ANY INDEX TO ${APP_USER};
-- Mesma exigencia pro indice espacial da Gold (V080, gold.feature_publicada - mapa
-- de cobertura, LIZARDS-35). Sem CREATE SESSION: ninguem precisa logar como GOLD.
-- Banco criado antes desta linha: rodar o GRANT abaixo uma vez como SYS (ver V080).
GRANT CREATE TABLE, CREATE SEQUENCE, UNLIMITED TABLESPACE TO gold;

EXIT;
EOSQL

echo "[oracle-init] schemas criados e privilegios concedidos a ${APP_USER}."
