-- Novos tipos de erro de validacao para triagem Bronze -> Silver / Quarentena.
-- Fornecem feedback especifico e detalhado para a tela de gestao do analista.
-- Ver docs/modelagem-silver-gold-quarentena.md secao 4.

INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao)
VALUES ('CPF_CNPJ_INVALIDO', 'Digitos verificadores ou formato de CPF/CNPJ invalido');

INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao)
VALUES ('VALOR_ABAIXO_DO_MINIMO', 'Valor numerico menor que o valor minimo parametrizado');

INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao)
VALUES ('CAMPO_EM_BRANCO', 'Campo preenchido exclusivamente com espacos ou falso nulo (NULL, N/A, -)');

INSERT INTO ${quarentenaSchema}.tipo_erro_quarentena (codigo, descricao)
VALUES ('GEOMETRIA_TIPO_INCOMPATIVEL', 'Tipo primitivo de geometria incompativel com a entidade alvo');
