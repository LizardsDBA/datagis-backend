-- Suporte a ingestao via upload manual (ex.: Shapefile do CAR baixado pelo usuario em
-- consultapublica.car.gov.br - a pagina e uma SPA com sessao, nao da pra automatizar o
-- download; o usuario baixa no navegador dele e sobe o .zip pro sistema).
--
-- Guarda o ENDERECO do ultimo arquivo enviado (o que ArmazenamentoArquivoBruto.salvar
-- devolve), nao a URL - fontes UPLOAD_MANUAL nao tem url_origem (ja documentado assim
-- desde o inicio, coluna fica nula pra elas).
ALTER TABLE ${controleSchema}.fonte_dado ADD (
    caminho_arquivo_upload VARCHAR2(500)
);
