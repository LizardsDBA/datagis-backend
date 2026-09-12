-- Rastreamento da última execução de cada fonte, pra mostrar em tela (tempo gasto,
-- se deu erro, qual foi o erro) sem precisar vasculhar log de container. Ver achado
-- real: IngestaoGeoJsonExecutor estourou memória (OutOfMemoryError) em algumas
-- execuções, e até agora isso só aparecia no log do backend, nunca chegava na tela.
ALTER TABLE ${controleSchema}.fonte_dado ADD (
    ultima_duracao_ms      NUMBER,        -- em milissegundos
    ultimo_status_execucao VARCHAR2(20),  -- SUCESSO | ERRO (nulo = nunca executou)
    ultimo_erro_mensagem   VARCHAR2(2000) -- só preenchido quando ultimo_status_execucao = ERRO
);
