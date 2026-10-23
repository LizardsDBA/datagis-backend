-- Antes e depois da alteracao no log de auditoria (Controle de Acesso).
--
-- O FiltroAuditoria continua sem ler o corpo da requisicao. Quem preenche estas colunas e o
-- proprio servico que alterou o dado (MudancaAuditada.registrar): ele le o registro antes,
-- salva e entrega a "fotografia" dos dois lados, com senha e segredo de fora.
--
-- Null nas duas = acao sem antes/depois (processo, como ingestao, ou acao feita antes desta
-- migration). Antes null e depois preenchido = cadastro novo.
--
-- A tabela continua somente insercao: o trigger da V075 so dispara em UPDATE/DELETE de
-- linha, ALTER TABLE nao passa por ele.
ALTER TABLE ${controleSchema}.log_auditoria ADD (
    dados_antes   CLOB CONSTRAINT ck_log_auditoria_antes_json CHECK (dados_antes IS JSON),
    dados_depois  CLOB CONSTRAINT ck_log_auditoria_depois_json CHECK (dados_depois IS JSON)
);
