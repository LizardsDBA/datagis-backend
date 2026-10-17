-- Unidade de medida de cada tipo de indicador.
--
-- Seis dos sete sao percentuais, o IFC nao: a pkg_calculo_ambiental (V045) calcula
-- densidade = contagem de focos / area do imovel * 1000, ou seja focos por mil
-- hectares, e grava NULL no percentual do resultado de proposito. A pag. 6 do PDF do
-- desafio confirma: "focos detectados dentro do imovel / area do imovel x 1.000
-- hectares".
--
-- Sem essa informacao no catalogo a tela desenha a barra do IFC como se o valor fosse
-- porcentagem, e a legenda "valores de 0 a 100" fica errada pra ele. O comentario da
-- V045 documenta a unidade pra quem le o SQL, mas nao serve pra aplicacao decidir como
-- exibir o numero - por isso vira coluna.
--
-- unidade_simbolo guarda o texto exibido ao lado do valor, pra indicador novo nao
-- exigir alteracao de frontend. DEFAULT no ALTER pra indicador cadastrado depois pela
-- tela de administracao nascer como percentual, que e o caso de 6 dos 7 atuais.

ALTER TABLE ${goldSchema}.tipo_indicador ADD (
    unidade         VARCHAR2(20) DEFAULT 'PERCENTUAL',
    unidade_simbolo VARCHAR2(30) DEFAULT '%'
);

UPDATE ${goldSchema}.tipo_indicador SET unidade = 'PERCENTUAL', unidade_simbolo = '%' WHERE codigo = 'ICV';
UPDATE ${goldSchema}.tipo_indicador SET unidade = 'PERCENTUAL', unidade_simbolo = '%' WHERE codigo = 'IRL';
UPDATE ${goldSchema}.tipo_indicador SET unidade = 'PERCENTUAL', unidade_simbolo = '%' WHERE codigo = 'IAPP';
UPDATE ${goldSchema}.tipo_indicador SET unidade = 'PERCENTUAL', unidade_simbolo = '%' WHERE codigo = 'ISAP';
UPDATE ${goldSchema}.tipo_indicador SET unidade = 'PERCENTUAL', unidade_simbolo = '%' WHERE codigo = 'IAE';
UPDATE ${goldSchema}.tipo_indicador SET unidade = 'PERCENTUAL', unidade_simbolo = '%' WHERE codigo = 'IDESMAT';

UPDATE ${goldSchema}.tipo_indicador
   SET unidade = 'DENSIDADE', unidade_simbolo = 'focos/1.000 ha'
 WHERE codigo = 'IFC';

-- Rede de seguranca: com o DEFAULT acima nenhuma linha fica nula, mas se o ALTER rodar
-- num Oracle que nao preencha o default em linha existente, isto cobre.
UPDATE ${goldSchema}.tipo_indicador
   SET unidade = 'PERCENTUAL', unidade_simbolo = '%'
 WHERE unidade IS NULL;

ALTER TABLE ${goldSchema}.tipo_indicador ADD CONSTRAINT ck_tipo_indicador_unidade
    CHECK (unidade IN ('PERCENTUAL', 'DENSIDADE'));