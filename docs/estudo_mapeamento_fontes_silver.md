# Estudo de Mapeamento de Colunas (Bronze -> Silver)

Este documento reúne todas as 18 fontes de dados cadastradas no DataGIS, suas respectivas tabelas de destino na camada Silver e o mapeamento de-para campo a campo.
Você pode editar diretamente este arquivo para ajustar colunas de origem, colunas de destino ou regras de validação.

OBS IMPORTANTE:
1. Tudo que estiver relacionado a "orgao" é atribuído diretamente a partir dos metadados da fonte de dados no sistema. Não é buscado nos dados brutos e não requer validações no de-para. Não sendo necessário e nem permitido vincular esse item no mapeamento de coluna.
2. Tudo que for relacionado a "tipo_area_protegida" é derivado automaticamente a partir de "categoria_area_protegida". Não sendo necessário vincular tipo_area_protegida no mapeamento de coluna. Em contrapartida, "categoria_area_protegida" é de preenchimento obrigatório para áreas protegidas.
3. Tudo relacionado a "unidade_federativa" em município é preenchido automaticamente com o valor padrão do estado do Paraná (PR). Não sendo necessário vincular no mapeamento de coluna.
4. Regra de Bloqueio de Ingestão por Mapeamento Incompleto: O sistema bloqueia preventivamente a execução da fonte (manual ou agendada) caso alguma coluna física da tabela Silver marcada como NOT NULL não esteja mapeada. Colunas que não possuem restrição NOT NULL na tabela física da Silver (por exemplo, situacao_imovel em imovel_rural) são opcionais e não impedem a ingestão.

---

## 1. Regras e Colunas Canônicas Permitidas por Tabela Silver

A coluna de destino (`Coluna Destino (Silver)`) deve obrigatoriamente existir na tabela física de destino na Silver (colunas autogeradas como `orgao`, `tipo_area_protegida` e `unidade_federativa` foram removidas do catálogo de seleção manual):

| Tabela Silver | Colunas Canônicas Disponíveis |
| :--- | :--- |
| **`imovel_rural`** | `cod_imovel`, `situacao_imovel`, `condicao_imovel`, `tipo_imovel`, `area_ha`, `modulos_fiscais`, `municipio`, `data_registro`, `geometria` |
| **`area_protegida`** | `codigo_origem`, `nome`, `area_ha`, `categoria_area_protegida`, `bioma`, `ato_legal`, `geometria` |
| **`embargo_ambiental`** | `numero_termo`, `auto_infracao`, `data_embargo`, `area_embargada_ha`, `tipo_area_embargo`, `cpf_cnpj`, `nome_autuado`, `municipio`, `descricao`, `num_processo`, `num_auto_infracao`, `geometria` |
| **`auto_infracao`** | `numero_auto`, `data_auto`, `valor_multa`, `tipo_auto_infracao`, `tipo_infracao`, `tipo_pessoa`, `status_auto_infracao`, `cpf_cnpj_infrator`, `nome_infrator`, `municipio`, `descricao`, `num_processo`, `geometria`, `houve_embargo`, `houve_apreensao` |
| **`desmatamento`** | `identificador_origem`, `ano`, `area_ha`, `classe_prodes`, `satelite`, `sensor`, `data_imagem`, `geometria` |
| **`foco_calor`** | `identificador_origem`, `data_hora`, `satelite`, `bioma`, `municipio`, `latitude`, `longitude`, `risco_fogo`, `dias_sem_chuva`, `precipitacao_mm`, `frp`, `geometria` |
| **`municipio`** | `cod_ibge`, `nome`, `bioma_predominante`, `area_km2`, `geometria` |
| **`reserva_legal`** | `identificador_origem`, `cod_imovel`, `area_ha`, `situacao_bruto`, `condicao_bruto`, `geometria` |
| **`app_declarada`** | `identificador_origem`, `cod_imovel`, `area_ha`, `situacao_bruto`, `condicao_bruto`, `geometria` |
| **`vegetacao_nativa_declarada`** | `identificador_origem`, `cod_imovel`, `area_ha`, `situacao_bruto`, `condicao_bruto`, `geometria` |
| **`bioma`** | `codigo`, `descricao`, `ativo`, `data_add`, `geometria` |

---

## 2. Mapeamento por Fonte de Dados

### Fonte 1: `IBGE - Malha Municipal (PR)`
- **Tabela Silver Destino:** `municipio`
- **Chave Natural (Deduplicação):** `cod_ibge`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `codibge` | `cod_ibge` | COLUNA | texto | Sim | Chave natural (7 dígitos) |
| `nome` | `nome` | COLUNA | texto | Sim | Nome do município |
| `area_km2` | `area_km2` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria |

---

### Fonte 2: `IBGE - Limites de biomas`
- **Tabela Silver Destino:** `bioma`
- **Chave Natural (Deduplicação):** `codigo_bioma`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `CD_BIOMA` | `codigo_bioma` | COLUNA | texto | Sim | Chave natural (1 a 6) |
| `NM_BIOMA` | `nome` | COLUNA | texto | Sim | |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria |

Obs: Coluna `ativo` vem como padrao 1 e `data_add` vem como parão a data da ingestão

---

### Fonte 3: `CAR - Imóveis (PR)` (WFS / GeoJSON)
- **Tabela Silver Destino:** `imovel_rural`
- **Chave Natural (Deduplicação):** `cod_imovel`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `cod_imovel` | `cod_imovel` | COLUNA | texto | Sim | Chave natural (Código SICAR) |
| `status_imovel` | `situacao_imovel` | COLUNA | texto | Não | Domínio: `situacao_imovel` (AT, PE, CA, etc.) |
| `condicao` | `condicao_imovel` | COLUNA | texto | Não | Domínio: `condicao_imovel` |
| `tipo_imovel` | `tipo_imovel` | COLUNA | texto | Não | Domínio: `tipo_imovel` |
| `area` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `m_fiscal` | `modulos_fiscais` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `cod_municipio_ibge` | `municipio` | COLUNA | texto | Não | Ref. Silver: `municipio` |
| `dat_criacao` | `data_registro` | COLUNA | data_iso | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |

---

### Fonte 4: `CAR - Imóveis (PR) via Shapefile (upload)`
- **Tabela Silver Destino:** `imovel_rural`
- **Chave Natural (Deduplicação):** `cod_imovel`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `COD_IMOVEL` | `cod_imovel` | COLUNA | texto | Sim | Chave natural |
| `STATUS_IMOVEL` | `situacao_imovel` | COLUNA | texto | Não | Domínio: `situacao_imovel` |
| `CONDICAO` | `condicao_imovel` | COLUNA | texto | Não | Domínio: `condicao_imovel` |
| `TIPO_IMOVEL` | `tipo_imovel` | COLUNA | texto | Não | Domínio: `tipo_imovel` |
| `NUM_AREA` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `NUM_MODULO` | `modulos_fiscais` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `COD_MUNICIPIO` | `municipio` | COLUNA | texto | Não | Ref. Silver: `municipio` |
| `DAT_CRIACAO` | `data_registro` | COLUNA | data_iso | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |

---

### Fonte 5: `IAT/PR - APP Total (CAR)` (ArcGIS REST)
- **Tabela Silver Destino:** `app_declarada`
- **Chave Natural (Deduplicação):** `identificador_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `objectid` | `identificador_origem` | COLUNA | texto | Sim | Chave natural única do polígono |
| `cod_imovel` | `cod_imovel` | COLUNA | texto | Sim |  |
| `num_area` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `ind_status` | `situacao_bruto` | COLUNA | texto | Não |  |
| `des_condic` | `condicao_bruto` | COLUNA | texto | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |

---

### Fonte 6: `IAT/PR - Reserva Legal (CAR)` (ArcGIS REST)
- **Tabela Silver Destino:** `reserva_legal`
- **Chave Natural (Deduplicação):** `identificador_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `objectid` | `identificador_origem` | COLUNA | texto | Sim | Chave natural única do polígono |
| `cod_imovel` | `cod_imovel` | COLUNA | texto | Sim |  |
| `num_area` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `ind_status` | `situacao_bruto` | COLUNA | texto | Não |  |
| `des_condic` | `condicao_bruto` | COLUNA | texto | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |

---

### Fonte 7: `IAT/PR - Vegetação Nativa (CAR)` (ArcGIS REST)
- **Tabela Silver Destino:** `vegetacao_nativa_declarada`
- **Chave Natural (Deduplicação):** `identificador_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `objectid` | `identificador_origem` | COLUNA | texto | Sim | Chave natural única do polígono |
| `cod_imovel` | `cod_imovel` | COLUNA | texto | Sim |  |
| `num_area` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `ind_status` | `situacao_bruto` | COLUNA | texto | Não |  |
| `des_condic` | `condicao_bruto` | COLUNA | texto | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |

---

### Fonte 8: `ICMBio - Unidades de Conservação (PR)` (WFS INDE)
- **Tabela Silver Destino:** `area_protegida`
- **Chave Natural (Deduplicação):** `codigo_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `cnuc` | `codigo_origem` | COLUNA | texto | Sim | Chave natural CNUC |
| `nomeuc` | `nome` | COLUNA | texto | Sim |  |
| `areahaalb` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `categoria` | `categoria_area_protegida` | COLUNA | texto | Sim | Domínio: `categoria_area_protegida` |
| `biomas` | `bioma` | COLUNA | texto | Não | Domínio: `bioma` |
| `criacaoato` | `ato_legal` | COLUNA | texto | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |

---

### Fonte 9: `IAT/PR - Terras Indígenas (FUNAI)` (WFS / GeoJSON)
- **Tabela Silver Destino:** `area_protegida`
- **Chave Natural (Deduplicação):** `codigo_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `terrai_cod` | `codigo_origem` | COLUNA | texto | Sim | Chave natural FUNAI |
| `terrai_nom` | `nome` | COLUNA | texto | Sim |  |
| `superficie` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `modalidade` | `categoria_area_protegida` | COLUNA | texto | Sim | Domínio: `categoria_area_protegida` |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |

---

### Fonte 10: `MMA - Unidades de Conservacao (CNUC nacional)` (Shapefile)
- **Tabela Silver Destino:** `area_protegida`
- **Chave Natural (Deduplicação):** `codigo_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `cd_cnuc`, `codigo_uc` | `codigo_origem` | COLUNA | texto | Sim | Chave natural CNUC |
| `nome_uc` | `nome` | COLUNA | texto | Sim |  |
| `ha_total` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `categoria` | `categoria_area_protegida` | COLUNA | texto | Sim | Domínio: `categoria_area_protegida` |
| `cria_ato`, `ato_criaca` | `ato_legal` | COLUNA | texto | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |

---

### Fonte 11: `SFB - Cadastro Nacional de Florestas Publicas (CNFP)` (Shapefile)
- **Tabela Silver Destino:** `area_protegida`
- **Chave Natural (Deduplicação):** `codigo_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `geocodigo` | `codigo_origem` | COLUNA | texto | Sim | Chave natural CNFP |
| `nome` | `nome` | COLUNA | texto | Sim |  |
| `area_ha` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `categoria` | `categoria_area_protegida` | COLUNA | texto | Sim | Domínio: `categoria_area_protegida` |
| `bioma` | `bioma` | COLUNA | texto | Não | Domínio: `bioma` |
| `atolegal` | `ato_legal` | COLUNA | texto | Não | Ato legal |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria + Limite PR |


---

### Fonte 12: `IAT/PR - Embargos IBAMA` (ArcGIS REST)
- **Tabela Silver Destino:** `embargo_ambiental`
- **Chave Natural (Deduplicação):** `numero_termo`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `num_tad` | `numero_termo` | COLUNA | texto | Sim | Chave natural do embargo |
| `cpf_cnpj_embargado` | `cpf_cnpj` | COLUNA | cpf_cnpj | Não | Validar CPF/CNPJ |
| `nome_embargado` | `nome_autuado` | COLUNA | texto | Não |  |
| `qtd_area_embargada` | `area_embargada_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `tipo_area` | `tipo_area_embargo` | COLUNA | texto | Não | Domínio: `tipo_area_embargo` |
| `num_auto_infracao` | `num_auto_infracao` | COLUNA | texto | Não | Referência ao Auto infração |
| `num_processo` | `num_processo` | COLUNA | texto | Não |  |
| `dat_embargo` | `data_embargo` | COLUNA | data_iso | Não |  |
| `cod_municipio` | `municipio` | COLUNA | texto | Não | Ref. Silver: `municipio` |
| `des_infracao` | `descricao` | COLUNA | texto | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria |

OBS: a coluna auto_infracao será preenchida a partir da tabela auto_infracao, pela busca do num_auto_infracao em numero_auto

---

### Fonte 13: `IAT/PR - Embargos ICMBio` (ArcGIS REST)
- **Tabela Silver Destino:** `embargo_ambiental`
- **Chave Natural (Deduplicação):** `numero_termo`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `numero_emb` | `numero_termo` | COLUNA | texto | Sim | Chave natural do embargo |
| `cpf_cnpj` | `cpf_cnpj` | COLUNA | cpf_cnpj | Não | Validar CPF/CNPJ |
| `autuado` | `nome_autuado` | COLUNA | texto | Não |  |
| `numero_ai` | `num_auto_infracao` | COLUNA | texto | Não | |
| `desc_sanc` | `descricao` | COLUNA | texto | Não |  |
| `tipo_infra` | `tipo_area_embargo` | COLUNA | texto | Não | Domínio: `tipo_area_embargo` |
| `processo` | `num_processo` | COLUNA | texto | Não |  |
| `data` | `data_embargo` | COLUNA | data_iso | Não |  |
| `municipio` | `municipio` | COLUNA | texto | Não | Ref. Silver: `municipio` |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria |

OBS: a coluna auto_infracao será preenchida a partir da tabela auto_infracao, pela busca do num_auto_infracao em numero_auto

---

### Fonte 14: `IAT/PR - Autos de Infração IBAMA` (ArcGIS REST)
- **Tabela Silver Destino:** `auto_infracao`
- **Chave Natural (Deduplicação):** `numero_auto`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `num_auto_infracao` | `numero_auto` | COLUNA | texto | Sim | Chave natural |
| `cpf_cnpj_infrator` | `cpf_cnpj_infrator` | COLUNA | cpf_cnpj | Não | Validar CPF/CNPJ |
| `nome_infrator` | `nome_infrator` | COLUNA | texto | Não |  |
| `val_auto_infracao` | `valor_multa` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `tipo_auto` | `tipo_auto_infracao` | COLUNA | texto | Não | Domínio: `tipo_auto_infracao` |
| `tipo_infracao` | `tipo_infracao` | COLUNA | texto | Não | Domínio: `tipo_infracao` |
| `tp_pessoa_infrator` | `tipo_pessoa` | COLUNA | texto | Não | Domínio: `tipo_pessoa` |
| `des_status_formulario` | `status_auto_infracao` | COLUNA | texto | Não | Domínio: `status_auto_infracao` |
| `des_auto_infracao` | `descricao` | COLUNA | texto | Não |  |
| `num_processo` | `num_processo` | COLUNA | texto | Não |  |
| `dat_hora_auto_infracao` | `data_auto` | COLUNA | data_iso | Não |  |
| `cod_municipio` | `municipio` | COLUNA | texto | Não | Ref. Silver: `municipio` |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria |
| `cd_termos_embargos` | `houve_embargo` | COLUNA | texto | Não |  |
| `cd_termos_apreensao` | `houve_apreensao` | COLUNA | texto | Não |  |

---

### Fonte 15: `IAT/PR - Autos de Infração ICMBio` (ArcGIS REST)
- **Tabela Silver Destino:** `auto_infracao`
- **Chave Natural (Deduplicação):** `numero_auto`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `numero_ai` | `numero_auto` | COLUNA | texto | Sim | Chave natural |
| `cpf_cnpj` | `cpf_cnpj_infrator` | COLUNA | cpf_cnpj | Não | Validar CPF/CNPJ |
| `autuado` | `nome_infrator` | COLUNA | texto | Não | Nome do infrator |
| `valor_mult` | `valor_multa` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `tipo` | `tipo_auto_infracao` | COLUNA | texto | Não | Domínio: `tipo_auto_infracao` |
| `tipo_infra` | `tipo_infracao` | COLUNA | texto | Não | Domínio: `tipo_infracao` |
| `desc_ai` | `descricao` | COLUNA | texto | Não |  |
| `processo` | `num_processo` | COLUNA | texto | Não |  |
| `data` | `data_auto` | COLUNA | data_iso | Não |  |
| `municipio` | `municipio` | COLUNA | texto | Não | Ref. Silver: `municipio` |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria |
| `embargo` | `houve_embargo` | COLUNA | texto | Não |  |
| `apreensao` | `houve_apreensao` | COLUNA | texto | Não |  |

---

### Fonte 16: `INPE (PRODES) - Desmatamento Mata Atlântica (PR)` (WFS)
- **Tabela Silver Destino:** `desmatamento`
- **Chave Natural (Deduplicação):** `identificador_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `uuid` | `identificador_origem` | COLUNA | texto | Sim | Chave natural (UUID) |
| `year` | `ano` | COLUNA | numero | Não | Valor mínimo: 1900 |
| `area_km` | `area_ha` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `main_class` | `classe_prodes` | COLUNA | texto | Não | Domínio: `classe_prodes` |
| `satelite` | `satelite` | COLUNA | texto | Não | Domínio: `satelite` |
| `sensor` | `sensor` | COLUNA | texto | Não | Domínio: `sensor` |
| `image_date` | `data_imagem` | COLUNA | data_iso | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria |

---

### Fonte 17: `IAT/PR - Focos de Calor (INPE)` (ArcGIS REST)
- **Tabela Silver Destino:** `foco_calor`
- **Chave Natural (Deduplicação):** `identificador_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `objectid` | `identificador_origem` | COLUNA | texto | Sim | Chave natural |
| `datahora` | `data_hora` | COLUNA | data_iso | Não |  |
| `satelite` | `satelite` | COLUNA | texto | Não | Domínio: `satelite` |
| `bioma` | `bioma` | COLUNA | texto | Não | Domínio: `bioma` |
| `municipio` | `municipio` | COLUNA | texto | Não | Ref. Silver: `municipio` |
| `riscofogo` | `risco_fogo` | COLUNA | numero | Não |  |
| `latitude` | `latitude` | COLUNA | numero | Não |  |
| `longitude` | `longitude` | COLUNA | numero | Não |  |
| `diasemchuv` | `dias_sem_chuva` | COLUNA | numero | Não |  |
| `precipitac` | `precipitacao_mm` | COLUNA | numero | Não |  |
| `frp` | `frp` | COLUNA | texto | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria ponto |

---

### Fonte 18: `INPE (Queimadas) - Focos de calor, Brasil (mensal)` (CSV)
- **Tabela Silver Destino:** `foco_calor`
- **Chave Natural (Deduplicação):** `identificador_origem`

| Coluna Origem (Bruto) | Coluna Destino (Silver) | Destino Tipo | Tipo de Dado | Obrigatório | Validações / Regras |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `id` | `identificador_origem` | COLUNA | texto | Sim | Chave natural |
| `data_hora_gmt` | `data_hora` | COLUNA | data_iso | Não |  |
| `satelite` | `satelite` | COLUNA | texto | Não | Domínio: `satelite` |
| `bioma` | `bioma` | COLUNA | texto | Não | Domínio: `bioma` |
| `municipio` | `municipio` | COLUNA | texto | Não | Ref. Silver: `municipio` |
| `risco_fogo` | `risco_fogo` | COLUNA | numero | Não | |
| `numero_dias_sem_chuva` | `dias_sem_chuva` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `precipitacao` | `precipitacao_mm` | COLUNA | numero | Não | Valor mínimo: 0.0 |
| `lat` | `latitude` | COLUNA | numero | Não |  |
| `lon` | `longitude` | COLUNA | numero | Não |  |
| `frp` | `frp` | COLUNA | texto | Não |  |
| `geometry` | `geometria` | GEOMETRIA | wkt | Não | Validar geometria ponto |
