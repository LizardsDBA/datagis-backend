-- Liberacao de token de acesso por usuario ("Criacao de token de acesso").
--
-- O token em si ainda nao existe: no futuro ele vai servir para consultar os dados da Gold
-- por um entry point (sem passar pela tela). Por enquanto esta tabela guarda so se o
-- administrador liberou ou nao a criacao de token para cada usuario; a pessoa ve o estado
-- na tela "Minha conta", mas so o administrador muda (tela de Usuarios).
--
-- Fica no Oracle e nao como atributo do usuario no Keycloak: o Keycloak 26 descarta
-- atributo que nao foi declarado no perfil de usuario dele, o que exigiria configuracao a
-- mais em cada ambiente. O usuario e o "sub" do token (UUID do Keycloak), texto e sem FK,
-- como em log_auditoria (V075) e favorito_usuario (V089). Sem linha = nao liberado.
CREATE TABLE ${controleSchema}.token_acesso_usuario (
    usuario_id    VARCHAR2(64)  PRIMARY KEY,
    habilitado    NUMBER(1)     DEFAULT 0 NOT NULL,
    alterado_por  VARCHAR2(200),
    data_alt      TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    CONSTRAINT ck_token_acesso_habilitado CHECK (habilitado IN (0, 1))
);
