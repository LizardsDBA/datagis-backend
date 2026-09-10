# Modelagem Silver / Gold / Quarentena — v1 (2026-09-10)

> Desenho das camadas Silver, Gold e Quarentena a partir dos **dados reais já
> ingeridos no Bronze** (11 fontes, ~350 mil registros). Branch
> `feat/modelagem-silver-gold-quarentena`, a partir da `sprint-1`.
>
> **Escopo desta rodada: só DDL + seeds das tabelas de referência.** Nenhuma
> transformação Bronze→Silver é implementada aqui — isso é fatia futura. O objetivo é
> ter o schema pronto e normalizado pra quando a transformação entrar.

---

## 1. Princípios

1. **Silver é modelada por fenômeno, não por fonte.** Existe uma tabela de fato por
   "tipo de coisa que os indicadores medem" (imóvel, área protegida, embargo,
   desmatamento, foco de calor…). Uma fonte de dado nova **cai numa tabela que já
   existe** — não gera tabela nova (ver §6). Tabela nova só quando aparece um fenômeno
   que nenhum dos 7 indicadores cobre.

2. **Todo campo "enum" é FK pra tabela de referência.** Nada de `VARCHAR2` com valores
   canônicos soltos. `imovel_rural.situacao_imovel_id` → `silver.situacao_imovel`, etc.
   As tabelas de referência já nascem semeadas (§3).

3. **Núcleo tipado + cauda JSON.** Cada tabela de fato tem as colunas canônicas
   tipadas (as que os indicadores usam) e uma coluna `atributos_origem JSON` pro que é
   específico de cada fonte. Isola a variação de schema sem estourar a tabela em 40
   colunas nem exigir DDL a cada fonte nova.

4. **Linhagem em toda linha.** `fonte_dado_id`, `lote_ingestao_id`,
   `registro_bruto_id` (aponta pra linha exata do Bronze), `data_competencia`,
   `data_processamento`, `hash_origem`. Sustenta a rastreabilidade que o desafio exige
   (RN016-019: "comprovar que a informação é verdadeira").

5. **FK real só intra-schema.** Referência cross-schema (Silver→Bronze,
   Silver→Controle, Gold→Silver) é `NUMBER` sem constraint — mesmo padrão que o Bronze
   já usa (CLAUDE.md §9.5). Motivo: `GRANT REFERENCES` cross-schema exige o dono da
   tabela-pai, e o Flyway roda tudo como `CONTROLE`; forçar isso pra linhagem não paga
   o custo.

6. **Geometria: `MDSYS.SDO_GEOMETRY`, SRID 4674 (SIRGAS 2000).** Todas as fontes já
   chegam em 4674 (ou são forçadas via `outSR=4674`). É o primeiro uso real de Oracle
   Spatial no projeto (item 2 do spike, CLAUDE.md §5.5) — necessário pros cruzamentos
   de sobreposição (`SDO_RELATE`, `SDO_GEOM.SDO_AREA`) que ISAP/IAE/IDesmat exigem.
   **Exigiu trocar a imagem Oracle de `-slim` para a completa**
   (`gvenzl/oracle-free:23.26.3-faststart`) — o `-slim` não traz o Oracle Spatial e
   `SDO_GEOMETRY` dá `ORA-00902`.

---

## 2. O que os dados reais mostraram (achados que moldaram o schema)

| Achado | Consequência no modelo |
|---|---|
| **Bronze acumulou duplicata** de ingestões repetidas (CAR 13.775 ≈ 3× o circuito de 3 municípios; Embargos IBAMA 6.155 ≈ 3×) | Transformação Silver deduplica **por chave natural, mantendo a ocorrência do maior `lote_ingestao_id`** |
| **Tipos inconsistentes entre linhas** (`json(scalar)` no dataguide — Embargos IBAMA tem `num_latitude_tad`, `qtd_area_embargada`, `dat_embargo` ora string, ora número, ora null) | Cast + validação na transformação; o que falhar vai pra `quarentena` com `tipo_erro = TIPO_DE_DADO_INVALIDO` |
| **Datas em epoch millis** (`data: 1520899200000` nos Embargos/Autos ICMBio) | `tipo_dado = epoch_millis` no `mapeamento_coluna`; coluna Silver é `DATE`/`TIMESTAMP` |
| **Área só serve reprojetada** — `Shape__Area` está em graus². IBAMA tem `qtd_area_embargada` em ha; **ICMBio não tem área em ha** | `area_ha` calculada de `SDO_GEOM.SDO_AREA(geometria, tol, 'unit=SQ_M')/10000` quando a fonte não trouxer |
| **Autos de Infração IBAMA vêm sem geometria** (16.796 linhas, geometria nula) | `auto_infracao.geometria` é `NULL`-ável; essas linhas não entram em indicador espacial |
| **Foco de calor: 2 fontes, schemas diferentes** — INPE Queimadas traz `municipio_id` (código IBGE), o IAT traz só o nome | `foco_calor.municipio_id` nullable; o que faltar resolve por join espacial na transformação |
| **Área protegida abrange várias UFs** (`"SP/PR"`, `"PR,SC"`) | Bridge `area_protegida_uf` (N:N), não FK simples de UF |
| **Embargo IBAMA já traz `num_auto_infracao`, `des_infracao`, `num_processo`** | `embargo_ambiental.auto_infracao_id` (FK nullable) — embargo é consequência do auto, não se duplica |

---

## 3. Camada Silver

### 3.1. Tabelas de referência (todas com seed) — `V011`

Estrutura padrão: `id` IDENTITY PK · `codigo VARCHAR2 UNIQUE` · `descricao` · `ativo NUMBER(1)` · `data_add`.

**Compartilhadas:** `orgao`, `bioma`, `satelite`, `sensor`, `unidade_federativa`.

**Por tabela de fato:** `situacao_imovel`, `condicao_imovel`, `tipo_imovel`,
`tipo_area_protegida`, `categoria_area_protegida` (com FK pra `tipo_area_protegida`),
`tipo_area_embargo`, `tipo_auto_infracao`, `tipo_infracao`, `tipo_pessoa`,
`status_auto_infracao`, `classe_prodes`.

Valores semeados: ver `V011__silver_tabelas_referencia.sql` (vêm do que apareceu nos
dados + o domínio conhecido de cada um, ex. SNUC pras categorias de UC).

### 3.2. Tabelas de fato — `V012`

Todas carregam o bloco de linhagem (§1.4), `atributos_origem JSON`, e `geometria
SDO_GEOMETRY`.

| Tabela | Chave natural | FK de referência (`_id`) | Fontes hoje |
|---|---|---|---|
| `municipio` | `cod_ibge` | `unidade_federativa_id`, `bioma_predominante_id` | 23 |
| `imovel_rural` | `cod_imovel` | `situacao_imovel_id`, `condicao_imovel_id`, `tipo_imovel_id`, `municipio_id` | 1 |
| `area_protegida` | `codigo_origem` + `orgao_id` | `tipo_area_protegida_id`, `categoria_area_protegida_id`, `orgao_id`, `bioma_id` | 3, 24 |
| `area_protegida_uf` (bridge) | (`area_protegida_id`,`unidade_federativa_id`) | — | — |
| `embargo_ambiental` | `numero_termo` + `orgao_id` | `orgao_id`, `tipo_area_embargo_id`, `municipio_id` | 6, 25 |
| `auto_infracao` | `numero_auto` + `orgao_id` | `orgao_id`, `tipo_auto_infracao_id`, `tipo_infracao_id`, `tipo_pessoa_id`, `status_auto_infracao_id`, `municipio_id` | 26, 27 |
| `desmatamento` | (`fonte_dado_id`,`identificador_origem`) | `classe_prodes_id`, `satelite_id`, `sensor_id` | 4 |
| `foco_calor` | (`fonte_dado_id`,`identificador_origem`) | `satelite_id`, `bioma_id`, `municipio_id` | 5, 7 |

`embargo_ambiental.auto_infracao_id` — FK intra-schema nullable, liga o embargo ao auto que o originou.

### 3.3. Metadata espacial — `V013`

`INSERT` em `MDSYS.SDO_GEOM_METADATA_TABLE` (uma linha por coluna geométrica, bbox do
Brasil, SRID 4674). **É o primeiro uso de Oracle Spatial no projeto** — se o `INSERT`
na tabela do `MDSYS` falhar por privilégio (o `CONTROLE` tem `INSERT ANY TABLE`, deve
cobrir), essa migration é onde se resolve (grant pontual ou rodar como `SILVER`).

Os **índices espaciais** (`INDEXTYPE IS MDSYS.SPATIAL_INDEX_V2`) ficam pra fatia de
cálculo — índice em tabela vazia não vale nada agora, e criar índice de domínio em
outro schema é o passo mais arriscado; entra junto quando a transformação popular a
Silver e o Oracle Spatial for validado de verdade (§5.5 do CLAUDE.md).

### 3.4. View unificada — `V014`

`vw_restricao_territorial` — `UNION ALL` de `area_protegida` + `embargo_ambiental` +
`desmatamento` num formato comum (`origem`, `tipo_restricao`, `geometria`,
`data_competencia`, `area_ha`, `fonte_dado_id`). Os calculadores de ISAP/IAE/IDesmat
leem essa view, não as três tabelas.

---

## 4. Camada Quarentena

**Espelho frouxo do Silver.** Uma tabela `q_<nome>` por tabela de fato do Silver, com:

- as **mesmas colunas de negócio, mas frouxas**: tudo `NULL`-ável, **sem FK, sem
  UNIQUE, sem CHECK** (o dado rejeitado pode ser exatamente o que quebrou a regra);
- as colunas que no Silver são `_id` FK, aqui voltam a ser **texto cru** (guarda o
  valor original que não bateu com a referência);
- `geometria` como `CLOB` (WKT cru) — geometria inválida não entra em coluna espacial;
- o **bloco de rejeição** (igual em todas): `registro_bruto_id`, `lote_ingestao_id`,
  `fonte_dado_id`, `payload_original JSON`, `tipo_erro_id` (FK
  `quarentena.tipo_erro_quarentena`), `campo_problema`, `valor_recebido VARCHAR2(4000)`,
  `motivo_rejeicao VARCHAR2(500)`, `status_analise_id` (FK
  `quarentena.status_analise_quarentena`), `analista_responsavel_id`,
  `data_quarentena`, `data_resolucao`.

Tabelas (`V015`): `q_municipio`, `q_imovel_rural`, `q_area_protegida`,
`q_embargo_ambiental`, `q_auto_infracao`, `q_desmatamento`, `q_foco_calor`.

Referências (schema `quarentena`):
- `tipo_erro_quarentena`: `GEOMETRIA_NULA`, `GEOMETRIA_INVALIDA`, `GEOMETRIA_MALFORMADA`,
  `CAMPO_OBRIGATORIO_AUSENTE`, `TIPO_DE_DADO_INVALIDO`, `VALOR_FORA_DO_DOMINIO`,
  `CHAVE_NATURAL_DUPLICADA`, `REFERENCIA_NAO_ENCONTRADA`, `FORA_DO_RECORTE_TERRITORIAL`.
- `status_analise_quarentena`: `PENDENTE`, `EM_ANALISE`, `CORRIGIDO_REPROCESSADO`,
  `DESCARTADO`.

Regra de negócio (RN007): correção só manual, pelo Operador de Dados — o sistema nunca
"inventa" o dado faltante.

---

## 5. Camada Gold

Indicador versionado + linhagem (CLAUDE.md §2.6, `V016`). Os 7 indicadores do desafio:
ICV, IRL, IAPP, ISAP, IAE, IDesmat, IFC.

| Tabela | Papel |
|---|---|
| `regra_calculo` | fórmula versionada por indicador (`tipo_indicador_id` FK, `versao`, `expressao`, `vigente_de`/`vigente_ate`) |
| `indicador_versao` | 1 cálculo p/ 1 imóvel: `imovel_cod` (ref natural), `data_processamento`, `hash_versao` (imutável — amarra dado-fonte + regra + resultado), `regra_calculo_id` |
| `indicador_resultado` | `indicador_versao_id`, `tipo_indicador_id`, `valor`, `area_afetada_ha`, `percentual_sobreposicao` |
| `indicador_versao_fonte` | N:N — quais `lote_ingestao` de quais fontes entraram no cálculo (espinha da linhagem) |

`tipo_indicador` é tabela de referência (schema `gold`) semeada com os 7.

### Cobertura hoje

| Indicador | Fonte Silver | Status |
|---|---|---|
| ISAP | `area_protegida` × `imovel_rural` | ✅ calculável |
| IAE | `embargo_ambiental` × `imovel_rural` | ✅ |
| IDesmat | `desmatamento` × `imovel_rural` | ✅ |
| IFC | `foco_calor` × `imovel_rural` | ✅ |
| ICV / IRL / IAPP | — | ❌ sem fonte (dependem da API SICAR Temas, credenciamento externo) |

Os 3 sem fonte já têm `tipo_indicador` semeado e coluna na Gold — só não dá pra popular.

---

## 6. Fonte nova sem DDL — o mecanismo

Pra uma fonte nova (mesmo tema) entrar sem criar tabela:

1. **`controle.fonte_dado.entidade_silver`** (coluna nova, `V010`) — no cadastro da
   fonte o usuário escolhe pra qual entidade canônica ela vai (`IMOVEL_RURAL`,
   `AREA_PROTEGIDA`, `EMBARGO_AMBIENTAL`, `AUTO_INFRACAO`, `DESMATAMENTO`,
   `FOCO_CALOR`, `MUNICIPIO`).

2. **`controle.mapeamento_coluna`** (tabela nova, `V010` — já prevista no CLAUDE.md
   §2.3, nunca construída) — de-para campo a campo:

   ```
   fonte_dado_id     → qual fonte
   coluna_origem     → 'num_tad'            (chave no payload do Bronze)
   atributo_destino  → 'numero_termo'       (coluna canônica da Silver)
   destino_tipo      → 'COLUNA' | 'JSON' | 'GEOMETRIA'
   tipo_dado         → 'texto' | 'numero' | 'epoch_millis' | 'data_iso' |
                       'cpf_cnpj' | 'graus2_para_ha' | 'wkt' ...
   obrigatorio       → 0 | 1
   ```

Cadastrar uma fonte nova = 1 linha em `fonte_dado` + N linhas em `mapeamento_coluna`.
Nenhuma migration. **Tabela nova só pra fenômeno novo** (ex.: quando a API SICAR Temas
destravar, "APP declarada" e "Reserva Legal declarada" são fenômeno novo →
`area_declarada_car`).

---

## 7. Decisões travadas nesta rodada

- **[DECIDIDO]** Silver por fenômeno; fonte nova → tabela existente via `mapeamento_coluna`.
- **[DECIDIDO]** Todo enum é FK pra tabela de referência semeada.
- **[DECIDIDO]** Quarentena = espelho frouxo do Silver + bloco de rejeição.
- **[DECIDIDO]** `geometria SDO_GEOMETRY` SRID 4674; metadata/índice espacial em migration própria.
- **[DECIDIDO]** Dedup do Bronze por chave natural, mantendo maior `lote_ingestao_id`.
- **[DECIDIDO]** FK real só intra-schema; linhagem cross-schema é `NUMBER` sem constraint.
- **[EM ABERTO]** Transformação Bronze→Silver (fatia futura) — nada implementado aqui.
- **[EM ABERTO]** Reprojeção pra cálculo de área/distância — usar `SDO_GEOM` direto no
  4674 (geodésico) ou reprojetar pra métrico (EPSG:5880). Decidir na fatia de cálculo.
