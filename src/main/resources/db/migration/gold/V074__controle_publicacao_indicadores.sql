-- Mesmo padrão de status/andamento/erro já usado pra publicação de camada de mapa
-- (gold.publicacao_camada_mapa/publicacao_camada_mapa_erro, V067) - agora pra
-- publicarTudo() recalcular indicadores de todos os imóveis, que até aqui não tinha
-- nenhum rastreamento (só devolvia o resultado no final, sem dar pra consultar
-- enquanto roda). Importante pra uma futura tela de acompanhamento de processos
-- tratar as duas etapas (indicadores + camadas de mapa) de forma uniforme.
CREATE TABLE ${goldSchema}.publicacao_indicadores (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    status            VARCHAR2(20) DEFAULT 'EM_ANDAMENTO' NOT NULL,
    total_estimado    NUMBER,
    total_processado  NUMBER DEFAULT 0 NOT NULL,
    total_sucesso     NUMBER DEFAULT 0 NOT NULL,
    total_falha       NUMBER DEFAULT 0 NOT NULL,
    mensagem_erro     VARCHAR2(4000),
    iniciado_em       TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    concluido_em      TIMESTAMP,
    CONSTRAINT ck_publicacao_indicadores_status
        CHECK (status IN ('EM_ANDAMENTO', 'CONCLUIDA', 'FALHOU'))
);

-- 1 linha por imóvel que falhou no recálculo - sempre com motivo (mesmo princípio
-- de publicacao_camada_mapa_erro: nunca so um log que ninguem olha).
CREATE TABLE ${goldSchema}.publicacao_indicadores_erro (
    id                         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    publicacao_indicadores_id  NUMBER NOT NULL
        REFERENCES ${goldSchema}.publicacao_indicadores(id),
    cod_imovel                 VARCHAR2(60),
    motivo                     VARCHAR2(500) NOT NULL,
    data_add                   TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX ${goldSchema}.ix_pub_indicadores_erro_exec
    ON ${goldSchema}.publicacao_indicadores_erro(publicacao_indicadores_id);
