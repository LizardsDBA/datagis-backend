#!/bin/bash
# Executado UMA VEZ pelo entrypoint do gvenzl/oracle-free, na primeira
# inicializacao (datafiles vazios), conectado como SYS. Quando este script
# roda, o usuario definido em APP_USER/APP_USER_PASSWORD ja foi criado no PDB
# FREEPDB1 pelo proprio entrypoint da imagem (com o papel DB_DEVELOPER_ROLE).
#
# Design (ver CLAUDE.md §2.1): 7 schemas, 1 unico usuario de conexao.
#   - APP_USER (definido no .env como CONTROLE) = schema de controle E e quem
#     o Spring Boot usa para conectar no banco.
#   - BRONZE, SILVER, GOLD, QUARENTENA, QUARTZ, BATCH sao criados aqui como
#     usuarios "donos de schema" sem uso de login direto — so existem para
#     possuir tabelas. O APP_USER recebe privilegios ANY para criar/ler/
#     escrever objetos QUALIFICADOS nesses schemas (ex.: bronze.registro_bruto).
#   - Adequado para DEV/academico. Em um ambiente real isso seria restrito
#     por schema com roles dedicados em vez de privilegios ANY.
set -euo pipefail

: "${APP_USER:?defina APP_USER no .env}"
: "${SCHEMA_PASSWORD:?defina SCHEMA_PASSWORD no .env}"

sqlplus -s "/ as sysdba" <<EOSQL
WHENEVER SQLERROR EXIT SQL.SQLCODE
ALTER SESSION SET CONTAINER = FREEPDB1;

CREATE USER bronze     IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER silver     IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER gold       IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER quarentena IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER quartz     IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;
CREATE USER batch      IDENTIFIED BY "${SCHEMA_PASSWORD}" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS;

GRANT CREATE ANY TABLE, ALTER ANY TABLE, DROP ANY TABLE                      TO ${APP_USER};
GRANT SELECT ANY TABLE, INSERT ANY TABLE, UPDATE ANY TABLE, DELETE ANY TABLE TO ${APP_USER};
GRANT CREATE ANY INDEX, CREATE ANY SEQUENCE, CREATE ANY TRIGGER, CREATE ANY VIEW TO ${APP_USER};

EXIT;
EOSQL

echo "[oracle-init] schemas bronze/silver/gold/quarentena/quartz/batch criados; privilegios ANY concedidos a ${APP_USER} (schema controle)."
