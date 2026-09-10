-- Tabelas de referencia da Silver: toda coluna "enum" das tabelas de fato aponta
-- pra uma destas por FK (_id). Todas ja nascem semeadas com o que apareceu nos
-- dados reais + o dominio conhecido. Ver docs/modelagem-silver-gold-quarentena.md 3.1.
--
-- Estrutura padrao: id IDENTITY PK | codigo UNIQUE | descricao | ativo | data_add.

-- ============================================================
-- Referencias compartilhadas (usadas por mais de uma tabela de fato)
-- ============================================================

CREATE TABLE ${silverSchema}.orgao (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('SFB_SICAR', 'Servico Florestal Brasileiro / SICAR');
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('IBAMA', 'Instituto Brasileiro do Meio Ambiente e dos Recursos Naturais Renovaveis');
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('ICMBIO', 'Instituto Chico Mendes de Conservacao da Biodiversidade');
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('FUNAI', 'Fundacao Nacional dos Povos Indigenas');
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('INCRA', 'Instituto Nacional de Colonizacao e Reforma Agraria');
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('IBGE', 'Instituto Brasileiro de Geografia e Estatistica');
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('INPE', 'Instituto Nacional de Pesquisas Espaciais');
INSERT INTO ${silverSchema}.orgao (codigo, descricao) VALUES ('IAT_PR', 'Instituto Agua e Terra (Parana)');

CREATE TABLE ${silverSchema}.bioma (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.bioma (codigo, descricao) VALUES ('MATA_ATLANTICA', 'Mata Atlantica');
INSERT INTO ${silverSchema}.bioma (codigo, descricao) VALUES ('CERRADO', 'Cerrado');
INSERT INTO ${silverSchema}.bioma (codigo, descricao) VALUES ('CAATINGA', 'Caatinga');
INSERT INTO ${silverSchema}.bioma (codigo, descricao) VALUES ('AMAZONIA', 'Amazonia');
INSERT INTO ${silverSchema}.bioma (codigo, descricao) VALUES ('PAMPA', 'Pampa');
INSERT INTO ${silverSchema}.bioma (codigo, descricao) VALUES ('PANTANAL', 'Pantanal');

CREATE TABLE ${silverSchema}.satelite (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.satelite (codigo, descricao) VALUES ('AQUA_M-T', 'Aqua (MODIS, passagem da tarde) - satelite de referencia do INPE Queimadas');
INSERT INTO ${silverSchema}.satelite (codigo, descricao) VALUES ('TERRA_M-M', 'Terra (MODIS, passagem da manha)');
INSERT INTO ${silverSchema}.satelite (codigo, descricao) VALUES ('GOES-16', 'GOES-16');
INSERT INTO ${silverSchema}.satelite (codigo, descricao) VALUES ('NOAA-20', 'NOAA-20 (VIIRS)');
INSERT INTO ${silverSchema}.satelite (codigo, descricao) VALUES ('NPP-375', 'Suomi NPP (VIIRS 375m)');
INSERT INTO ${silverSchema}.satelite (codigo, descricao) VALUES ('LANDSAT7', 'Landsat 7');
INSERT INTO ${silverSchema}.satelite (codigo, descricao) VALUES ('LANDSAT8', 'Landsat 8');

CREATE TABLE ${silverSchema}.sensor (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.sensor (codigo, descricao) VALUES ('ETM', 'Enhanced Thematic Mapper Plus (Landsat 7)');
INSERT INTO ${silverSchema}.sensor (codigo, descricao) VALUES ('TM', 'Thematic Mapper (Landsat 5)');
INSERT INTO ${silverSchema}.sensor (codigo, descricao) VALUES ('OLI', 'Operational Land Imager (Landsat 8)');
INSERT INTO ${silverSchema}.sensor (codigo, descricao) VALUES ('MODIS', 'Moderate Resolution Imaging Spectroradiometer');
INSERT INTO ${silverSchema}.sensor (codigo, descricao) VALUES ('VIIRS', 'Visible Infrared Imaging Radiometer Suite');

CREATE TABLE ${silverSchema}.unidade_federativa (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(2)   NOT NULL UNIQUE,   -- sigla
    descricao VARCHAR2(60)  NOT NULL,
    cod_ibge  NUMBER(2)     NOT NULL UNIQUE,
    regiao    VARCHAR2(20)  NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('AC', 'Acre', 12, 'NORTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('AL', 'Alagoas', 27, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('AP', 'Amapa', 16, 'NORTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('AM', 'Amazonas', 13, 'NORTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('BA', 'Bahia', 29, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('CE', 'Ceara', 23, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('DF', 'Distrito Federal', 53, 'CENTRO_OESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('ES', 'Espirito Santo', 32, 'SUDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('GO', 'Goias', 52, 'CENTRO_OESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('MA', 'Maranhao', 21, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('MT', 'Mato Grosso', 51, 'CENTRO_OESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('MS', 'Mato Grosso do Sul', 50, 'CENTRO_OESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('MG', 'Minas Gerais', 31, 'SUDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('PA', 'Para', 15, 'NORTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('PB', 'Paraiba', 25, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('PR', 'Parana', 41, 'SUL');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('PE', 'Pernambuco', 26, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('PI', 'Piaui', 22, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('RJ', 'Rio de Janeiro', 33, 'SUDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('RN', 'Rio Grande do Norte', 24, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('RS', 'Rio Grande do Sul', 43, 'SUL');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('RO', 'Rondonia', 11, 'NORTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('RR', 'Roraima', 14, 'NORTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('SC', 'Santa Catarina', 42, 'SUL');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('SP', 'Sao Paulo', 35, 'SUDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('SE', 'Sergipe', 28, 'NORDESTE');
INSERT INTO ${silverSchema}.unidade_federativa (codigo, descricao, cod_ibge, regiao) VALUES ('TO', 'Tocantins', 17, 'NORTE');

-- ============================================================
-- Referencias de imovel_rural (fonte: CAR)
-- ============================================================

CREATE TABLE ${silverSchema}.situacao_imovel (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.situacao_imovel (codigo, descricao) VALUES ('AT', 'Ativo');
INSERT INTO ${silverSchema}.situacao_imovel (codigo, descricao) VALUES ('PE', 'Pendente');
INSERT INTO ${silverSchema}.situacao_imovel (codigo, descricao) VALUES ('SU', 'Suspenso');
INSERT INTO ${silverSchema}.situacao_imovel (codigo, descricao) VALUES ('CA', 'Cancelado');

CREATE TABLE ${silverSchema}.condicao_imovel (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.condicao_imovel (codigo, descricao) VALUES ('AGUARDANDO_ANALISE', 'Aguardando analise');
INSERT INTO ${silverSchema}.condicao_imovel (codigo, descricao) VALUES ('EM_ANALISE', 'Em analise');
INSERT INTO ${silverSchema}.condicao_imovel (codigo, descricao) VALUES ('ANALISADO_SEM_PENDENCIAS', 'Analisado sem pendencias');
INSERT INTO ${silverSchema}.condicao_imovel (codigo, descricao) VALUES ('ANALISADO_COM_PENDENCIAS', 'Analisado com pendencias');
INSERT INTO ${silverSchema}.condicao_imovel (codigo, descricao) VALUES ('CANCELADO_ADM', 'Cancelado por decisao administrativa');
INSERT INTO ${silverSchema}.condicao_imovel (codigo, descricao) VALUES ('CANCELADO_JUDICIAL', 'Cancelado por decisao judicial');

CREATE TABLE ${silverSchema}.tipo_imovel (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.tipo_imovel (codigo, descricao) VALUES ('IRU', 'Imovel Rural');
INSERT INTO ${silverSchema}.tipo_imovel (codigo, descricao) VALUES ('AST', 'Assentamento de Reforma Agraria');
INSERT INTO ${silverSchema}.tipo_imovel (codigo, descricao) VALUES ('PCT', 'Territorio de Povos e Comunidades Tradicionais');

-- ============================================================
-- Referencias de area_protegida (fontes: ICMBio UC, FUNAI TI, futuramente INCRA)
-- ============================================================

CREATE TABLE ${silverSchema}.tipo_area_protegida (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.tipo_area_protegida (codigo, descricao) VALUES ('UC_PROTECAO_INTEGRAL', 'Unidade de Conservacao de Protecao Integral (SNUC)');
INSERT INTO ${silverSchema}.tipo_area_protegida (codigo, descricao) VALUES ('UC_USO_SUSTENTAVEL', 'Unidade de Conservacao de Uso Sustentavel (SNUC)');
INSERT INTO ${silverSchema}.tipo_area_protegida (codigo, descricao) VALUES ('TERRA_INDIGENA', 'Terra Indigena');
INSERT INTO ${silverSchema}.tipo_area_protegida (codigo, descricao) VALUES ('TERRITORIO_QUILOMBOLA', 'Territorio Quilombola');
INSERT INTO ${silverSchema}.tipo_area_protegida (codigo, descricao) VALUES ('ASSENTAMENTO_INCRA', 'Assentamento de Reforma Agraria (INCRA)');

CREATE TABLE ${silverSchema}.categoria_area_protegida (
    id                     NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo                 VARCHAR2(40)  NOT NULL UNIQUE,
    descricao              VARCHAR2(200) NOT NULL,
    tipo_area_protegida_id NUMBER NOT NULL REFERENCES ${silverSchema}.tipo_area_protegida(id),
    ativo                  NUMBER(1) DEFAULT 1 NOT NULL,
    data_add               TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
-- UC - Protecao Integral
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'PARNA', 'Parque Nacional', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_PROTECAO_INTEGRAL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'REBIO', 'Reserva Biologica', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_PROTECAO_INTEGRAL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'ESEC', 'Estacao Ecologica', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_PROTECAO_INTEGRAL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'REVIS', 'Refugio de Vida Silvestre', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_PROTECAO_INTEGRAL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'MONA', 'Monumento Natural', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_PROTECAO_INTEGRAL';
-- UC - Uso Sustentavel
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'FLONA', 'Floresta Nacional', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_USO_SUSTENTAVEL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'RESEX', 'Reserva Extrativista', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_USO_SUSTENTAVEL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'RDS', 'Reserva de Desenvolvimento Sustentavel', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_USO_SUSTENTAVEL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'ARIE', 'Area de Relevante Interesse Ecologico', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_USO_SUSTENTAVEL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'APA', 'Area de Protecao Ambiental', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_USO_SUSTENTAVEL';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'RPPN', 'Reserva Particular do Patrimonio Natural', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'UC_USO_SUSTENTAVEL';
-- Terra Indigena (modalidades)
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'TI_TRADICIONALMENTE_OCUPADA', 'Tradicionalmente ocupada', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'TERRA_INDIGENA';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'TI_RESERVA_INDIGENA', 'Reserva Indigena', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'TERRA_INDIGENA';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'TI_DOMINIAL', 'Dominial', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'TERRA_INDIGENA';
INSERT INTO ${silverSchema}.categoria_area_protegida (codigo, descricao, tipo_area_protegida_id)
    SELECT 'TI_INTERDITADA', 'Interditada (restricao de uso)', id FROM ${silverSchema}.tipo_area_protegida WHERE codigo = 'TERRA_INDIGENA';

-- ============================================================
-- Referencias de embargo_ambiental (fontes: IBAMA, ICMBio)
-- ============================================================

CREATE TABLE ${silverSchema}.tipo_area_embargo (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.tipo_area_embargo (codigo, descricao) VALUES ('DESMATAMENTO', 'Desmatamento');
INSERT INTO ${silverSchema}.tipo_area_embargo (codigo, descricao) VALUES ('QUEIMADA', 'Queimada / incendio');
INSERT INTO ${silverSchema}.tipo_area_embargo (codigo, descricao) VALUES ('EXPLORACAO_IRREGULAR', 'Exploracao florestal irregular');
INSERT INTO ${silverSchema}.tipo_area_embargo (codigo, descricao) VALUES ('OUTROS', 'Outros');

-- ============================================================
-- Referencias de auto_infracao (fontes: IBAMA, ICMBio)
-- ============================================================

CREATE TABLE ${silverSchema}.tipo_auto_infracao (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.tipo_auto_infracao (codigo, descricao) VALUES ('MULTA_SIMPLES', 'Multa simples');
INSERT INTO ${silverSchema}.tipo_auto_infracao (codigo, descricao) VALUES ('MULTA_DIARIA', 'Multa diaria');
INSERT INTO ${silverSchema}.tipo_auto_infracao (codigo, descricao) VALUES ('ADVERTENCIA', 'Advertencia');
INSERT INTO ${silverSchema}.tipo_auto_infracao (codigo, descricao) VALUES ('APREENSAO', 'Apreensao de bens/produtos');
INSERT INTO ${silverSchema}.tipo_auto_infracao (codigo, descricao) VALUES ('EMBARGO', 'Embargo/interdicao');
INSERT INTO ${silverSchema}.tipo_auto_infracao (codigo, descricao) VALUES ('DEMOLICAO', 'Demolicao de obra');
INSERT INTO ${silverSchema}.tipo_auto_infracao (codigo, descricao) VALUES ('SUSPENSAO', 'Suspensao de atividade');

CREATE TABLE ${silverSchema}.tipo_infracao (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.tipo_infracao (codigo, descricao) VALUES ('CONTRA_FLORA', 'Das infracoes contra a flora');
INSERT INTO ${silverSchema}.tipo_infracao (codigo, descricao) VALUES ('CONTRA_FAUNA', 'Das infracoes contra a fauna');
INSERT INTO ${silverSchema}.tipo_infracao (codigo, descricao) VALUES ('POLUICAO', 'Das infracoes relativas a poluicao e outras infracoes ambientais');
INSERT INTO ${silverSchema}.tipo_infracao (codigo, descricao) VALUES ('CONTRA_ADMIN_AMBIENTAL', 'Das infracoes contra a administracao ambiental');
INSERT INTO ${silverSchema}.tipo_infracao (codigo, descricao) VALUES ('EM_UNIDADES_CONSERVACAO', 'Das infracoes cometidas exclusivamente em unidades de conservacao');
INSERT INTO ${silverSchema}.tipo_infracao (codigo, descricao) VALUES ('CONTRA_ORDENAMENTO_URBANO', 'Das infracoes contra o ordenamento urbano e o patrimonio cultural');

CREATE TABLE ${silverSchema}.tipo_pessoa (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(2)   NOT NULL UNIQUE,
    descricao VARCHAR2(60)  NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.tipo_pessoa (codigo, descricao) VALUES ('PF', 'Pessoa Fisica');
INSERT INTO ${silverSchema}.tipo_pessoa (codigo, descricao) VALUES ('PJ', 'Pessoa Juridica');

CREATE TABLE ${silverSchema}.status_auto_infracao (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.status_auto_infracao (codigo, descricao) VALUES ('LAVRADO', 'Lavrado');
INSERT INTO ${silverSchema}.status_auto_infracao (codigo, descricao) VALUES ('HOMOLOGADO', 'Homologado');
INSERT INTO ${silverSchema}.status_auto_infracao (codigo, descricao) VALUES ('JULGADO', 'Julgado');
INSERT INTO ${silverSchema}.status_auto_infracao (codigo, descricao) VALUES ('CANCELADO', 'Cancelado');

-- ============================================================
-- Referencias de desmatamento (fonte: INPE PRODES)
-- ============================================================

CREATE TABLE ${silverSchema}.classe_prodes (
    id        NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    codigo    VARCHAR2(40)  NOT NULL UNIQUE,
    descricao VARCHAR2(200) NOT NULL,
    ativo     NUMBER(1) DEFAULT 1 NOT NULL,
    data_add  TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
INSERT INTO ${silverSchema}.classe_prodes (codigo, descricao) VALUES ('DESMATAMENTO', 'Desmatamento (corte raso)');
INSERT INTO ${silverSchema}.classe_prodes (codigo, descricao) VALUES ('NAO_FLORESTA', 'Nao floresta');
INSERT INTO ${silverSchema}.classe_prodes (codigo, descricao) VALUES ('NUVEM', 'Nuvem');
INSERT INTO ${silverSchema}.classe_prodes (codigo, descricao) VALUES ('HIDROGRAFIA', 'Hidrografia');
INSERT INTO ${silverSchema}.classe_prodes (codigo, descricao) VALUES ('FLORESTA', 'Floresta');
