-- Favoritos da tela inicial: as telas que cada usuario marcou com a estrela.
--
-- Uma linha por tela favorita, na ordem em que o usuario quer ve-las. O usuario vem do
-- token do Keycloak ("sub", UUID) - nao ha tabela de usuario no Oracle (CLAUDE.md 9.39),
-- por isso texto e sem FK, como em log_auditoria (V075).
--
-- Guarda so a rota da tela (ex. /fontes), nao o titulo: o titulo e a permissao vem das
-- rotas do frontend, entao renomear uma tela nao deixa favorito desatualizado, e uma tela
-- que o usuario deixou de poder abrir simplesmente some da tela inicial.
CREATE TABLE ${controleSchema}.favorito_usuario (
    id          NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    usuario_id  VARCHAR2(64)  NOT NULL,
    rota        VARCHAR2(200) NOT NULL,
    ordem       NUMBER(3)     NOT NULL,
    data_add    TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT uq_favorito_usuario UNIQUE (usuario_id, rota)
);

CREATE INDEX ${controleSchema}.ix_favorito_usuario ON ${controleSchema}.favorito_usuario (usuario_id, ordem);
