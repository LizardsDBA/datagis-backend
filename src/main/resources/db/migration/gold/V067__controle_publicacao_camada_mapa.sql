-- Controle de execucao da publicacao de camadas de mapa (bloco 2), no mesmo
-- espirito de lote_ingestao/pipeline_execucao (CLAUDE.md §2.7) - status +
-- andamento + rastreabilidade de erro por registro, nao so log perdido.
--
-- Motivacao: publicar uma camada inteira (ex. DESMATAMENTO, 1.428 poligonos) numa
-- unica sessao Oracle pode estourar PGA_AGGREGATE_LIMIT (ORA-04036, achado ao vivo
-- - ver V065/V066). A correcao de verdade e processar em LOTES, cada lote numa
-- conexao JDBC nova (sessao Oracle nova, PGA reseta) - orquestrado pelo Java
-- (GoldPublicacaoService), nao mais 1 chamada monolitica. Isso levanta uma
-- exigencia de negocio: cada publicacao gera uma versao que e "absoluta" - nunca
-- pode ficar visivel pela metade se um lote no meio falhar. A tabela abaixo e o
-- mecanismo: o que e "valido pra mostrar" nunca e MAX(versao_publicacao) direto em
-- gold.feature_publicada - e a versao mais recente com status='CONCLUIDA' aqui.
-- Se um lote falhar, esta linha vira FALHOU, os registros orfaos da versao nova sao
-- apagados, e a versao anterior (ja CONCLUIDA) continua sendo a valida.
CREATE TABLE ${goldSchema}.publicacao_camada_mapa (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    mapa_camada_id    NUMBER NOT NULL REFERENCES ${goldSchema}.mapa_camada(id),
    versao_publicacao NUMBER NOT NULL,
    status            VARCHAR2(20) DEFAULT 'EM_ANDAMENTO' NOT NULL,
    total_estimado    NUMBER,
    total_processado  NUMBER DEFAULT 0 NOT NULL,
    total_publicado   NUMBER DEFAULT 0 NOT NULL,
    total_pulado      NUMBER DEFAULT 0 NOT NULL,
    mensagem_erro     VARCHAR2(4000),
    iniciado_em       TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
    concluido_em      TIMESTAMP,
    CONSTRAINT ck_publicacao_camada_status
        CHECK (status IN ('EM_ANDAMENTO', 'CONCLUIDA', 'FALHOU')),
    CONSTRAINT uq_publicacao_camada_versao UNIQUE (mapa_camada_id, versao_publicacao)
);

-- 1 linha por registro pulado ou que falhou dentro de uma execucao - sempre com
-- motivo, nunca so um DBMS_OUTPUT.PUT_LINE que ninguem le (o que a V044/V065/V066
-- faziam ate aqui).
CREATE TABLE ${goldSchema}.publicacao_camada_mapa_erro (
    id                        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    publicacao_camada_mapa_id NUMBER NOT NULL
        REFERENCES ${goldSchema}.publicacao_camada_mapa(id),
    origem_silver_id          NUMBER,
    identificador             VARCHAR2(200),
    motivo                    VARCHAR2(500) NOT NULL,
    data_add                  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE INDEX ${goldSchema}.ix_pub_camada_erro_exec
    ON ${goldSchema}.publicacao_camada_mapa_erro(publicacao_camada_mapa_id);
