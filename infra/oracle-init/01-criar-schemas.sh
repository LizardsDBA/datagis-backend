#!/bin/bash
# Roda uma vez so, na primeira inicializacao do container Oracle (banco vazio),
# conectado como SYS. Nesse ponto o usuario do APP_USER/APP_USER_PASSWORD ja foi
# criado no PDB pelo proprio entrypoint da imagem.
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
-- Toda tabela com IDENTITY cria uma sequence escondida no schema dono dela.
-- Sem SELECT ANY SEQUENCE, criar tabela com IDENTITY fora do schema CONTROLE
-- da erro (testamos e confirmamos isso).
GRANT SELECT ANY SEQUENCE TO ${APP_USER};
-- Nao existe "REFERENCES ANY TABLE" no Oracle. Pra fazer FK de um schema pro
-- outro (ex.: bronze -> controle) precisa de GRANT REFERENCES na tabela,
-- feito pelo dono dela. Isso fica nas migrations do Flyway, nao aqui, porque
-- aqui ainda nao existe tabela nenhuma.

EXIT;
EOSQL

echo "[oracle-init] schemas criados e privilegios concedidos a ${APP_USER}."
