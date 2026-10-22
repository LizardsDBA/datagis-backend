-- Log de auditoria das acoes feitas na API (LIZARDS-48, RN025 do DRN).
--
-- Uma linha por requisicao que ALTERA alguma coisa (POST/PUT/PATCH/DELETE), gravada pelo
-- FiltroAuditoria do backend - inclusive as recusadas (401/403) e as que deram erro, que
-- tambem interessam a quem audita. Leitura (GET) nao entra: viraria ruido.
--
-- Quem fez: o usuario vem do token do Keycloak (nao ha tabela de usuario no Oracle -
-- CLAUDE.md 9.39), por isso id/login sao texto, sem FK.
--
-- O corpo da requisicao NAO e gravado de proposito: tem senha temporaria no cadastro de
-- usuario e payload de geometria com MB na quarentena.
CREATE TABLE ${controleSchema}.log_auditoria (
    id              NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    data_hora       TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    usuario_id      VARCHAR2(64),               -- "sub" do token (UUID do Keycloak); null = sem login
    usuario_login   VARCHAR2(200),              -- preferred_username, ex. "maria.silva"
    perfis          VARCHAR2(1000),             -- perfis do usuario no momento da acao
    metodo          VARCHAR2(10)   NOT NULL,    -- POST | PUT | PATCH | DELETE
    rota            VARCHAR2(1000) NOT NULL,    -- caminho real chamado (/api/quarentena/lotes/45/liberar)
    rota_padrao     VARCHAR2(500),              -- molde do endpoint (/api/quarentena/lotes/{loteId}/liberar)
    parametros      VARCHAR2(2000),             -- query string, se houver
    acao            VARCHAR2(200),              -- descricao legivel ("Liberar lote para a Silver")
    status_http     NUMBER(3)      NOT NULL,
    sucesso         NUMBER(1)      NOT NULL,    -- 1 = status 2xx/3xx
    duracao_ms      NUMBER,
    ip_origem       VARCHAR2(64)
);

CREATE INDEX ${controleSchema}.ix_log_auditoria_data ON ${controleSchema}.log_auditoria (data_hora);
CREATE INDEX ${controleSchema}.ix_log_auditoria_login ON ${controleSchema}.log_auditoria (usuario_login);

-- Append-only (RN025): nem o proprio backend consegue alterar ou apagar o historico.
-- Trigger em vez de so revogar UPDATE/DELETE porque o usuario da aplicacao (CONTROLE) e o
-- DONO da tabela - dono nao perde privilegio por REVOKE.
CREATE OR REPLACE TRIGGER ${controleSchema}.trg_log_auditoria_somente_insercao
BEFORE UPDATE OR DELETE ON ${controleSchema}.log_auditoria
BEGIN
    RAISE_APPLICATION_ERROR(-20025, 'log_auditoria e somente insercao (RN025): UPDATE e DELETE nao sao permitidos');
END;
/
