# DataGis — Contexto do Projeto

> Este arquivo é lido automaticamente pelo Claude Code ao abrir este diretório.
> Ele resume as decisões de arquitetura tomadas até o momento em discussões fora
> deste ambiente (chat Claude.ai). Mantenha-o atualizado conforme novas decisões
> forem tomadas — é a fonte de verdade de contexto entre sessões.

---

## 1. Visão Geral do Projeto

**Nome:** DataGis (produto da equipe; o desafio proposto pela Visiona se chama
"GeoRural DataHub" — ver documento oficial em §1; internamente também já foi referido
como "Geo Data Lake")
**Tipo:** Projeto acadêmico em equipe — FATEC, disciplina de API/Métodos Ágeis
**Parceiro/Cliente do desafio:** Visiona Tecnologia Espacial
**Semestre/contexto:** Desafio proposto em kickoff com a Visiona; Prof. Garcia como orientador acadêmico

### Objetivo de negócio
Centralizar, tratar e dar rastreabilidade a dados socioambientais de imóveis rurais
brasileiros (CAR, embargos do IBAMA, terras indígenas, unidades de conservação,
desmatamento/PRODES, uso do solo/MapBiomas, lista de trabalho escravo etc.), gerando
indicadores de conformidade socioambiental **auditáveis e reproduzíveis** para uso por
instituições financeiras na concessão de crédito rural.

O objetivo central (conforme reforçado pelo Prof. Garcia) **não é só exibir mapas** —
é **dar meios de comprovar que uma informação sobre um imóvel rural é verdadeira**,
com rastreabilidade total (qual fonte, qual versão, qual regra gerou qual resultado).

### Documentos de referência (uploads originais)
- `especificacao_nao_tecnica_georural.md` — visão de negócio, dores de mercado, perfis de usuário
- `documento_requisitos_georural.md` — RF01-RF10, RNF01-RNF05, user stories, DoR/DoD
- `Infos_Kickoff.md` — ata da reunião de kickoff com a Visiona
- `transcrição_garcia.md` — transcrição de aula do Prof. Garcia explicando o problema de negócio
- `drn-georural-datahub.md` — Documento de Regras de Negócio (RN001-RN026), RF001-RF011,
  RNF001-RNF010 e um ER de exemplo. **Este documento é material de levantamento de ideias
  ("brainstorm"), não uma especificação final vinculante** — algumas de suas premissas
  (ex: uso obrigatório do Apache Airflow) foram **conscientemente descartadas** pela equipe
  (ver seção 3). O ER de exemplo dele também não precisa ser seguido à risca.
- `Desafio_API_4BD_2026-2_GeoRural_DataHub_Apresentacao_v1.pdf` (recebido 2026-09-04) —
  **desafio oficial da Visiona, é a fonte mais autoritativa que existe** (mais que o DRN).
  Define: recorte territorial = **Paraná** (399 municípios, ~199 mil km², bioma Mata
  Atlântica — RL mínima 20%), 7 indicadores a calcular (ICV, IRL, IAPP, ISAP, IAE,
  IDesmat, IFC — fórmulas na pág. 6), fluxo mínimo de 8 etapas, RNFs (Oracle com PL/SQL,
  GeoDataLake em "armazenamento de objetos" nas 4 zonas, nuvem preferencialmente GCP),
  telas de referência (painel, catálogo, detalhe do conjunto, execuções). **Também repete
  a exigência de Airflow** (pipelines/DAGs) — decisão da equipe de não usá-lo (§3.1) segue
  valendo; o próprio slide dá a brecha "ou container equivalente". Catálogo de fontes de
  dados do slide 7 (pág. do PDF) testado e documentado em `docs/fontes-de-dados.md`.

---

## 2. Arquitetura de Dados — Medalhão (Bronze / Silver / Gold / Quarentena)

Decisão herdada dos documentos de requisito e confirmada nas discussões: pipeline de
dados em 4 zonas lógicas.

```
Fontes externas (CAR, IBAMA, MapBiomas, PRODES, ICMBio, Min. Trabalho...)
        │
        ▼ [Ingestão]
   ZONA BRUTA (Bronze)     — dado exatamente como recebido, imutável
        │
        ▼ [Validação/Limpeza]
   ┌─────────────┬─────────────────┐
   ▼             ▼
ZONA TRATADA   ZONA DE QUARENTENA
  (Silver)     — registros reprovados + motivo detalhado do erro
   │
   ▼ [Cruzamentos espaciais/tabulares]
ZONA PUBLICADA (Gold) — indicadores prontos para consumo via API/Portal
```

### 2.1. Separação física/lógica das camadas — **[DECIDIDO]**
O Prof. Garcia, na aula gravada, recomendou **bancos distintos por camada** (facilita
gestão/manutenção isolada por etapa, evita comprometer todas as camadas numa manutenção).

**Decisão da equipe:** **uma única instância Oracle, com um schema por camada.** Motivos:
- O banco **é** Oracle (requisito), rodando localmente em container e depois numa instância
  gratuita (provável OCI). Rodar 4+ containers Oracle (um por camada) consome ~2 GB de RAM
  cada — inviável nos notebooks da equipe.
- Schema já entrega a fronteira de isolamento que o professor quer: grants por schema,
  backup/manutenção por schema, `ALTER SESSION SET CURRENT_SCHEMA`.
- **Transação cross-camada** (Bronze→Silver move dado entre schemas) fica trivial: 1
  datasource, 1 `@Transactional`, rollback atômico. Instâncias separadas exigiriam
  transação distribuída (XA) — complexidade grande para ganho pequeno.

**Schemas (7):**
| Schema | Conteúdo |
|---|---|
| `bronze` | `registro_bruto` (payload JSON cru) |
| `silver` | entidades canônicas de domínio (§2.5) |
| `gold` | indicadores versionados + linhagem (§2.6) |
| `quarentena` | `registro_quarentena` (§2.4) |
| `controle` | schema transversal do projeto (§2.7): `fonte_dado`, `lote_ingestao`, `mapeamento_coluna`, `pipeline_execucao`, `log_auditoria` |
| `quartz` | tabelas internas do Quartz JobStore (DDL `tables_oracle.sql` da distribuição) |
| `batch` | tabelas do Spring Batch (`BATCH_*`, DDL `schema-oracle.sql` do `spring-batch-core`) |

**Princípio de design travado:** o nome de cada schema é **configuração**
(`app.schema.bronze=BRONZE`, placeholders do Flyway `${bronzeSchema}` etc.), **nunca
hard-coded**. Mover uma camada para instância própria no futuro = trocar datasource em
config, não reescrever.

**Usuários Oracle / privilégios — [DECIDIDO, implementado e testado]:** um único usuário
conecta (o backend), os outros schemas só "possuem" tabelas:
- `CONTROLE` é o `APP_USER` criado pela própria imagem `gvenzl/oracle-free` (via env
  `APP_USER`/`APP_USER_PASSWORD`), com o papel `DB_DEVELOPER_ROLE` — **é quem o
  datasource do Spring Boot usa para conectar.**
- `BRONZE`, `SILVER`, `GOLD`, `QUARENTENA`, `QUARTZ`, `BATCH` são criados por
  `infra/oracle-init/01-criar-schemas.sh` (script custom rodado pelo entrypoint, uma vez,
  como SYS) — usuários sem uso de login direto, só donos de tabela.
- `CONTROLE` recebe privilégios `CREATE/ALTER/DROP ANY TABLE`, `SELECT/INSERT/UPDATE/DELETE
  ANY TABLE`, `CREATE ANY INDEX/SEQUENCE/TRIGGER/VIEW`, **`SELECT ANY SEQUENCE`** —
  permite criar e manipular objetos **qualificados** (`bronze.registro_bruto`) em
  qualquer schema do PDB.
- Adequado para **dev/acadêmico**; em ambiente real isso seria restrito por schema com
  roles dedicados em vez de privilégios `ANY`. Ver §4.6 para o compose completo.

**Duas pegadinhas de privilégio do Oracle, descobertas na prática (2026-09-04) —
guardar para não perder tempo de novo:**
1. **FK de um schema para outro** (ex.: `bronze.registro_bruto` → `controle.lote_ingestao`)
   **não** é coberta por `CREATE ANY TABLE`. Não existe `REFERENCES ANY TABLE` no Oracle
   (`ORA-00990` se tentar). O certo é `GRANT REFERENCES ON <tabela> TO <schema-filho>`,
   feito pelo **dono** da tabela referenciada — por isso esse grant vive dentro da
   **migration Flyway** que cria a tabela-pai (ex. `V001__criar_fonte_dado.sql`,
   `V002__criar_lote_ingestao.sql`), não no script de init (que roda antes de qualquer
   tabela existir). Cada nova FK cross-schema precisa do grant equivalente.
2. **`GENERATED ALWAYS AS IDENTITY` numa tabela criada via `CREATE ANY TABLE` falha**
   com `ORA-41900: missing READ privilege` mesmo com `CREATE ANY SEQUENCE` concedido —
   identity column cria uma sequence oculta (`ISEQ$$_...`) no schema dono, e o Oracle
   também exige **`SELECT ANY SEQUENCE`** pra sessão que está criando a tabela poder
   "ler" essa sequence. Sem isso, toda tabela com PK `IDENTITY` fora do schema `CONTROLE`
   quebra. Esse grant já está no `infra/oracle-init/01-criar-schemas.sh`.

### 2.1.1. Estratégia de migrations (Flyway) — **[DECIDIDO]**
- **Uma única instância de Flyway** (autoconfig do Spring Boot), com
  `spring.flyway.schemas=BRONZE,SILVER,GOLD,QUARENTENA,CONTROLE,QUARTZ,BATCH` e
  `default-schema=CONTROLE`. O Flyway cria os schemas que não existirem.
- Scripts organizados em subpastas por schema para leitura
  (`db/migration/controle/`, `db/migration/bronze/`, …), mas **numeração de versão é
  global e zero-padded** (`V001__`, `V002__`, …) — há um só `flyway_schema_history`, a
  ordem entre pastas precisa ser inequívoca.
- Cada script qualifica o schema via placeholder (`CREATE TABLE ${bronzeSchema}.registro_bruto …`).
- **Quartz e Spring Batch: DDL versionada como migration**, copiada dos jars
  (`schema-oracle.sql` / `tables_oracle.sql`) para `db/migration/batch/` e
  `db/migration/quartz/`. `spring.batch.jdbc.initialize-schema=never` e
  `spring.quartz.jdbc.initialize-schema=never` — nada de auto-criação (coerente com o
  ethos de migrations versionadas/reproduzíveis).
- Alternativa descartada por ora: um bean Flyway por schema (isola a linha de versão de
  cada camada, mas ~60 linhas de config e um conceito a mais). Voltar a considerar só se
  a linha de versão global incomodar.

### 2.2. Bronze — decisão importante: **schema genérico, não uma tabela por fonte**
Problema identificado: fontes diferentes (ex: embargos do IBAMA vs. embargos de órgão
estadual) têm colunas completamente diferentes. Criar uma tabela por fonte não escala.

**Decisão: Bronze é *schema-on-read*.** Uma única tabela genérica guarda o registro bruto
inteiro como JSON, sem tipagem, sem interpretação:

```sql
CREATE TABLE bronze.registro_bruto (
    id                NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lote_ingestao_id  NUMBER NOT NULL REFERENCES controle.lote_ingestao(id),
    fonte_dado_id     NUMBER NOT NULL REFERENCES controle.fonte_dado(id),
    numero_linha      NUMBER,           -- posição no arquivo original (rastreabilidade)
    payload           JSON NOT NULL,    -- registro inteiro, chaves = nomes de colunas originais
    geometria_wkt     CLOB,             -- WKT bruto como texto, se a fonte tiver geometria
    hash_registro     VARCHAR2(64),     -- para deduplicação
    data_ingestao     TIMESTAMP DEFAULT SYSTIMESTAMP
);
```

Exemplo real de `payload` (baseado no CSV `areas_embargadas.csv` do IBAMA, que tem 39
colunas: `SEQ_TAD`, `NUM_TAD`, `DAT_EMBARGO`, `SIG_UF_TAD`, `NOME_PESSOA_EMBARGADA`,
`CPF_CNPJ_EMBARGADO`, `WKT_GEOM_AREA_EMBARGADA`, `NUM_PROCESSO`, etc. — schema completo
disponível no arquivo original de upload):

```json
{
  "SEQ_TAD": "1829644", "NUM_TAD": "28855", "DAT_EMBARGO": "16/12/1987 15:40:00",
  "SIG_UF_TAD": "SC", "NOME_PESSOA_EMBARGADA": "DELZI MACHADO ALVES",
  "CPF_CNPJ_EMBARGADO": "75776849000150", "NUM_PROCESSO": "2026000823198742"
}
```

Datas ficam como string no formato original nesta camada — Bronze **não trata nada**,
só preserva com fidelidade e amarra a proveniência.

### 2.3. Tabela de mapeamento "de-para" (Bronze → Silver)
Em vez de fixar o mapeamento de colunas em código Java (quebra a cada fonte nova), o
de-para vira dado:

```sql
CREATE TABLE controle.mapeamento_coluna (
    id                         NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fonte_dado_id              NUMBER NOT NULL REFERENCES controle.fonte_dado(id),
    coluna_origem              VARCHAR2(200) NOT NULL,    -- chave no payload do Bronze (ex: 'num_tad')
    atributo_destino           VARCHAR2(100) NOT NULL,    -- coluna canonica da Silver (ex: 'numero_termo')
    destino_tipo               VARCHAR2(20)  NOT NULL,    -- COLUNA | JSON | GEOMETRIA
    tipo_dado                  VARCHAR2(30),              -- texto, numero, data_iso, epoch_millis, cpf_cnpj...
    obrigatorio                NUMBER(1) DEFAULT 0 NOT NULL,
    -- Regras de validacao (V029):
    valor_minimo               NUMBER,                    -- limite inferior numerico (sem teto maximo)
    tabela_referencia          VARCHAR2(60),              -- validacao de dominio/lookup (ex: 'situacao_imovel')
    referencia_entidade_silver VARCHAR2(60),              -- integridade relacional cruzada (ex: 'municipio')
    validar_cpf_cnpj           NUMBER(1) DEFAULT 0 NOT NULL, -- validacao algoritmica modulo 11
    validar_geometria          NUMBER(1) DEFAULT 0 NOT NULL, -- validade topologica e tipo primitivo
    validar_recorte_pr         NUMBER(1) DEFAULT 0 NOT NULL, -- coordenadas/poligono dentro do Parana
    impedir_regressao          NUMBER(1) DEFAULT 1 NOT NULL, -- campo preenchido nao pode regredir pra nulo
    regras_extras              JSON,                      -- regras customizadas de fontes desconhecidas
    data_add                   TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);
```

O pipeline Bronze→Silver lê essa tabela em runtime para saber como interpretar/converter
cada coluna e quais validações aplicar para desviar inconsistências para a Quarentena. Isola
a variação de schema e regras na tabela de mapeamento; a Silver continua com colunas fixas e tipadas.

### 2.4. Quarentena
```sql
CREATE TABLE quarentena.registro_quarentena (
    id                 NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    lote_id            NUMBER NOT NULL,
    registro_origem_ref NUMBER,          -- referência ao registro_bruto que falhou
    motivo_rejeicao    VARCHAR2(500),    -- texto descritivo (ex: "Geometria nula", "CPF inválido")
    tipo_erro          VARCHAR2(50),     -- enum: geometria_invalida | campo_obrigatorio_ausente | duplicidade | ...
    status_analise     VARCHAR2(30) DEFAULT 'PENDENTE',
    analista_responsavel_id NUMBER,
    data_add           TIMESTAMP DEFAULT SYSTIMESTAMP
);
```
Regra de negócio associada (RN007 do DRN): o sistema **nunca "inventa"/completa** dado
faltante automaticamente — só o Operador de Dados corrige manualmente.

### 2.5. Silver — **[MODELADA, DDL aplicada — ver `docs/modelagem-silver-gold-quarentena.md` e §9.11]**
> O esboço abaixo é histórico. O desenho vigente (DDL + seeds já no banco, `V011`-`V014`)
> está em `docs/modelagem-silver-gold-quarentena.md`. Resumo: Silver **por fenômeno, não
> por fonte** — `municipio`, `imovel_rural`, `area_protegida` (+bridge `area_protegida_uf`),
> `embargo_ambiental`, `auto_infracao`, `desmatamento`, `foco_calor`. Todo domínio de
> negócio é FK pra tabela de referência semeada; núcleo tipado + `atributos_origem JSON`;
> `geometria MDSYS.SDO_GEOMETRY` SRID 4674; linhagem (`fonte_dado_id`/`lote_ingestao_id`/
> `registro_bruto_id`) em toda linha, cross-schema sem FK. **Silver é current-state, não
> SCD.** Nenhuma transformação Bronze→Silver implementada ainda — só o schema.

Esboço original (não seguir como estado atual): entidades canônicas normalizadas via
`mapeamento_coluna` — `imovel_rural`, `area_restricao`, `lista_trabalho_escravo`,
`desmatamento_prodes`, `uso_solo_mapbiomas`.

**Reprojeção de SRID — [PROPOSTO]:** cálculo espacial direto no **4674 nativo** (Oracle
faz área/distância geodésicas corretas pra SRID geográfico — `SDO_GEOM.SDO_AREA`,
`SDO_ANYINTERACT`); reprojetar pra métrico (EPSG:5880) só se aparecer inconsistência.
Validar na fatia de cálculo.

### 2.6. Gold — **[MODELADA, DDL aplicada — ver `docs/modelagem-silver-gold-quarentena.md` e §9.11]**
> Desenho vigente (`V016`-`V020`) em `docs/modelagem-silver-gold-quarentena.md`. **A Gold
> deste projeto não é genérica — é a camada de produtos analíticos e de portal.** 3 blocos:
> **(1)** indicadores versionados (`regra_calculo`, `indicador_versao`, `indicador_resultado`,
> `indicador_versao_fonte`, `tipo_indicador`); **(2)** camadas de mapa publicadas
> (`mapa_camada` + `feature_publicada` genérica — todo polígono/ponto que o Leaflet
> desenha); **(3)** detalhe congelado por imóvel (`imovel_publicado` + `sobreposicao_
> publicada`, com `geometria_intersecao` em precisão total — artefato de auditoria).
> **O portal lê só a Gold, nunca a Silver.**

**Ponto crítico ainda em aberto:** o algoritmo de `hash_versao` (amarra dado-fonte +
regra + resultado) ainda não foi detalhado — a coluna existe, a lógica de cálculo não.

### 2.7. Schema de controle (transversal)
- `usuario`, `papel`, `usuario_papel` — RBAC (**adiado**, ver seção 4)
- `fonte_dado` — catálogo de fontes (ver seção 2.8, estendido para agendamento)
- `lote_ingestao` (fonte_dado_id, arquivo_origem, data_upload, usuário, hash_arquivo)
- `pipeline_execucao` — histórico de execuções (substitui o que seria "DAG run" do Airflow)
- `log_auditoria` — **append-only**, sem UPDATE/DELETE possível (RN025 do DRN)

### 2.8. `fonte_dado` — extensão para agendamento automático e ingestão genérica
Discutido: além do cadastro básico, a fonte de dado pode ter aquisição automatizada
(API ou download por URL) com frequência configurável.

**Estado real da tabela em 2026-09-09** (evoluiu por `V005__generalizar_fonte_dado.sql`,
já refletindo a arquitetura de ingestão genérica por formato — ver §5.8):

```sql
CREATE TABLE controle.fonte_dado (
    id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome                VARCHAR2(200) NOT NULL,
    orgao               VARCHAR2(200),
    tipo_aquisicao      VARCHAR2(20) NOT NULL, -- UPLOAD_MANUAL | API_REST | DOWNLOAD_URL
    url_origem          VARCHAR2(2000),        -- widened na V005; já leva todo filtro
                                                -- resolvido (CQL_FILTER, outSR, bbox...)
    cron_expressao      VARCHAR2(100),         -- só relevante se automatizado (Quartz
                                                -- ainda não lê este campo — ver §3.2)
    formato_esperado    VARCHAR2(30),          -- CSV | GEOJSON | SHAPEFILE | JSON
    campo_geometria_lat VARCHAR2(100),         -- só CSV com coordenada (ex.: focos de calor)
    campo_geometria_lon VARCHAR2(100),
    ativo               NUMBER(1) DEFAULT 1,   -- Java usa @Convert(NumericBooleanConverter)
    ultima_execucao_em  TIMESTAMP,
    proxima_execucao_em TIMESTAMP,
    data_add            TIMESTAMP DEFAULT SYSTIMESTAMP
);
```

CRUD completo já existe: `POST/GET/PUT /api/fontes`, `GET /api/fontes/{id}` — ver §5.8.

**Risco que ERA aberto, agora resolvido:** detecção de "a fonte mudou desde a última
execução" — implementado via hash do conteúdo relevante da resposta (não do envelope
inteiro — ver a pegadinha do `timeStamp` do WFS registrada em §9.5/§5.8) comparado ao
hash do último `lote_ingestao` da mesma fonte. Funciona igual para `API_REST` e (em
tese) `DOWNLOAD_URL`; `cron_expressao`/agendamento automático via Quartz **ainda não foi
ligado** — hoje toda execução é disparo manual via `POST /api/fontes/{id}/executar`.

---


## 3. Decisões de Infraestrutura e Orquestração

### 3.1. Sem Apache Airflow — **[DECIDIDO pela equipe]**
Apesar de o documento de requisitos original e o DRN (RNF006) mencionarem Airflow como
tecnologia obrigatória, **a equipe decidiu não usá-lo** — "tudo será construído" dentro
do próprio backend Spring Boot. O Julião (Visiona), no Q&A do kickoff, abriu margem para
isso: *"Não é uma trava tecnológica... se a solução for equivalente e justificada, podemos
avaliar."*

Consequência prática: o próprio Spring Boot assume o papel de orquestrador — não há um
motor externo coordenando Bronze→Silver→Gold. A tabela `pipeline_execucao` é a fonte de
verdade do estado do pipeline (não um espelho de metadados do Airflow).

### 3.2. Agendamento dinâmico de ingestão — **[DECIDIDO: Quartz Scheduler]**
Necessidade: cron configurável **por fonte de dado**, editável pelo usuário em tela,
sobrevivendo a restart da aplicação.

- `@Scheduled`/`TaskScheduler` puro do Spring foi **descartado** — cron fixo em tempo de
  compilação, não serve para N crons dinâmicos definidos em runtime; exigiria bookkeeping
  manual de `ScheduledFuture` por fonte e reconstrução manual no boot.
- **Escolhido: Quartz Scheduler** (`spring-boot-starter-quartz`) — suporta `CronTrigger`
  dinâmico (criar/atualizar/remover em runtime via `scheduler.rescheduleJob(...)`), com
  `JobStore` persistido em banco (`quartz-jobstore-jdbc`), sobrevivendo a restarts.
  Confirmar suporte oficial do Quartz JobStore para Oracle antes de gerar o schema dele.
- **Status real em 2026-09-09: ainda não ligado.** A dependência está no `pom.xml` e o
  contexto Spring sobe com ela sem conflito (confirmado contra o Oracle real), mas
  nenhum `Job`/`Trigger` foi criado, o schema `quartz` (§2.1) segue vazio (sem as
  migrations `tables_oracle.sql`) e `fonte_dado.cron_expressao` não é lido por
  ninguém ainda. Toda execução de ingestão hoje é **disparo manual** via
  `POST /api/fontes/{id}/executar` (§5.8). Ligar o Quartz de verdade é o próximo passo
  óbvio de agendamento, não feito ainda.

### 3.3. Orquestração do pipeline — **[DECIDIDO: Spring Batch]**
Sem Airflow, o pipeline Bronze→Silver→Gold precisa de reinício-do-meio, processamento em
lote, retry e desvio de registro ruim. O **Quartz só resolve "quando"**; quem resolve
"como rodar de forma reprocessável" é o **Spring Batch** (`spring-boot-starter-batch`).

- **Quartz dispara o Job do Spring Batch.**
- Spring Batch dá de fábrica: `Job`/`Step`, chunk (lê N, processa, grava, commit),
  **restart** de execução falha (estado nas tabelas `BATCH_*`), `SkipListener` que joga
  a linha reprovada direto na `quarentena` e segue.
- **Relação com `pipeline_execucao`:** as tabelas `BATCH_*` são o registro de baixo
  nível (framework). `controle.pipeline_execucao` continua sendo a visão de negócio
  (qual fonte, qual lote, qual transição de camada, status para UI/auditoria), amarrada
  ao Batch por um `job_execution_id`. Não tentar servir a tela de auditoria direto das
  tabelas do Batch.
- Confirmar no spike (§5.x) que Spring Batch 6 roda liso no Boot 4.1.1.
- **Status real em 2026-09-09: mesma situação do Quartz — dependência presente, contexto
  sobe sem conflito, mas nenhum `Job`/`Step` foi escrito e o schema `batch` segue vazio.**
  Hoje `LoteIngestaoWriter` (§5.8) faz esse papel na marra: grava registro a registro
  (sem chunk), sem retry nem skip automático de linha ruim — a resiliência que existe
  (geometria nula/malformada não derruba o lote — §9.10) foi implementada manualmente no
  executor, não via Spring Batch. Migrar pra `Job`/`Step`/`SkipListener` de verdade é
  trabalho futuro, não uma reescrita urgente — o comportamento atual já cobre a fatia 1.

### 3.4. Nuvem / hospedagem — **[ADIADO]**
Decisão da equipe: **não definir onde hospedar agora.** Certezas: o banco **é** Oracle
(não trocamos por Postgres/outro); a instância gratuita provável é **OCI** (Always Free
— Autonomous Database com Spatial, ou VM ARM Ampere rodando o mesmo compose). A escolha
de topologia (VM única com compose vs. ADB gerenciado + VM só do app) fica para quando
deploy virar assunto. As decisões §2.1 (schema parametrizado) e §2.1.1 (Flyway) são o que
mantêm essa troca barata.

---

## 4. Stack Técnica e Configuração do Projeto

### 4.1. Stack obrigatória
- **Backend:** Java + Spring Boot
- **Persistência:** Spring Data JPA / Hibernate
- **Banco:** Oracle Database com Oracle Spatial (SDO_GEOMETRY)
- **Frontend:** **Vue 3 + Vite** + Axios + Leaflet (mapas). Mora em `frontend/` na raiz
  do repo (monorepo). Ainda não iniciado. **Atenção:** `frontend/` está no `.gitignore` —
  remover essa linha ANTES de rodar `npm create vite` lá, senão o front não é versionado.
- **Padrão de nomenclatura:** `snake_case` no banco, incluindo campos de auditoria
  (`data_add`, `data_alt`)

### 4.2. Configuração do Spring Initializr — **[DECIDIDO, projeto já sendo gerado]**
| Campo | Valor |
|---|---|
| Project | Maven |
| Language | Java |
| Spring Boot | **4.1.1** |
| Group | `br.gov.sp.cps` |
| Artifact | `datagis` |
| Package name | `br.gov.sp.cps.datagis` |
| Packaging | Jar |
| Configuration | Properties |
| Java | **21** |

**Atenção:** Spring Boot 4.1.1 roda sobre Spring Framework 7 + Jakarta EE 11 +
Hibernate ORM 7.4 + Jackson 3. Isso invalida versões "clássicas" de algumas libs
de terceiros — ver seção 4.4.

### 4.3. Dependências a marcar no Initializr
- Spring Web
- Spring Data JPA
- Spring Security *(mantido no `pom.xml`, mas com `SecurityFilterChain` provisória
  liberando tudo — ver seção 5.2; não remover a dependência para evitar reconfigurar
  Maven depois)*
- Validation
- Spring Boot Actuator
- Oracle Driver
- Flyway Migration
- Lombok
- Testcontainers (módulo `oracle-free` deve ser adicionado manualmente depois)
- **Quartz Scheduler** (`spring-boot-starter-quartz`) — necessário assim que a fatia de
  agendamento automático de fontes for construída (ver seção 3.2)

### 4.4. Dependências adicionadas manualmente no `pom.xml` — **[FEITO E VALIDADO em 2026-09-04]**
Todas resolvidas com sucesso via `./mvnw dependency:tree` (sem conflito reportado):

```xml
<properties>
    <geotools.version>35.1</geotools.version>  <!-- checar por versao mais nova periodicamente -->
    <jts.version>1.20.0</jts.version>
</properties>

<!-- Suporte a SDO_GEOMETRY no JPA/Hibernate -->
<!-- Sem <version> — o BOM do Spring Boot 4.1.1 resolveu para hibernate-spatial 7.4.5.Final -->
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-spatial</artifactId>
</dependency>

<!-- Operações geométricas: interseção, validação topológica, parsing WKT -->
<dependency>
    <groupId>org.locationtech.jts</groupId>
    <artifactId>jts-core</artifactId>
    <version>${jts.version}</version>
</dependency>

<!-- Leitura de Shapefile/GeoJSON de fontes futuras (não a 1ª, CAR é GeoJSON puro via
     Jackson — ver §5.6). Resolveu limpo na árvore de dependências; falta ainda o teste
     funcional de ler um .shp de verdade (spike, §5.5). -->
<dependency>
    <groupId>org.geotools</groupId>
    <artifactId>gt-shapefile</artifactId>
    <version>${geotools.version}</version>
</dependency>
<dependency>
    <groupId>org.geotools</groupId>
    <artifactId>gt-geojson</artifactId>
    <version>${geotools.version}</version>
</dependency>

<!-- Swagger/OpenAPI — precisa ser a linha 3.x, a 2.x NÃO suporta Spring Boot 4 -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <version>3.0.3</version>
</dependency>

<!-- Agendamento dinâmico (§3.2) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-quartz</artifactId>
</dependency>

<!-- Orquestração reprocessável do pipeline (§3.3) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-batch</artifactId>
</dependency>
```

Repositório Maven adicional exigido pelo GeoTools (não está no Central):
```xml
<repositories>
    <repository>
        <id>osgeo</id>
        <name>OSGeo Release Repository</name>
        <url>https://repo.osgeo.org/repository/release/</url>
    </repository>
</repositories>
```

**Versões reais resolvidas** (`mvn dependency:tree`): `hibernate-spatial 7.4.5.Final`,
`spring-boot-quartz 4.1.1` → `quartz 2.5.2`, `spring-boot-batch 4.1.1` →
`spring-batch-core 6.0.5`, `geotools 35.1`, `jts-core 1.20.0`,
`springdoc-openapi 3.0.3`. **`BUILD SUCCESS`, zero conflito de versão reportado** — é um
bom sinal pro spike, mas só a *resolução* de dependência foi validada aqui; falta ainda o
teste funcional (Hibernate salvando `SDO_GEOMETRY` de verdade, GeoTools lendo um `.shp`,
Spring Batch rodando um job trivial) — isso é o próprio spike, §5.5, ainda não feito.

### 4.5. Estado atual do `pom.xml`
Commitado. Presentes: `webmvc`, `data-jpa`, `flyway` + `flyway-database-oracle`,
`security`, `validation`, `actuator`, `lombok`, `ojdbc17` (runtime), Testcontainers
(`testcontainers-oracle-free`, `spring-boot-testcontainers`, `junit-jupiter`) +
starters `-test` granulares do Boot 4, mais tudo do §4.4 acima.

- **`websocket` — [REMOVIDO]** (commit `bc9eae6`). Veio no Initializr por engano. Se
  precisar de progresso de pipeline em tempo real, usar **SSE** (`SseEmitter`, sem
  starter). WebSocket só se surgir necessidade de bidirecional.

### 4.6. Ambiente de desenvolvimento em containers — **[DECIDIDO, implementado e testado end-to-end]**
Dev roda **3 containers via `docker compose`**: `oracle` + `backend` + `frontend`.
Testado em 2026-09-02 no WSL: os 3 sobem, Oracle fica `healthy`, backend conecta e
migra Flyway em `CONTROLE`, `curl localhost:8080/actuator/health` → `200 UP`, Vite serve
em `localhost:5173` e o proxy `/api` alcança o backend pela rede Docker.

**Arquivos:**
| Arquivo | Papel |
|---|---|
| `docker-compose.yml` (raiz) | define os 3 serviços |
| `Dockerfile.dev` (raiz) | imagem dev do backend: `eclipse-temurin:21-jdk-jammy` + `./mvnw` |
| `datagis-frontend/Dockerfile.dev` | imagem dev do frontend: `node:22-slim` + `npm run dev` |
| `infra/oracle-init/01-criar-schemas.sh` | cria os 6 schemas extras + grants `ANY` ao `CONTROLE` (ver §2.1) |
| `.env.example` (raiz, rastreado) | modelo de variáveis |
| `.env` (raiz) | **[DECIDIDO 2026-09-09] passou a ser rastreado no git, de propósito** — ver nota abaixo |

**`frontend/` renomeado para `datagis-frontend/`** (commit `DATAGIS-17`, 2026-09-07) —
mesmo princípio de antes (repositório git próprio, totalmente fora do repo do backend),
só o nome da pasta mudou; `docker-compose.yml` já aponta pro caminho novo. As duas
entradas (`frontend/` e `datagis-frontend/`) seguem no `.gitignore` do backend.

**`.env` commitado — mudança de decisão, deliberada:** a linha que ignorava `.env` foi
comentada no `.gitignore` (`DATAGIS-17`) e o arquivo real (com as senhas de
desenvolvimento do Oracle) está no repositório desde 2026-09-07. **Confirmado como
decisão consciente da equipe, não acidente:** essas credenciais valem só para o Oracle
local do `docker compose` (nunca saem da máquina de quem sobe o ambiente); quando a
hospedagem em nuvem entrar (§3.4), as credenciais de lá são outras e não vêm daqui. Isso
inverte a recomendação genérica de "nunca commitar `.env`" citada em versões anteriores
deste arquivo — mantendo aqui só pra registrar que foi avaliado e decidido, não
esquecido.

- **Modo dev, não "empacotado":** backend e frontend rodam a toolchain
  (`./mvnw spring-boot:run`, `npm run dev`) com o **código-fonte montado do host**
  (bind mount). Nada de código dentro da imagem.
- **Dockerfile multi-stage (build imutável) fica para quando hospedagem entrar** (§3.4).
  Barato de escrever depois; opcionalmente o CI compila cedo para pegar quebra.
- `oracle`: imagem `gvenzl/oracle-free:23.26.3-faststart` (tag exata pinada — checar tags novas
  periodicamente em hub.docker.com/r/gvenzl/oracle-free/tags). **NÃO usar o `-slim`**:
  ele remove o Oracle Spatial, e a camada Silver usa `SDO_GEOMETRY` (SRID 4674) — com
  `-slim` as migrations `V012+` quebram com `ORA-00902: invalid datatype` (descoberto
  2026-09-10 ao criar a Silver). Era `-slim` até então porque nenhuma tabela usava
  SDO_GEOMETRY ainda. Volume nomeado
  `oracle-data` para datafiles, `infra/oracle-init/` montado em
  `/container-entrypoint-initdb.d`, `healthcheck: healthcheck.sh` (nativo da imagem).
  `backend` usa `depends_on: { oracle: { condition: service_healthy } }`.
- `backend`: volume nomeado `maven-repo` → `/root/.m2` (cache de dependência entre subidas).
  `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` vêm direto do `.env` via `environment:` do
  compose — Spring Boot faz o binding automático, **sem precisar de
  `application-docker.properties`** por enquanto.
- `frontend`: `node_modules` em **volume nomeado** `frontend-node-modules` (nunca bind
  mount do host); comando **sempre roda `npm install`** antes do `npm run dev`
  (`sh -c "npm install; npm run dev -- --host 0.0.0.0"` — **[CORRIGIDO 2026-09-09]**,
  era `[ -d node_modules/vite ] || npm install`, que só instalava na primeira vez;
  quebrou quando `vue-router` foi adicionado ao `package.json` depois do volume já ter
  `vite` — o container nunca rodava o install de novo e a tela quebrava com "Failed to
  resolve import vue-router". `npm install` sem nada novo pra instalar é rápido
  (<1s), então rodar sempre não pesa no dia a dia). Vite com `server.proxy` mandando
  `/api` → `http://backend:8080` (sem CORS em dev, sem `rewrite` — os controllers já
  nascem com `/api/...` no `@RequestMapping`, ex. §5.2).
- Profiles Spring: `dev` (backend na IDE do host → `localhost:1521`) e `docker` (dentro
  do compose → host `oracle`, já setado via `SPRING_PROFILES_ACTIVE` no compose). Os dois
  cenários funcionam sem trocar código.
- **Subir/derrubar:** `docker compose up -d` (tudo) ou `--build` após mudar Dockerfile;
  `docker compose down` (mantém volumes) / `down -v` (apaga também os dados do Oracle —
  reseta os schemas, útil se o init script mudar).

**Cuidados de WSL (toda a equipe está em WSL/Ubuntu):**
- **Repositório vive no filesystem do WSL** (`~/projetos/datagis-backend` — pasta
  renomeada desde a versão anterior deste arquivo), nunca em `/mnt/c/...`
  — bind mount de `/mnt/c` para container é I/O lento.
- File watching por **polling**: `server.watch.usePolling: true` já configurado no
  `vite.config.js`; considerar o mesmo para DevTools do backend quando ele entrar.
- Fim de linha: `.sh`/entrypoints precisam ser **LF** (CRLF quebra em container Linux —
  `bad interpreter`) — `.gitattributes` já cobre isso nos dois repositórios (backend e
  frontend). **Atenção:** `core.autocrlf` global também importa — se estiver `true`
  (herança de máquina Windows), reescreve os arquivos em CRLF no checkout mesmo com
  `.gitattributes` correto; deixar em `input` ou `false` numa máquina só-WSL.

---

## 5. Estratégia de Desenvolvimento

### 5.1. Fatias verticais — **[DECIDIDO]**
A equipe optou por desenvolver por **fatias verticais de funcionalidade completa**
(entidade → repositório → service → controller → teste → **tela Vue quando fizer
sentido**), uma de cada vez, em vez do padrão "todo o backend, depois todo o frontend".
Sempre que a fatia tiver algo demonstrável na interface, o frontend correspondente entra
na mesma fatia.

### 5.2. Escopo da primeira fatia — **[DECIDIDO, HISTÓRICO — superado pela §5.8]**
> Esta seção documenta a decisão original da fatia 1 (CAR/PR apenas). Em 2026-09-08/09
> essa arquitetura foi generalizada para qualquer fonte GeoJSON ou CSV cadastrada — ver
> **§5.8, que é o desenho vigente**. Mantido aqui por valor histórico/rastreabilidade da
> decisão; não usar como referência do estado atual do código.

Prioridade explícita da equipe: **acessar o sistema e salvar dados brutos, e somente
isso.** Login, RBAC completo e tela de cadastro de fontes ficam para depois.

**Fonte única desta fase: CAR (SICAR), obtida por requisição WFS** — ver §5.6. Nada de
upload de arquivo por ora; a primeira fonte é uma API.

**Recorte territorial oficial: Paraná (PR)** — corrigido em 2026-09-04 a partir do
desafio formal da Visiona (`Desafio_API_4BD_2026-2_GeoRural_DataHub_Apresentacao_v1.pdf`).
399 municípios, ~199 mil km², bioma Mata Atlântica predominante (RL mínima 20%, não a
regra da Amazônia Legal). **SP foi a hipótese usada antes do desafio chegar — descartada.**

**"Circuito" menor de municípios, expansível — [DECIDIDO]:** para a fatia 1 e para a
massa de homologação inicial, ingerir só um subconjunto pequeno do PR (o slide 2 do
desafio autoriza explicitamente: "a massa de homologação pode cobrir apenas alguns
municípios, desde que o fluxo completo seja demonstrado de ponta a ponta"). Proposta
inicial de circuito (números reais, verificados em 2026-09-04):

| Município | Código IBGE | Imóveis CAR |
|---|---|---|
| Curitiba | 4106902 | 111 |
| Maringá | 4115200 | 1.831 |
| Ponta Grossa | 4119905 | 2.648 |

Mecanismo: `CQL_FILTER=cod_municipio_ibge IN (4106902,4115200,4119905)` (testado,
funciona). **A lista de códigos é configuração** (mesmo princípio de nomes de schema
parametrizados, §2.1) — expandir o circuito depois é editar a lista, não o código.

Escopo mínimo combinado:
- `POST /api/ingestao/car/pr` (ou `POST /api/ingestao/executar?fonte=CAR_PR`) — dispara a
  ingestão: faz `GetFeature` no WFS do SICAR para os municípios do circuito, com `count`
  pequeno nesta fatia (**sem paginação ainda** — o circuito inteiro cabe numa página só).
- Cria um `lote_ingestao` (fonte = CAR, com o hash do corpo recebido).
- Percorre as `features` do GeoJSON e grava cada uma como `registro_bruto`:
  `payload` = objeto `properties` da feature; `geometria_wkt` = geometria da feature
  convertida para WKT (texto, sem validar); `numero_linha` = índice da feature.
- Responde com `lote_ingestao_id` e contagem de features salvas.
- **Sem** validação de geometria, **sem** quarentena, **sem** reprojeção, **sem**
  cálculo de indicador, **sem** paginação, **sem** agendamento Quartz — só ingestão
  bruta pura de uma página.
- **Frontend da fatia:** tela Vue mínima com um botão "Ingerir CAR/PR" que chama o
  endpoint (via proxy do Vite) e mostra `lote_ingestao_id` + contagem. Sem login.
- **Testes:** não bater no servidor real — usar um GeoJSON de amostra (fixture estática
  ou WireMock) gravado a partir de uma chamada real com `count` baixo.

**Dependência resolvida para não travar essa fatia:** `fonte_dado` populada por seed do
Flyway (sem endpoint de CRUD):

```sql
-- db/migration/controle/V0NN__seed_fonte_dado_car.sql
INSERT INTO fonte_dado (nome, orgao, tipo_aquisicao, url_origem, formato_esperado, ativo)
VALUES ('CAR - Imóveis (PR)', 'SFB/SICAR', 'API_REST',
        'https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr&outputFormat=application/json',
        'GEOJSON', 1);
```

Quando o cadastro de fontes for implementado, o seed vira dado real da tela — a
estrutura não muda.

### 5.6. Fonte CAR / SICAR via WFS — **[DECIDIDO como 1ª e única fonte da fase]**
Endpoint de teste (funciona no navegador / `curl`):
```
https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr&outputFormat=application/json&count=10000
```
- **WFS 2.0.0**, GeoServer do SICAR. `typeNames=sicar:sicar_imoveis_pr` = imóveis rurais
  do **Paraná**. Saída `application/json` = **GeoJSON `FeatureCollection`**.
- **Verificado em 2026-09-04** (`resultType=hits` + amostra):
  - **`numberMatched = 556.563`** feições para o PR (todo o estado).
  - **CRS = `urn:ogc:def:crs:EPSG::4674`** (SIRGAS 2000) → **já é o SRID-alvo da RN013,
    esta fonte não precisa de reprojeção.**
  - Geometria: **`MultiPolygon`**. Não há campo WKT — a geometria vem como objeto
    GeoJSON; Bronze converte para WKT texto em `geometria_wkt`.
  - `properties` (enxuto, idêntico entre UFs): `cod_imovel` (chave natural, ex.
    `PR-4106902-00B2EA81...`; embute UF + código IBGE do município — boa base de
    dedup/hash), `status_imovel` (`AT`), `dat_criacao` (ISO), `area` (ha, número),
    `condicao` (texto), `uf`, `municipio`, `cod_municipio_ibge` (número), `m_fiscal`
    (módulos fiscais), `tipo_imovel` (`IRU`).
  - **27 layers no workspace `sicar:`, uma por UF** (`sicar_imoveis_ac` até `_to`) — **só
    o perímetro do imóvel**, não há layer pública de APP/RL/vegetação nativa nesse WFS
    (ver gap documentado em `docs/fontes-de-dados.md`).
- **Filtro por município**: `&CQL_FILTER=cod_municipio_ibge IN (...)` — testado e
  funcionando (ver tabela do circuito em §5.2).
- **Paginação** (fatia futura, se o circuito crescer muito): WFS 2.0 aceita `count` +
  `startIndex`; iterar até `numberReturned < count`.
- **Parsing:** ler com **Jackson 3** (já no projeto) + um mapper GeoJSON→JTS pequeno para
  esta fonte (é GeoJSON puro, não precisa de GeoTools). `gt-shapefile`/`gt-geojson`
  seguem no `pom.xml` (§4.4) para as futuras fontes que vierem em Shapefile (IBGE,
  ICMBio, FUNAI, IBAMA — ver `docs/fontes-de-dados.md`).
- `tipo_aquisicao = API_REST`; `cron_expressao` só quando a fatia de agendamento
  (Quartz) entrar. O hash do corpo recebido alimenta a detecção de "mudou desde a
  última execução" (§2.8 / RN018).

### 5.7. Catálogo de fontes de dados do desafio — **[ver `docs/fontes-de-dados.md` (v1) e `docs/fontes-de-dados-v2.md` (v2)]**
Documentos dedicados (não neste arquivo, pelo tamanho) com o levantamento e teste real
de **todos os endpoints/serviços citados na página "Dados necessários, fontes e
formatos" do desafio oficial da Visiona**.

- **v1 (2026-09-04):** primeiro levantamento — CAR, IBGE (malha e biomas), ICMBio/MMA
  (UCs), FUNAI (Terras Indígenas), INCRA, IBAMA (embargos), INPE PRODES (desmatamento) e
  INPE Queimadas (focos de calor). 8 de 10 fontes com caminho técnico confirmado.
- **v2 (2026-09-08):** aprofundamento — mais fontes reais via geoportal do PR (IAT/PR,
  ArcGIS REST), incluindo Embargos/Autos de Infração (IBAMA e ICMBio), Malha Municipal,
  Terras Indígenas via fonte alternativa, PRODES Mata Atlântica. Descobriu também a API
  oficial do CAR para APP/RL (`api-sicar-tema`), que existe mas exige credenciamento
  OAuth2 + allowlist de IP no Serpro — **fora do alcance da equipe sem apoio externo**.
- **`docs/checklist-cobertura-fontes.md` (2026-09-08):** cruzamento linha a linha da
  tabela oficial do desafio contra v1+v2, com veredito honesto por severidade. **Gaps
  reais que seguem sem solução:** APP declarada e Reserva Legal do CAR (IAPP e IRL não
  têm fonte de dado funcionando até hoje, por causa do bloqueio de credenciamento acima).
  Demais linhas: atendidas de forma exata ou com formato de acesso equivalente
  (ex.: GeoJSON via API no lugar de `.shp` estático — mesma fonte/órgão, canal diferente).

### 5.8. Arquitetura de ingestão genérica por formato — **[DECIDIDO e implementado, 2026-09-08/09 — substitui a lógica CAR-específica da §5.2/§5.6]**

A fatia 1 (§5.2) nasceu com um `IngestaoCarService` que só sabia processar o CAR. Ele
foi generalizado em duas etapas (`DATAGIS-14` e `LIZARDS-5`) até virar: **qualquer fonte
cadastrada com `formatoEsperado` conhecido roda sem escrever código novo.**

**Camadas (pacote `br.gov.sp.cps.datagis.ingestao`):**

| Classe | Papel |
|---|---|
| `IngestaoExecutor` | Interface — `suporta(FonteDado)` e `executar(FonteDado)`. Cada implementação decide sozinha se sabe processar aquela fonte. |
| `IngestaoExecucaoService` | Ponto único de disparo (`POST /api/fontes/{id}/executar`): carrega a `FonteDado`, acha o executor certo entre os registrados (Spring injeta a lista), atualiza `ultima_execucao_em`. Fonte sem executor compatível → `400` com mensagem clara, nunca `500`. |
| `IngestaoGeoJsonExecutor` | Suporta `formatoEsperado=GEOJSON` — cobre WFS (GeoServer) **e** ArcGIS REST (que já devolve GeoJSON padrão com `f=geojson`, sem parser próprio). Busca **todas as páginas** antes de gravar (ver `PaginadorUrlGeoJson`). |
| `IngestaoCsvExecutor` | Suporta `formatoEsperado=CSV` — parser que respeita aspas/vírgula dentro de campo; geometria opcional via `campo_geometria_lat`/`campo_geometria_lon` (§2.8). |
| `PaginadorUrlGeoJson` | Detecta a família do serviço **pela própria URL cadastrada** — WFS (`service=WFS`, pagina com `startIndex`+`maxFeatures`) ou ArcGIS REST (`FeatureServer`/`MapServer`, pagina com `resultOffset`+`resultRecordCount`). Família desconhecida (ex.: arquivo estático) não tenta paginar o que não dá pra paginar. |
| `HttpFetchClient` | Generaliza o antigo `CarWfsClient` — URL é parâmetro, não fixa no CAR; User-Agent de navegador por padrão (necessário pra fontes com WAF, ex. FUNAI — ver `docs/fontes-de-dados.md`). |
| `LoteIngestaoWriter` | Ciclo de vida comum a qualquer formato: hash do conteúdo, detecção de "sem mudança" (§2.8), criação do `lote_ingestao`, gravação registro a registro em `registro_bruto`, fechamento do lote. |
| `GeoJsonWktMapper` | GeoJSON→WKT em JTS puro (sem GeoTools). Suporta `Polygon`/`MultiPolygon`/`Point`/`MultiPoint` (os dois últimos entraram ao vivo testando focos de calor). |
| `RegistroPreparado` / `IngestaoResultado` | DTOs internos do pipeline de ingestão. |

**Resiliência — 3 bugs achados ao vivo contra fontes reais, não em teste unitário:**
1. **Geometria ausente não é erro.** Feature sem coordenada (ex.: autuação do IBAMA sem
   georreferência) grava o registro normalmente, só sem `geometria_wkt`.
2. **Geometria malformada não derruba o lote inteiro.** Um polígono nulo dentro de um
   `MultiPolygon` (achado nos Embargos ICMBio) lançava `NullPointerException` — **não**
   era `IllegalStateException`, então nem o `TratadorDeErro` pegava, virava `500` cru do
   Spring. Corrigido: falha ao converter geometria → grava sem WKT, mesmo princípio do
   item 1, não derruba o lote.
3. **Paginação de verdade.** Sem paginar, cada servidor aplicava seu próprio limite
   (ArcGIS corta em ~2000 por padrão) e a ingestão perdia o resto **silenciosamente**.
   Confirmado ao vivo nos Embargos IBAMA (IAT/PR): faltavam ~113 de 2.113 registros
   antes da correção.
4. **Retentativa antes de reduzir tamanho de página.** Falha intermitente de servidor
   (confirmado no "Embargos ICMBio": falhou, minutos depois funcionou de primeira com a
   mesma URL) → tenta de novo no mesmo tamanho antes de reduzir pela metade (isso cobre
   servidor que não aguenta converter muita geometria pesada numa página só, achado na
   Malha Municipal do IBGE/IAT-PR, que quebra com HTTP 500 acima de ~200-500 polígonos).

**`TratadorDeErro`** (`@RestControllerAdvice`, pacote `config`) transforma
`IllegalArgumentException` em `400` e `IllegalStateException` (falha de ingestão) em
`502`, sempre com a mensagem real no corpo — antes disso, qualquer falha de ingestão
virava `500` genérico do Spring sem detalhe nenhum, escondendo qual fonte falhou e por quê.

**Fontes semeadas hoje (migrations V006/V009), todas rodando pelo executor GEOJSON
genérico, sem código específico por fonte:** CAR/PR, ICMBio (UCs), PRODES Mata Atlântica,
Focos de Calor (via geoportal PR), Embargos IBAMA e ICMBio (via IAT/PR, ArcGIS REST),
Autos de Infração IBAMA e ICMBio, Malha Municipal IBGE, Terras Indígenas FUNAI (via
IAT/PR) — mais 1 CSV do INPE semeado só pra provar o caminho `IngestaoCsvExecutor`
(152 mil linhas, validado por teste unitário, não rodado ao vivo por desproporção de
volume sem paginação de CSV ainda).

**CRUD de fontes agora completo:** `POST`, `GET` (lista), `GET /{id}`, `PUT /{id}` —
fonte cadastrada é editável (útil pra corrigir URL/circuito sem nova migration).

**Testes:** 36 automatizados (`GeoJsonWktMapperTest`, `IngestaoCsvExecutorTest`,
`IngestaoGeoJsonExecutorTest`, `LoteIngestaoWriterTest`, `PaginadorUrlGeoJsonTest`,
`TratadorDeErroTest`, `FonteDadoServiceTest`), `BUILD SUCCESS`.

### 5.3. Spring Security — bypass temporário
A dependência `spring-boot-starter-security` fica no projeto (para não reconfigurar
Maven depois), mas com uma configuração provisória liberando tudo, marcada para
remoção quando RF001/RN008-012 forem implementados:

```java
@Bean
SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
        .csrf(csrf -> csrf.disable());
    return http.build();
    // TODO: RN008 - remover ao implementar RBAC (Administrador/Operador/Analista/Auditor)
}
```

### 5.4. Armazenamento do arquivo bruto — **[DECIDIDO]**
O upload da fatia 1 grava o arquivo original em algum lugar. Para não amarrar o código a
"pasta no disco", criar interface fina:

```java
interface ArmazenamentoArquivoBruto {
    String salvar(String nome, InputStream conteudo);  // devolve um "endereço"
    InputStream ler(String endereco);
}
```

**Agora: só a implementação local-FS** (`@Profile` selecionando). Implementação de bucket
(OCI Object Storage / GCS) só vira fatia se e quando deploy exigir — se a nuvem gravar
numa pasta de Block Volume, local e nuvem ficam idênticos e o bucket nem entra.

**Zona Bronze continua em tabela Oracle (não em Object Storage) — [DECIDIDO 2026-09-04]**
O desafio oficial (slide de RNFs) fala em "armazenamento de objetos nas quatro zonas" do
GeoDataLake, o que sugere arquivo em bucket em vez de tabela. A equipe decidiu **manter
como já estava**: `bronze.registro_bruto` é tabela Oracle com coluna `JSON` (§2.2), não
arquivo em bucket — o diagrama de arquitetura já aprovado pela Visiona usa ícones
genéricos de banco de dados por camada, o que sustenta essa leitura. A interface acima
(`ArmazenamentoArquivoBruto`) continua existindo só para o arquivo bruto ORIGINAL (o
`.zip`/`.geojson` como baixado, se um dia precisar guardá-lo à parte do payload
estruturado) — não é o mecanismo de armazenamento da zona Bronze em si.

### 5.5. Spike de compatibilidade — **[PARCIALMENTE FEITO, atualizado 2026-09-09]**
½–1 dia, uma pessoa, branch descartável. Objetivo: provar que a stack bleeding-edge
(Boot 4.1.1 / Framework 7 / Jakarta 11 / Hibernate 7.4 / Jackson 3) funciona ANTES de
construir em cima:
1. ✅ `mvn dependency:tree` — feito em 2026-09-04 (§4.4): **sem conflito de versão
   reportado**, todas as libs (hibernate-spatial, GeoTools 35.1, jts-core 1.20.0,
   springdoc 3.0.3, Quartz, Spring Batch) resolveram limpo.
2. ❓ **Não é mais necessário do jeito que foi desenhado.** `RegistroBruto.geometriaWkt`
   é `CLOB`/texto (§2.2, decisão consciente — Bronze não tipa geometria), não
   `SDO_GEOMETRY`. `hibernate-spatial` está no `pom.xml` e o contexto sobe com ele sem
   conflito (confirmado contra o Oracle real, DATAGIS-14), mas **nenhum código do
   projeto até hoje mapeia uma coluna `SDO_GEOMETRY`** — o round-trip real só vira
   obrigatório quando a Silver (§2.5) nascer com colunas espaciais tipadas.
3. ⬜ 1 teste: GeoTools abre um `.shp` pequeno e lê a primeira feição — **ainda não
   necessário**: todas as fontes reais ligadas até hoje são GeoJSON ou CSV (§5.8),
   nenhuma usa `gt-shapefile` de fato.
4. ✅ **Feito** (DATAGIS-14) — app sobe, `/swagger-ui.html` confirmado com springdoc 3.x.
5. ⬜ Spring Batch: job trivial ainda não escrito — ver status real em §3.3 (dependência
   presente, não ligada; `LoteIngestaoWriter` faz esse papel na marra por enquanto).
6. Achado ao vivo, fora do que os itens 1-5 previam: **Jackson 3 mudou de pacote**
   (`tools.jackson.databind.*`, não `com.fasterxml.jackson.databind.*` — os dois
   convivem no classpath porque uma lib transitiva ainda traz o antigo; importar o
   errado **compila** mas o Spring não acha o bean de `ObjectMapper` certo) e **métodos
   mudaram** (`asText()`→`asString()`, `asDouble()` removido → usar `doubleValue()`).
   Isso pegou na prática construindo o `GeoJsonWktMapper` (§5.8) — vale mais que
   qualquer um dos itens 2/3 que ainda não foram exercidos de verdade.

**Resolver dependência sem conflito não prova que funciona em runtime.** Dos itens que
*foram* exercidos de verdade (Hibernate JPA comum + JSON nativo + Quartz/Batch coexistindo
no boot + springdoc + Jackson 3), tudo passou — mas isso não é o mesmo que validar
`SDO_GEOMETRY` ou `gt-shapefile`, que seguem não exercitados porque a Silver e uma fonte
Shapefile de verdade ainda não existem.

---

## 6. Perfis de Usuário (referência futura, RBAC ainda não implementado)
1. **Administrador Geral** — acesso irrestrito, gerencia usuários/permissões/credenciais
2. **Operador/Ingestor de Dados** — cadastra fontes, faz uploads, dispara pipelines
3. **Analista/Gestor Socioambiental** — configura regras de cálculo, audita quarentena,
   valida/publica versões
4. **Usuário Consultor/Auditor** — somente leitura: busca imóveis, visualiza indicadores
   homologados (Gold), consulta linhagem, exporta relatórios

---

## 7. Glossário rápido
- **DAG:** Directed Acyclic Graph — conceito do Airflow (não usado neste projeto, mas
  aparece nos documentos de referência originais)
- **CAR:** Cadastro Ambiental Rural — registro obrigatório de imóveis rurais no Brasil
- **APP:** Área de Preservação Permanente
- **RL:** Reserva Legal
- **SRC/SRID:** Sistema de Referência de Coordenadas (RN013 do DRN pede padronização em
  SIRGAS 2000, EPSG:4674)
- **WKT:** Well-Known Text — formato textual de geometria (presente na fonte IBAMA como
  `WKT_GEOM_AREA_EMBARGADA`)

---

## 8. Como manter este arquivo
Sempre que uma nova decisão de arquitetura for tomada (aqui no Claude Code ou em
discussão externa), atualize a seção correspondente e marque claramente como
**[DECIDIDO]**, **[PROPOSTO — a validar]** ou **[EM ABERTO]**, para que sessões futuras
do Claude Code não tratem uma hipótese como se fosse definitiva.

---

## 9. Estado do repositório e itens em aberto

### 9.1. Repositório
- **Relocado para o WSL — [FEITO]:** `/home/fagner/projetos/datagis`, clonado a partir
  da cópia Windows. `origin` removido (ainda sem remote — falta criar no GitHub e dar
  `push`; `~/projetos` no WSL não tem backup automático).
- **`mvnw` sem bit de execução — [CORRIGIDO]:** o commit inicial foi feito no Windows
  (NTFS não tem +x), então `./mvnw` dava `Permission denied` em clone Linux. Corrigido
  via `git update-index --chmod=+x mvnw`; testado (`./mvnw -v` → Maven 3.9.16 nativo
  Linux, JDK 21).
- **`core.autocrlf=true` global — [CORRIGIDO]:** herança de config do lado Windows;
  reescreveria os arquivos em CRLF no próximo checkout mesmo com `.gitattributes`
  correto. Ajustado para `input` (`git config --global core.autocrlf input`).
- **`frontend/` = repositório separado — [FEITO]:** fica **totalmente fora** do repo do
  backend (`frontend/` no `.gitignore`). Tem `git init` próprio, com seu próprio
  `.gitattributes` (mesmo motivo do `core.autocrlf` acima). (Não adotado: git submodule.)
- **`CLAUDE.md` — [DECIDIDO: rastreado]** no repo do backend. Só `HELP.md` segue ignorado.
- **`.gitattributes`** (nos dois repositórios): `* text=auto eol=lf` + regras para
  `*.sh`, `Dockerfile`, `*.yml`/`*.yaml` (LF) e `*.cmd`/`*.bat` (CRLF).
- **Docker Desktop com WSL Integration — [CONFIRMADO]:** `docker version` /
  `docker compose version` / `docker run hello-world` funcionando de dentro do WSL.

### 9.2. Decidido nesta rodada
- §2.1 — 1 instância Oracle, 7 schemas, nomes parametrizados.
- §2.1.1 — Flyway: 1 instância, versão global zero-padded, DDL de Quartz/Batch como
  migration, `initialize-schema=never`.
- §3.3 — Spring Batch para orquestração do pipeline (Quartz dispara o Job).
- §3.4 — hospedagem **adiada**; banco é Oracle; nuvem gratuita provável = OCI.
- §4.1 — frontend Vue 3 + Vite em `frontend/`.
- §4.6 — dev com 3 containers (oracle/backend/frontend), modo dev com bind mount, sem
  empacotar; Dockerfile de build adiado.
- §5.1 — fatia vertical inclui a tela Vue quando houver algo demonstrável.
- §5.2 / §5.6 — **1ª e única fonte da fase: CAR (SICAR) via WFS**, camada
  `sicar_imoveis_pr` (estado do **Paraná**, corrigido de SP). Ingestão por API, sem
  upload de arquivo.
- §5.4 — interface `ArmazenamentoArquivoBruto`, só impl local-FS agora.
- §4.5 — `spring-boot-starter-websocket` **removido**.
- §2.1 — tabelas do **Spring Batch em schema `batch` dedicado** (confirmado).
- §2.1 / §4.6 — **`docker-compose` implementado e testado end-to-end** (2026-09-02, WSL):
  Oracle 7 schemas + usuário `CONTROLE` com privilégios `ANY`, backend conecta e migra
  Flyway, `/actuator/health` → 200, Vite serve e o proxy `/api` alcança o backend.
- §9.1 — `CLAUDE.md` rastreado; `.gitattributes` com normalização de fim de linha.
- §4.4 — **dependências pendentes adicionadas ao `pom.xml`** (hibernate-spatial,
  jts-core, gt-shapefile, gt-geojson, springdoc, Quartz, Spring Batch) e validadas com
  `mvn dependency:tree` — resolveram sem conflito (2026-09-04). Spike funcional (§5.5)
  ainda não feito.

### 9.4. Decidido em 2026-09-04 (chegada do desafio oficial da Visiona)
- **Recorte territorial: Paraná**, não São Paulo — §1, §5.2, §5.6 atualizados.
- **Circuito menor de municípios, expansível**: Curitiba + Maringá + Ponta Grossa
  (lista configurável) — §5.2.
- **Zona Bronze continua em tabela Oracle** (não migra para Object Storage) apesar do
  desafio falar em "armazenamento de objetos" — §5.4.
- **`gt-shapefile` mantido no plano de dependências** (§4.4) — não removido apesar de
  CAR (a única fonte da fase) não precisar dele; outras fontes do catálogo (IBGE, ICMBio,
  FUNAI, IBAMA) são Shapefile-primário.
- **Catálogo de fontes de dados testado e documentado**: `docs/fontes-de-dados.md` — 8 de
  10 fontes do slide oficial com caminho técnico confirmado (CAR, IBGE malha, IBGE
  biomas, ICMBio UCs, FUNAI TIs, ICMBio embargos, INPE PRODES incl. Mata Atlântica, INPE
  Queimadas); 2 pendentes de mais investigação (INCRA sem WFS estável; APP/RL/vegetação
  nativa do CAR só via download em lote em `consultapublica.car.gov.br`, não via WFS).

### 9.3. Ainda em aberto (decidir antes de codar as partes afetadas)
- **Parsing GeoJSON** (§5.6): Jackson 3 + mapper JTS (recomendado, evita GeoTools) vs
  `gt-geojson` — confirmar no spike.
- **Reprojeção de SRID** (§2.5): Oracle `SDO_CS.TRANSFORM` vs GeoTools — decidir na Silver.
- **Spike de compatibilidade** (§5.5) — executar antes da 1ª entidade. Infra (§4.6) já
  validada; falta validar a stack Java (hibernate-spatial, GeoTools, Spring Batch) rodando
  sobre o Boot 4.1.1 dentro deste mesmo ambiente.
- **Gap do CAR (APP/RL/vegetação nativa)** (`docs/fontes-de-dados.md`): investigar a API
  interna de `consultapublica.car.gov.br` antes de precisar calcular IAPP/IRL/ICV.
- **INCRA sem WFS estável** (`docs/fontes-de-dados.md`): achar os parâmetros certos do
  broker i3geo, ou aceitar como fonte manual, antes de fechar o escopo completo de ISAP.
- **`DatagisApplicationTests` (Testcontainers) falha dentro do container do backend**:
  não tem acesso ao Docker do host de dentro do `datagis-backend` (sem Docker-in-Docker
  configurado). `./mvnw test` roda; teste específico (`-Dtest=...`) funciona; o teste
  padrão do Initializr que sobe um Oracle via Testcontainers, não. Resolver quando o
  spike (§5.5) for feito de verdade — provavelmente monta o socket do Docker no
  container ou roda esse teste fora dele.

### 9.5. Fatia 1 (ingestão CAR/PR) — **[COMPLETA e validada de ponta a ponta, 2026-09-05]**
Branch `feat/ingestao-car-pr`, a partir da `sprint-1`. **Rodado de ponta a ponta contra
o Oracle real do compose**, não só compilado:
- `SecurityConfig` (§5.3) — bean provisório liberando tudo.
- Entidades JPA: `FonteDado`/`LoteIngestao` (pacote `controle`), `RegistroBruto`
  (pacote `bronze`, `@Table(schema = "BRONZE")` explícito — é a única tabela fora do
  schema padrão da conexão). Referências cross-schema de `RegistroBruto` para
  `controle.*` são `Long` simples, não `@ManyToOne` — Bronze é armazenamento burro, não
  precisa navegar objeto.
- `payload` mapeado com `@JdbcTypeCode(SqlTypes.JSON)` sobre `String` — funcionou de
  primeira contra o tipo `JSON` nativo do Oracle 23ai.
- `ativo` (`fonte_dado`) precisou de `@Convert(converter = NumericBooleanConverter.class)`
  — sem isso, o Hibernate 7 espera `BOOLEAN` nativo (disponível no Oracle 23ai), mas a
  DDL usa `NUMBER(1)` (convenção Oracle tradicional, já documentada no §2.8). Usar o
  mesmo padrão em qualquer outro campo booleano futuro.
- Migrations `V001`-`V004` (`controle`: fonte_dado, lote_ingestao, seed CAR/PR;
  `bronze`: registro_bruto) — **incluindo os `GRANT REFERENCES` por tabela** (ver
  pegadinhas de privilégio acima).
- **Descoberta de spike, de graça**: esse ciclo já validou Hibernate+Oracle real (JSON
  nativo, conversor de tipo, FK cross-schema, IDENTITY cross-schema) — não é o teste de
  `SDO_GEOMETRY` do §5.5 (isso seguirá pendente pra Silver), mas reduz a incerteza geral
  sobre Hibernate 7.4 + Oracle 23ai.

**Itens 4-6 (2026-09-05) — feitos e testados contra o CAR/PR de verdade, não só a
amostra:**
- `CarProperties` (`app.car.*`) — URL do WFS, layer, e o **circuito de municípios como
  config** (`app.car.municipios-ibge`), nunca hard-coded (§5.2/§9.4).
- `GeoJsonWktMapper` — GeoJSON→WKT com **JTS puro** (`GeometryFactory` + `WKTWriter`),
  sem GeoTools nem `jts-io-common` (que nem tem `GeoJsonReader` dentro do `jts-core` —
  conferido no jar antes de assumir). Suporta `Polygon`/`MultiPolygon`; outro tipo
  falha explícito.
- **`Jackson 3` tem pacote novo**: `tools.jackson.databind.*`, não
  `com.fasterxml.jackson.databind.*` — os dois convivem no classpath (o antigo vem
  transitivo de outra lib), então importar o errado **compila** mas o Spring não acha
  bean de `ObjectMapper` daquele tipo. Métodos também mudaram: `asText()`→`asString()`,
  `asDouble()` foi removido (usar `doubleValue()`).
- **`IngestaoCarService`** — orquestra tudo: busca, hash SHA-256 do corpo bruto (RN018),
  cria `lote_ingestao`, grava 1 `registro_bruto` por feature, fecha o lote. Sem batch
  insert (grava 1 a 1) — aceitável pro volume da fatia (milhares, não milhões).
- **`IngestaoController`** — `POST /api/ingestao/car/pr`.
- **⚠️ Bloqueio de TLS por WAF contra `java.net.http.HttpClient`** — ver
  `docs/fontes-de-dados.md` item 1. `CarWfsClient` chama `curl` via `ProcessBuilder`
  como workaround (funciona de forma consistente); `RestClient`/`WebClient` do Spring
  **não funcionam** contra esse endpoint específico.
- Teste (`IngestaoCarServiceTest`) com fixture estática (`fixtures/car-pr-amostra.json`)
  — mocka só o `CarWfsClient`, resto é lógica real.
- **Testado contra o CAR de verdade**: `POST /api/ingestao/car/pr` → **4.590 registros**
  gravados (111 Curitiba + 1.831 Maringá + 2.648 Ponta Grossa, bate exato), ~13s.
- Tela Vue (`App.vue`) — botão "Ingerir CAR/PR", mostra `loteIngestaoId` +
  `quantidadeRegistros` ou erro. Testado através do proxy do Vite (`localhost:5173`),
  não só direto no backend.
- **Ambiente resetado** (`docker compose down` + volume do Oracle recriado) pro
  primeiro teste local ficar limpo (`loteIngestaoId=1`).

**Detecção de "nada mudou" (RN018/§2.8) — implementada em 2026-09-05:**
- Decisão: **sempre criar um `lote_ingestao`** (mesmo sem mudança — vira `status =
  'SEM_MUDANCA'`, `quantidade_registros = 0`), nunca ficar em silêncio. É o que permite
  diferenciar depois "o job não rodou" de "rodou e não tinha nada novo" — importante pra
  auditoria (RNF do desafio: "logs, execuções... visíveis"). Vale igual pra disparo
  manual e futuro Quartz — "mudou ou não" é propriedade do dado, não de quem perguntou.
- `LoteIngestaoRepository.findFirstByFonteDadoIdOrderByIdDesc` — compara o hash do
  lote mais recente da fonte com o hash do que acabou de chegar.
- **⚠️ Pegadinha real, achada testando**: o `hash_conteudo` **não pode ser da resposta
  bruta inteira** — o WFS carimba um campo `timeStamp` que muda a cada chamada, mesmo
  com dado idêntico (confirmado comparando duas respostas reais: mesmas 111 features,
  só o `timeStamp` diferente — por isso o SHA-256 nunca batia). Corrigido: o hash agora
  é só de `featureCollection.path("features")` reserializado, não do corpo bruto
  (`IngestaoCarService`). **Se outra fonte no futuro tiver campo parecido (timestamp de
  geração, nonce, etc.), aplicar o mesmo cuidado** — hashear o conteúdo relevante, nunca
  o envelope da resposta.
- Testado contra o CAR de verdade: 1ª chamada grava 4.590 (~13s); 2ª chamada idêntica →
  `SEM_MUDANCA`, 0 registros, ~2s (só busca e compara, não grava).

### 9.6. Cadastro de fontes — **[FEITO e testado, 2026-09-05]**
CRUD simples de `fonte_dado`, branch `feat/cadastro-fontes`:
- Backend: `FonteDadoRequest`/`FonteDadoResponse` (DTOs), `FonteDadoService`
  (valida `tipoAquisicao` contra os 3 valores aceitos), `FonteDadoController`
  (`POST /api/fontes`, `GET /api/fontes`). `TratadorDeErro` (`@RestControllerAdvice`)
  transforma `IllegalArgumentException` em 400 com mensagem, em vez de 500 cru.
- Frontend: `App.vue` ganhou abas (Ingestão CAR / Fontes de dados), sem router —
  só um `ref` trocando qual `<section>` aparece. Formulário + lista em
  `components/CadastroFontes.vue`.
- **Pegadinha achada**: editar o texto de uma migration SQL já aplicada muda o checksum
  que o Flyway guarda, e ele recusa subir com `FlywayValidateException`. Resolvido
  resetando o volume do Oracle (banco de dev, sem problema); em produção seria
  `flyway repair`. **Lição**: se editar uma migration que já rodou, ou reseta o banco de
  dev, ou roda repair — nunca edita e ignora.
- Testado via `curl` direto e através do proxy do Vite: cadastra, lista, e recusa
  `tipoAquisicao` inválido com 400.
- Testes: `FonteDadoServiceTest` (cadastra válido, recusa tipo inválido, lista delega
  pro repositório).

### 9.7. Portabilidade do ambiente — **[FEITO, 2026-09-07, `DATAGIS-17`]**
- Repositório do backend renomeado/clonado como `datagis-backend`; frontend passou a
  viver em **`datagis-frontend/`** (era `frontend/`) — ver §4.6.
- `docker-compose.yml` ajustado pra funcionar em máquinas diferentes da que originou o
  projeto (contexto de build do frontend, pequenos ajustes de path).
- **`.env` passou a ser rastreado no git, de propósito** — decisão confirmada pela
  equipe em 2026-09-09: credenciais são só do Oracle de desenvolvimento local, trocam
  quando a hospedagem em nuvem (§3.4) entrar. Ver nota detalhada em §4.6.
- Primeiro commit da equipe assinado por outro integrante (Flavio Pereira Jr.) — o
  projeto deixou de ser trabalho solo.
- `.devcontainer/devcontainer.json` adicionado (suporte a abrir o projeto em Dev
  Container no VS Code, alternativa ao fluxo manual do `docker compose`).

### 9.8. Ingestão deixa de ser exclusiva do CAR — **[FEITO, 2026-09-08, `DATAGIS-14`]**
- `IngestaoExecutor`/`IngestaoExecucaoService` — qualquer fonte cadastrada pode ser
  disparada por `POST /api/fontes/{id}/executar`, não só o CAR. Detalhe completo em §5.8
  (que descreve o estado final, já pós-`LIZARDS-5`).
- `docs/checklist-cobertura-fontes.md` e `docs/fontes-de-dados-v2.md` criados — ver §5.7.
- Confirmado ao vivo: Hibernate Spatial + Quartz + Spring Batch sobem juntos no mesmo
  contexto Spring sem conflito (mas nenhum dos três está funcionalmente exercitado —
  ver status real em §3.2/§3.3/§5.5).
- `FonteDadoResponse` ganhou `ultimaExecucaoEm`.

### 9.9. Ingestão genérica por formato + resiliência — **[FEITO, 2026-09-09, `LIZARDS-5`]**
Descrito em detalhe na §5.8 (arquitetura vigente). Resumo do que mudou de estado:
- Executor deixou de ser "1 por fonte" e virou "1 por formato" (GEOJSON, CSV).
- Paginação real pra WFS e ArcGIS REST — sem isso, fontes grandes perdiam registro
  silenciosamente (achado nos Embargos IBAMA: ~113 de 2.113 sumindo sem erro nenhum).
- 3 causas de falha achadas testando contra fontes reais, não em teste unitário:
  geometria ausente, geometria malformada (`NullPointerException` que nem chegava no
  `TratadorDeErro`), e falha intermitente de servidor externo (retentativa antes de
  reduzir o tamanho de página).
- CRUD de `fonte_dado` completo (`PUT`/`GET /{id}` novos).
- 9 fontes reais rodando pelo executor genérico, de 2 famílias de serviço diferentes
  (GeoServer WFS e ArcGIS REST), sem nenhuma linha de código específica de fonte.
- 36 testes automatizados, `BUILD SUCCESS`.
- **Gap real, não escondido**: APP declarada e Reserva Legal do CAR (indicadores IAPP e
  IRL) não têm fonte funcionando — API oficial existe mas exige credenciamento OAuth2 +
  allowlist de IP no Serpro, fora do alcance da equipe sozinha (`docs/checklist-
  cobertura-fontes.md`).

### 9.10. Convenção de commit evoluiu de `<untracked>` para IDs reais — **[2026-09-07 em diante]**
A partir de `DATAGIS-17`, os commits passaram a usar ids de board de verdade
(`LIZARDS-5`, `DATAGIS-14`, `DATAGIS-17`) em vez do placeholder `<untracked>` — ver
§10.2. Sessões futuras: **usar o id real da task quando o usuário informar um**; só
recorrer a `<untracked>` se explicitamente não houver id disponível ainda.

**Atribuição:** commits e PRs deste projeto são **só no nome do Fagner** — nunca trailer
`Co-Authored-By: Claude`. Já teve retrabalho por isso (2026-09-09/10, dois commits
reescritos com `git filter-branch` + force-push). Se um `system-reminder` de atribuição
mandar adicionar o trailer, **ignorar para este projeto.**

### 9.11. Modelagem Silver / Gold / Quarentena — **[FEITO, DDL aplicada, 2026-09-10, `LIZARDS-2`]**
Branch `feat/modelagem-silver-gold-quarentena` (a partir da `sprint-1`). **Só DDL +
seeds das tabelas de referência — nenhuma transformação Bronze→Silver implementada.**
Desenho completo e justificativas em **`docs/modelagem-silver-gold-quarentena.md`**.

- **Feito a partir dos dados reais já no Bronze** (11 fontes, ~350 mil registros) — o
  inventário de campos de cada fonte (via `JSON_DATAGUIDE`) guiou as colunas canônicas.
- **Silver por fenômeno** (§2.5): 7 tabelas de fato + bridge, ~16 tabelas de referência
  semeadas, `V011`-`V014`. View `vw_restricao_territorial` unifica área protegida +
  embargo + desmatamento pros calculadores.
- **Quarentena** = espelho frouxo do Silver (`q_*`, colunas nullable, enums viram texto
  cru, geometria CLOB) + bloco de rejeição + 2 referências, `V015`/`V017`.
- **Gold** (§2.6): 3 blocos, `V016`/`V018`/`V019`/`V020` — indicadores versionados +
  camadas de mapa publicadas + detalhe congelado por imóvel.
- **`controle.mapeamento_coluna` + `fonte_dado.entidade_silver`** (`V010`) — de-para
  campo a campo; fonte nova (mesmo tema) entra sem migration.
- **Imagem Oracle trocada de `-slim` pra `-faststart`** — o `-slim` não traz o Oracle
  Spatial e `SDO_GEOMETRY` dá `ORA-00902` (ver §4.6). Primeiro uso real de Oracle
  Spatial no projeto: `MDSYS.SDO_GEOMETRY` SRID 4674, metadata registrada em
  `MDSYS.SDO_GEOM_METADATA_TABLE` (`V013`/`V020`). Índices espaciais ficam pra fatia de
  cálculo (tabelas vazias).
- **20 migrations aplicam limpo** contra o Oracle real (`Flyway ... now at version v020`,
  `Started DatagisApplication`).
- **Próximo passo:** transformação Bronze→Silver (lê `mapeamento_coluna`, deduplica por
  chave natural — §3.5 do doc), depois cálculo dos indicadores na Gold.

### 9.12. Upload manual + ingestão de Shapefile — **[FEITO e testado ao vivo, 2026-09-11, `LIZARDS-3`]**
Branch `feat/upload-shp`. Detalhe completo, achados e itens em aberto em
**`docs/upload-shapefile-car.md`**. Resumo:

- **`consultapublica.car.gov.br` (download em lote de Shapefile por estado) é uma SPA
  com sessão — confirmado de novo que não dá pra automatizar por HTTP simples** (loop
  de redirect). Solução: usuário baixa no navegador dele, sobe pro sistema por
  **upload manual** — `tipo_aquisicao=UPLOAD_MANUAL` já existia no cadastro, só faltava
  a execução de verdade.
- Peças novas: `ArmazenamentoArquivoBruto` (interface do §5.4, finalmente
  implementada — local-FS), `IngestaoShapefileExecutor` (`formatoEsperado=SHAPEFILE`,
  usa GeoTools — item 3 do spike §5.5, finalmente exercitado), endpoint
  `POST /api/fontes/{id}/upload`, botão de upload na tela pra fontes `UPLOAD_MANUAL`.
- **GeoTools 35.1 usa `org.geotools.api.*`, não `org.opengis.*`** — mapa completo de
  pacotes no doc (mesma classe de pegadinha do Jackson 3, §5.5 item 6).
- **Limite de upload teve que subir pra 2GB** — arquivo real de teste (218MB) já
  passava do limite inicial de 100MB, e tem casos de 1GB+. Sintoma de limite estourado:
  sem erro nenhum no log, só `Failed to fetch` no navegador (Tomcat reseta a conexão no
  meio do recebimento, não devolve resposta HTTP).
- **Testado com arquivo de produção real**: 218MB, 559.899 registros, ~3 min,
  processamento síncrono (sem chunk/batch) sem erro.
- **Achado real comparando com a fonte via WFS**: o Shapefile bulk traz o **histórico
  completo** do CAR (`situacao_imovel` = `AT`/`CA`/`PE`), o WFS só devolve o vigente.
  Refina a regra de dedup do §3.5 do doc de modelagem — duplicata **dentro do mesmo
  lote** (não entre lotes) precisa de regra própria (manter só `AT`).

### 9.13. Regras de validação em mapeamento_coluna — [FEITO, 2026-09-13, `V029`]
Migration `V029__evoluir_mapeamento_coluna_regras_validacao.sql` adiciona suporte
a regras de validação por coluna diretamente na tabela `controle.mapeamento_coluna`:
- `valor_minimo` (NUMBER): limite numérico inferior (apenas mínimo, sem teto máximo).
- `tabela_referencia` (VARCHAR2(60)): nome da tabela de referência na Silver para validação de domínio (ex.: `situacao_imovel`, `orgao`, `bioma`).
- `referencia_entidade_silver` (VARCHAR2(60)): integridade relacional cruzada com Silver (ex.: `municipio`).
- `validar_cpf_cnpj` (NUMBER(1)): flag para validação algorítmica de CPF/CNPJ (módulo 11).
- `validar_geometria` (NUMBER(1)): flag para validação topológica (`isValid`) e tipo primitivo.
- `validar_recorte_pr` (NUMBER(1)): flag para checagem de coordenadas dentro dos limites do Paraná.
- `impedir_regressao` (NUMBER(1)): flag para checar se o campo existia preenchido e regrediu pra nulo.
- `regras_extras` (JSON): campo flexível para fontes futuras desconhecidas.

### 9.14. Novos tipos de erro na Quarentena — [FEITO, 2026-09-13, `V030`]
Migration `V030__quarentena_novos_tipos_erro.sql` insere novos tipos em `quarentena.tipo_erro_quarentena`:
- `CPF_CNPJ_INVALIDO`: dígitos verificadores ou formato de CPF/CNPJ inválido.
- `VALOR_ABAIXO_DO_MINIMO`: valor numérico menor que o valor mínimo parametrizado.
- `CAMPO_EM_BRANCO`: campo preenchido exclusivamente com espaços ou falso nulo (NULL, N/A, -).
- `GEOMETRIA_TIPO_INCOMPATIVEL`: tipo primitivo de geometria incompatível com a entidade alvo.

### 9.15. Sanitizador de Entrada & Falsos Nulos — [FEITO, 2026-09-13, Fase 2.1]
Criada a base do motor de validações no pacote `br.gov.sp.cps.datagis.validacao`:
- `ResultadoValidacao`: record imutável com resultado de sucesso (valor tratado) ou erro detalhado para a Quarentena.
- `ContextoValidacaoCampo`: metadados e regras da coluna oriundos de `mapeamento_coluna`.
- `ValidadorCampo`: interface para encadeamento Strategy / Chain of Responsibility.
- `SanitizadorEntrada`: intercepta valores, limpa espaços, converte falsos nulos (`"NULL"`, `"null"`, `"N/A"`, `"n/a"`, `"-"`, `""` e espaços) para `null` real, e valida obrigatoriedade gerando erro `CAMPO_OBRIGATORIO_AUSENTE` se `obrigatorio = true`.
- Testes: 26 cenários em `SanitizadorEntradaTest`, 100% de sucesso.

### 9.16. Validador de CPF / CNPJ — [FEITO, 2026-09-13, Fase 2.2]
Implementado `ValidadorCpfCnpj` no pacote `br.gov.sp.cps.datagis.validacao`:
- Remove todos os caracteres não numéricos (`replaceAll("\\D", "")`) antes de qualquer checagem.
- Determina o tipo pelo comprimento: 11 dígitos → CPF · 14 dígitos → CNPJ · outro → erro imediato.
- Rejeita sequências triviais de dígitos repetidos (ex.: `111.111.111-11`, `00.000.000/0000-00`) via regex `(\d)\1{10/13}`.
- Aplica o algoritmo de Dígito Verificador (Módulo 11) oficial para CPF (dois passes) e CNPJ (pesos `PESOS_CNPJ_DIGITO1/2`).
- Em caso de falha retorna `ResultadoValidacao.erro(CPF_CNPJ_INVALIDO, ...)` com `campoProblema`, `valorRecebido` e `motivoRejeicao`.
- Sucesso retorna apenas os dígitos normalizados em `valorTransformado` (persistência limpa).
- Valor `null` ou string vazia é devolvido como `sucesso(null)` — responsabilidade do `SanitizadorEntrada`.
- `suporta()` ativa pela flag `validarCpfCnpj = true` **ou** por `tipoDado` igual a `cpf`, `cnpj` ou `cpf_cnpj` (case-insensitive).
- Helpers estáticos públicos: `isCpfValido(String)` e `isCnpjValido(String)` para reuso externo.
- Testes: 18 cenários em `ValidadorCpfCnpjTest` cobrindo suporta(), CPF/CNPJ válidos e inválidos, casos de borda e helpers.

### 9.17. Validador de Domínio e Tabelas de Referência — [FEITO, 2026-09-13, Fase 2.3 | EVOLUÍDO 2026-09-14]
Implementado `ValidadorDominio` no pacote `br.gov.sp.cps.datagis.validacao`:
- Valida se o valor informado existe como registro ativo (`ativo = 1`) na tabela de referência da Silver parametrizada em `tabela_referencia` (ex.: `condicao_imovel`, `situacao_imovel`, `bioma`, `orgao`, `tipo_imovel`).
- Utiliza **cache thread-safe em memória** (`ConcurrentHashMap<String, Map<String, String>>`) com carregamento sob demanda (*lazy loading*) via `JdbcTemplate`.
- Suporta mapeamento por código canônico, descrição oficial e variações ortográficas em $O(1)$:
  - **Acento-insensível**: decomposição NFD (`\p{M}`) garante que `"Aguardando análise"` case com `'Aguardando analise'`.
  - **Tolerância a separadores**: intercambiabilidade entre espaços, underlines e hífens (`_`, ` `, `-`).
  - **Alfanumérico**: ignora pontuação, parênteses, vírgulas e caracteres como `º`.
  - **Sinônimos e sub-estados oficiais**: resolução semântica automática para variações da Lei 12.651/2012 do SICAR/CAR (ex.: `"Analisado, em conformidade com a Lei nº 12.651/2012"` $\to$ `ANALISADO_SEM_PENDENCIAS`; `"Analisado, aguardando regularização ambiental..."` $\to$ `ANALISADO_COM_PENDENCIAS`; `"Cancelado por decisão administrativa"` $\to$ `CANCELADO_ADM`).
- Replicado em `PersistidorSilverService.java` para garantir integridade e resolução exata de Foreign Keys numéricas (`condicao_imovel_id`, `situacao_imovel_id`, etc.) ao promover para a Silver.
- Possui mitigação de SQL Injection via regex `^[a-zA-Z0-9_]+$` e higienização de prefixos de schema (ex.: `silver.situacao_imovel` -> `situacao_imovel`).
- Fallback automático para tabelas de referência que não possuem coluna `descricao` (consulta apenas `codigo`).
- Se o valor não constar no domínio ativo, gera o erro `VALOR_FORA_DO_DOMINIO` com detalhes do campo, valor e tabela.
- **Preparação para ação do analista / revalidação**: expõe métodos `invalidarCache(tabela)` e `invalidarTodoCache()` para que, quando um analista aprovar ou cadastrar um novo valor de domínio, a engine revalide os registros pendentes sem reiniciar a aplicação.
- Testes: 27 cenários em `ValidadorDominioTest` e 4 cenários em `PersistidorSilverServiceTest` cobrindo acentos, separadores, descrições do SICAR, códigos, descrições, case-insensitive, prefixo de schema, mitigação de SQLi, fallback, cache thread-safe e revalidação.

### 9.18. Validador de Integridade Cruzada — [FEITO, 2026-09-13, Fase 2.4]
Implementado `ValidadorIntegridadeCruzada` no pacote `br.gov.sp.cps.datagis.validacao`:
- Valida se a chave informada existe na tabela canônica da camada Silver parametrizada em `referencia_entidade_silver` (ex.: `silver.municipio`, `imovel_rural`, `area_protegida`, `auto_infracao`, `embargo_ambiental`).
- Suporta mapeamento por convenção de chave natural (`municipio` -> `cod_ibge`, `imovel_rural` -> `cod_imovel`, etc.) e por especificação explícita de coluna (`municipio.cod_ibge` ou `tabela.coluna`).
- Mitigação de SQL Injection via validação de identificadores com regex `^[a-zA-Z0-9_]+$`.
- Estratégia híbrida de cache thread-safe (`ConcurrentHashMap<String, Set<String>>`):
  - Tabelas dimensionais/estáticas (`municipio`): carga em lote sob demanda de todos os códigos (`SELECT cod_ibge FROM silver.municipio`).
  - Tabelas volumosas: consulta pontual com cache das chaves já verificadas (`SELECT COUNT(1) ... WHERE coluna = ?`).
- Se a referência não for encontrada na Silver, gera o erro `REFERENCIA_NAO_ENCONTRADA`.
- Expõe `invalidarCache(entidade)` e `invalidarTodoCache()` para revalidação dinâmica.
- Testes: 23 cenários em `ValidadorIntegridadeCruzadaTest` cobrindo entidades estáticas, consultas pontuais, formatos de referência, SQLi, cache e revalidação do analista.

### 9.19. Validador de Faixa Mínima — [FEITO, 2026-09-13, Fase 2.5]
Implementado `ValidadorFaixaMinima` no pacote `br.gov.sp.cps.datagis.validacao`:
- Valida se o valor informado respeita o limite numérico inferior parametrizado em `valor_minimo` (ex.: `area_ha >= 0.0`).
- Conversão tolerante de tipos numéricos (`Number` ou `String`), suportando formatos decimais brasileiros (`15,5`) e internacionais (`15.5`), separadores de milhar (`1.250,50` e `1,250.50`) e símbolos monetários (`R$ 100,00`).
- Se o valor textual não for conversível para número, gera erro `TIPO_DE_DADO_INVALIDO`.
- Se o valor for menor que o piso configurado, gera erro `VALOR_ABAIXO_DO_MINIMO`.
- Testes: 23 cenários em `ValidadorFaixaMinimaTest` cobrindo aprovações, limites exatos, conversões tolerantes, erros de tipo, rejeições abaixo do piso e casos de borda (nulos e vazios).

### 9.20. Validador de Geometria e Recorte PR — [FEITO, 2026-09-13, Fase 2.6]
Implementado `ValidadorGeometria` no pacote `br.gov.sp.cps.datagis.validacao`:
- Validação completa de atributos espaciais utilizando a biblioteca JTS Topology Suite (`org.locationtech.jts`).
- Suporta entradas em formato WKT e GeoJSON (via `ObjectMapper`).
- Validação sintática: erros de formatação geram `GEOMETRIA_MALFORMADA`.
- Validação topológica com `IsValidOp`: auto-interseções, anéis desconexos e laços geram `GEOMETRIA_INVALIDA` com o motivo e as coordenadas exatas da quebra topológica.
- Validação de primitivo: garante que o tipo (ex.: `Polygon`, `MultiPolygon`, `Point`) respeite a configuração de `regras_extras` ou `tipo_dado`, gerando `GEOMETRIA_TIPO_INCOMPATIVEL` se divergente.
- Validação de recorte territorial do Paraná (`validar_recorte_pr = 1`): verifica interseção com os limites oficiais do Paraná (SRID 4674: lon `[-55.00, -47.50]`, lat `[-27.20, -22.00]`), gerando `FORA_DO_RECORTE_TERRITORIAL` se fora.
- Geometrias nulas obrigatórias geram `GEOMETRIA_NULA`.
- Sucesso retorna WKT padronizado gerado por `WKTWriter`.
- Testes: 29 cenários em `ValidadorGeometriaTest` cobrindo WKT, GeoJSON, auto-interseções, primitivos incompatíveis, recorte Paraná e nulidade.

### 9.21. Validador de Regressão de Valor — [FEITO, 2026-09-13, Fase 2.7]
Implementado `ValidadorRegressao` no pacote `br.gov.sp.cps.datagis.validacao`:
- Atua quando `impedir_regressao = 1` em `mapeamento_coluna` (padrão `DEFAULT 1`).
- Identifica se um campo estava preenchido no snapshot/lote anterior da mesma chave natural e veio nulo, vazio ou falso nulo no lote mais recente.
- Caso detectada perda de dados, gera o erro `REGRESSAO_DE_VALOR` com detalhamento do campo e valor anterior para a Quarentena.
- Aprova atualizações legítimas (valor novo preenchido) ou permanência de estado (ambos nulos).
- Adicionado campo opcional `Object valorAnterior` ao record `ContextoValidacaoCampo` com suporte a `toBuilder = true`.
- Testes: 22 cenários em `ValidadorRegressaoTest` cobrindo perda de dados, falsos nulos, atualizações legítimas e flags desativadas.

### 9.22. Deduplicação de Registros e Seleção do Mais Novo para a Silver — [FEITO, 2026-09-13, Fase 2.8]
Implementado `DeduplicadorRegistros` no pacote `br.gov.sp.cps.datagis.validacao`:
- Agrupa registros pela chave natural de negócio (ex.: `cod_imovel`, `numero_termo`, etc.).
- Quando houver duplicatas de chave natural (seja entre diferentes lotes/snapshots ou repetidas dentro do mesmo lote), seleciona o registro mais recente/relevante como vencedor para a Silver.
- Critério de precedência e desempate:
  1. Regra de negócio CAR: registros com `situacao_imovel = "AT"` (Ativo) têm precedência sobre históricos cancelados (`"CA"`) ou pendentes (`"PE"`);
  2. Lote/snapshot mais recente: maior `loteIngestaoId`;
  3. Sequência temporal mais recente: maior `dataIngestao` ou maior `id`.
- Retorna `ResultadoDeduplicacao<T>` estruturado contendo a lista de promovidos únicos e o detalhamento de duplicatas descartadas (`DuplicataDescartada<T>`) com motivo, chave natural e referência ao vencedor.
- Registros sem chave natural são preservados sem descarte indevido.
- Suporte a deduplicação genérica tipada (`deduplicarGenerico`).
- Testes: 7 cenários em `DeduplicadorRegistrosTest` (185 testes no total do pacote de validação).

### 9.23. Orquestrador de Validação e Transição Bronze → Silver / Quarentena — [FEITO, 2026-09-13, Fase 3]
Implementado `OrquestradorValidacao` no pacote `br.gov.sp.cps.datagis.validacao`:
- Conecta a leitura dos metadados de `controle.mapeamento_coluna`, a deduplicação temporal via `DeduplicadorRegistros`, e a execução da cadeia de `ValidadorCampo`.
- Para cada registro vencedor, executa o `SanitizadorEntrada` e em seguida os validadores especializados suportados pelo campo.
- Implementa estratégia fail-fast por registro: no primeiro campo com erro, interrompe a validação do registro e o classifica como `RegistroRejeitadoQuarentena` com o bloco completo de auditoria para a Quarentena.
- Registros que passam em todos os campos são promovidos como `RegistroAprovadoSilver` contendo os atributos transformados e geometria padronizada.
- Testes: 5 cenários em `OrquestradorValidacaoTest`.

### 9.24. Módulo de Quarentena & Qualidade de Dados (Backend + Frontend) — [FEITO, 2026-09-13, Fase 3]
- **Backend (`br.gov.sp.cps.datagis.quarentena` & `controle`):**
  - Entidade e repositório `MapeamentoColuna` (`controle.mapeamento_coluna`) e `MapeamentoColunaController` (`/api/fontes/{id}/mapeamentos`).
  - `QuarentenaService` e `QuarentenaController`: consultas dinâmicas às tabelas espelho `quarentena.q_*`, suporte a paginação, métricas agregadas por status/erro/entidade, e endpoints de ação do analista (`/descartar`, `/iniciar-analise`, `/reprocessar`).
  - Testes: 6 cenários em `QuarentenaControllerTest`.
- **Frontend (`datagis-frontend`):**
  - Adicionada rota e item de menu em `Sidebar.vue`: **"Quarentena & Qualidade"** (`/quarentena`).
  - Componente `QuarentenaView.vue`: cards de métricas (Total, Pendentes, Em Análise, Corrigidos, Descartados), filtros por entidade/status/erro/busca, e tabela de inconsistências com badges visuais.
  - Componente `DetalheQuarentenaModal.vue`: drawer/modal de inspeção profunda com visualizador de payload JSON cru, campo com erro destacado e formulário de ação/correção manual do analista (RN007).
  - Serviço `quarentenaService.js` com integração completa aos endpoints REST do backend.

### 9.25. Transição Automática Bronze → Silver com Regra All-or-Nothing e Persistência Especializada — [FEITO, 2026-09-13, Fase 3]
- **Regra de Negócio de Integridade Total (All-or-Nothing):**
  - Se um único registro de um lote de ingestão falhar em qualquer validação e for para a Quarentena, **0 registros desse lote vão para a Silver**.
  - O lote é retido no status `RETIDO_QUARENTENA` e todo o lote fica pausado até decisão do analista.
  - Se 100% dos registros forem aprovados, o lote é automaticamente promovido com status `PROMOVIDO_SILVER`.
  - Se o analista liberar o lote retido (`POST /api/quarentena/lotes/{id}/liberar`), os registros válidos são promovidos para a Silver e o lote passa a `PROMOVIDO_SILVER`.
  - Se o analista descartar o lote (`POST /api/quarentena/lotes/{id}/descartar`), nenhum registro vai para a Silver e o lote passa a `DESCARTADO`.
- **Persistência Especializada Silver & Quarentena:**
  - `PersistidorSilverService`: mapeia `RegistroAprovadoSilver` para as entidades canônicas da camada Silver (`ImovelRural`, `AreaProtegida`, `EmbargoAmbiental`, `AutoInfracao`, `Desmatamento`, `FocoCalor`, `Municipio`) e converte WKT para JTS `Geometry` com SRID 4674.
  - `PersistidorQuarentenaService`: persiste registros rejeitados nas tabelas espelho `quarentena.q_*` com rastreamento completo (`lote_ingestao_id`, `fonte_dado_id`, `registro_bruto_id`, `tipo_erro_id`, payload original, motivo e campo do erro).
  - `TransicaoBronzeSilverService`: coordena a validação de cada lote, orquestração e a aplicação estrita do All-or-Nothing.
  - `LoteIngestaoWriter`: integra automaticamente o disparo da transição ao final de cada gravação de lote na Bronze.
  - `LoteIngestaoController` & `LoteDto`: endpoints `GET /api/lotes` e `GET /api/lotes/{id}` para auditoria e histórico de pipeline.
  - Migration `V031__seed_mapeamentos_fontes_padrao.sql`: carga inicial de de-para para fontes padrão (`CAR_PR`, `IBAMA_EMBARGOS`, etc.) em `controle.mapeamento_coluna`.
  - Testes: 6 cenários em `TransicaoBronzeSilverServiceTest` e 8 cenários em `QuarentenaControllerTest`.

### 9.26. Frontend de Gestão de Lotes, Pipeline e De-Para de Mapeamento de Colunas — [FEITO, 2026-09-13, Fase 3]
- **Módulo de Lotes & Pipeline (`LotesView.vue`):**
  - Rota `/lotes` com item dedicado na `Sidebar.vue` (ícone de camadas de pipeline).
  - Cards de métricas agregadas por status de lote: Total de Lotes, Promovidos para Silver, Retidos na Quarentena, Descartados e Sem Alterações.
  - Filtro por status e busca rápida por Lote ID ou nome da fonte.
  - Tabela com status semântico do lote e ações de governança:
    - Para lotes em `RETIDO_QUARENTENA`: "Inspecionar Erros" (navega direto para a Quarentena com filtro pré-aplicado), "Liberar para Silver" e "Descartar" (com modal de justificativa obrigatória).
- **Modal de Mapeamento de Colunas (`ModalMapeamentoColunas.vue`):**
  - Permite visualizar o de-para de campos configurados para qualquer fonte de dados (`/api/fontes/{id}/mapeamentos`).
  - Permite aos analistas cadastrar novos mapeamentos para fontes com estruturas desconhecidas, configurando destino (`COLUNA`, `JSON`, `GEOMETRIA`), tipo de dado e flags de validação (`obrigatorio`, `validarGeometria`, `validarCpfCnpj`, `validarRecortePr`, `impedirRegressao`).
- **Integração na Tela de Cadastro de Fontes (`CadastroFontes.vue`):**
  - Novo botão de ação em cada linha para abrir o modal de mapeamento de colunas.
  - Linha de resultado após execução/upload atualizada para refletir a semântica de pipeline:
    - Se `PROMOVIDO_SILVER`: feedback verde com atalho para o Pipeline.
    - Se `RETIDO_QUARENTENA`: banner de alerta âmbar explicando que 0 registros foram para a Silver devido a inconsistências, com atalhos para inspecionar na Quarentena e gerenciar no Pipeline.
- **Integração e Rastreabilidade na Quarentena (`QuarentenaView.vue`):**
  - Leitura reativa de `?loteId=` via query params com campo de filtro dedicado e banner informativo de All-or-Nothing.
  - Badge `#Lote {id}` clicável diretamente em cada linha da tabela da Quarentena para isolar os erros daquela carga específica.
### 9.27. Resiliência do Pipeline de Transição, Oracle Spatial e Governança de Quarentena — [FEITO, 2026-09-14]
- **Resolução de Domínio & Fonética (CAR / SICAR / Lei 12.651/2012):**
  - `ValidadorDominio` e `PersistidorSilverService` ganharam normalização NFD sem acento (`\p{M}`), tolerância a separadores (`_`, ` `, `-`), remoção de pontuação e dicionário estático de sinônimos/sub-estados legais do SICAR.
  - Elimina falsos positivos onde valores válidos da base do CAR (ex.: `"Aguardando análise"`, `"Analisado, em conformidade com a Lei nº 12.651/2012"`) eram rejeitados por pequenas divergências ortográficas em relação às tabelas de referência da Silver.
- **Correção da Auditoria de Descarte na Quarentena (ORA-00904):**
  - Em `TransicaoBronzeSilverService.java`, o comando de atualização foi corrigido para utilizar a coluna `analista_responsavel_id` em vez de `analista_id` em todas as 7 tabelas espelho (`q_imovel_rural`, `q_area_protegida`, `q_auto_infracao`, `q_embargo_ambiental`, `q_desmatamento`, `q_foco_calor`, `q_municipio`).
  - Ao descartar um lote no pipeline (`POST /api/quarentena/lotes/{id}/descartar`), seus itens agora transitam corretamente de `PENDENTE` (`status_analise_id = 1`) para `DESCARTADO` (`status_analise_id = 4`), anexando o motivo do analista e saindo da fila principal de quarentena.
- **Persistência Silver com LOBs Nativos e Oracle 23ai (ORA-01461 & Driver Bug):**
  - Em `PersistidorSilverService.java`, comandos `MERGE INTO` usam `PreparedStatementCallback` direto para evitar que o Spring JDBC invoque `ps.getParameterMetaData()` com valores nulos (o que disparava `ArrayIndexOutOfBoundsException` no client parser do driver Oracle JDBC 23ai).
  - Substituído `setCharacterStream` por `java.sql.Clob` temporário de conexão via `ps.getConnection().createClob()` e liberação explícita via `clob.free()` no bloco `finally` para strings maiores que 2.000 caracteres.
  - Elimina o erro `ORA-01461: The value at bind position 9 exceeded the maximum VARCHAR2 length` em geometrias poligonais extensas (WKT com 20.000 a 100.000+ caracteres do CAR).
- **Validação End-to-End no Banco Real:**
  - **Lote #45 (CAR PR - 4.595 registros brutos):** promovido com sucesso para a Silver via `POST /api/quarentena/lotes/45/liberar`:
    - **4.593 registros válidos** inseridos na tabela `SILVER.imovel_rural` com geometrias espaciais (`SDO_GEOMETRY`, SRID 4674).
    - **2 registros retidos** por auto-interseção atualizados para `CORRIGIDO_REPROCESSADO` (`status_analise_id = 3`).
    - Lote #45 atualizado para `PROMOVIDO_SILVER`.
  - **Descarte de Lote:** testado e validado end-to-end com atualização simultânea do lote para `DESCARTADO` e dos itens de quarentena para `status_analise_id = 4`.
- **Frontend Vite CSS Fix:**
  - Corrigido fechamento de bloco CSS em `QuarentenaView.vue` (`.card-metrica--descarte .metrica-valor`).
  - Build de produção (`npm run build`) validado com sucesso em 2.10s.

### 10.4-bis. Estado atual (2026-09-14)
- **Branch de trabalho corrente:** `feat/regras-para-vallidacao` (backend) e `sprint-1` (frontend).
- **Migrations V029, V030 e V031 aplicadas**.
- **Fase 3 100% concluída e estabilizada em produção local (Backend + Frontend + Oracle 23ai):**
  - Esteira automatizada de transição Bronze → Silver / Quarentena com regra All-or-Nothing;
  - Descarte e liberação manual de lotes pelo analista operando perfeitamente com auditoria cruzada;
  - Resolução fonética e de sinônimos em domínios de referência;
  - Persistência espacial nativa de geometrias complexas em Oracle Spatial SRID 4674;
  - De-para dinâmico de colunas gerenciável pelo analista;
  - **202 testes unitários no backend aprovados** (100% de sucesso).
  - **Build frontend Vite concluído com sucesso** (`✓ built in 2.10s`).

---

## 10. Git — branches, commits e fluxo de PR — **[DECIDIDO, padrão da equipe LizardsDBA]**

Aplica-se aos **dois repositórios** (`datagis-backend`, `datagis-frontend`), cada um com
seu próprio histórico e remote independentes (ver §9.1).

### 10.1. Branches
```
main
 └── sprint-<numero>            (ex.: sprint-1, sprint-2)
      ├── feat/<nome-da-feature>
      ├── bugfix/<nome-do-fix>
      ├── hotfix/<nome-do-hotfix>
      └── refactor/<nome>
```
- `main` — sempre estável, pronta para entrega. **Nunca commit direto.**
- `sprint-<numero>` — criada a partir da `main` no início da sprint; base de todas as
  features da sprint; recebe os merges via PR; ao final da sprint, é mergeada na `main`.
  **Nunca commit direto.**
- `feat/*`, `bugfix/*`, `hotfix/*`, `refactor/*` — sempre criadas a partir da branch da
  sprint **atual**. Nome claro, sem espaço, sem acento.
- PR sempre `feat/* → sprint-X` (ou `bugfix/*`/`hotfix/*`/`refactor/*` → `sprint-X`); no
  fim da sprint, `sprint-X → main`.

### 10.2. Commits
```
tipo<id_tarefa>: descrição do commit
```
Exemplo: `docs<scrum-1>: Estruturação inicial do repositório.`

**Sem ID de task ainda (situação atual):** usar `<untracked>` no lugar do id —
ex. `chore<untracked>: estruturação inicial do repositório`. Trocar para o id real
(`<scrum-N>` ou equivalente) assim que o backlog/board estiver com IDs.

| Tipo | Uso |
|---|---|
| `feat` | novo recurso (MINOR) |
| `bugfix` / `fix` | correção de bug (PATCH) |
| `docs` | documentação, sem mudança de código |
| `style` | formatação (lint, espaço), sem mudança de código |
| `refactor` | refatoração sem mudar comportamento |
| `build` | build/dependências |
| `test` | testes |
| `chore` | tarefa administrativa/configuração (ex.: `.gitignore`, Docker, CI) |

### 10.3. Estado atual (2026-09-02) — **[remotes no ar]**
- Os dois repositórios foram **squashados para 1 commit cada** em `main`
  (`chore<untracked>: estruturação inicial do repositório`), removendo o histórico
  granular das sessões de desenvolvimento — guardado localmente em cada repo na branch
  `backup-historico-claude` (não sobe pro remote; apagável quando não precisar mais).
- **Remotes conectados e sincronizados:**
  - `datagis-backend` → `git@github.com:LizardsDBA/datagis-backend.git`
  - `datagis-frontend` → `git@github.com:LizardsDBA/datagis-frontend.git`
  - O GitHub criava cada repo com um "Initial commit" só de `LICENSE` (GPLv3, auto
    selecionada na criação); esse arquivo foi preservado dentro do commit squashado
    (não simplesmente sobrescrito) antes do `push --force` de `main`/`sprint-1`. Se a
    equipe não quiser GPLv3, é só trocar/apagar `LICENSE` num commit
    `chore<id_tarefa>: ...` depois.
- **`sprint-1` criada** a partir da `main` nos dois repos e **empurrada pro remote**.
  **A partir de agora, todo trabalho novo nasce em `feat/*`/`bugfix/*`/`hotfix/*`/
  `refactor/*` a partir de `sprint-1`, nunca direto em `sprint-1` ou `main`.**
- **Primeira branch de desenvolvimento criada: `feat/spike-compatibilidade`**
  (backend, a partir de `sprint-1`, já no remote) — é onde entra o spike do §5.5. Ainda
  sem branch de dev no frontend (nada de concreto pra fazer lá até o spike do backend
  destravar a 1ª fatia). PR de cada `feat/*` para `sprint-1` é aberto manualmente pelo
  time no GitHub.

### 10.4. Estado atual (2026-09-10)
- **PRs mesclados em `sprint-1` até agora:** #1 (`feat/spike-compatibilidade`), #4
  (`feat/dependencias-spatial-quartz-batch`), #5 (`feat/ingestao-car-pr`), #6
  (`feat/cadastro-fontes`), #7 (`feat/tela-cadastro-fontes` — §9.9).
- **Equipe deixou de ser um único dev**: commits de `DATAGIS-17` em diante já mostram
  mais de um autor (Flavio Pereira Jr. além do Fagner). Task ids reais em uso
  (`LIZARDS-2`, `LIZARDS-5`, `DATAGIS-14`, `DATAGIS-17`) — ver §9.10.
- **Branch de trabalho corrente:** `feat/modelagem-silver-gold-quarentena`, em cima da
  `sprint-1` — modelagem Silver/Gold/Quarentena (§9.11), no remote, ainda sem PR aberto.
- **`feat/tela-cadastro-fontes` — mesclada (PR #7)**; os commits `fc27d27`/`6686dfe`
  foram reescritos (`a5eb723`/`1ea1433`) pra tirar um trailer `Co-Authored-By` e
  normalizar o autor pra `Fagner Louis` antes do merge (ver §9.10).
- **Trabalho feito em sessão/máquina diferente da que gerou as seções anteriores deste
  arquivo** — por isso o `CLAUDE.md` ficou defasado por alguns dias (2026-09-05 até
  2026-09-09) até esta atualização. **Lição prática**: como o arquivo não se atualiza
  sozinho quando o trabalho acontece em outra sessão/máquina/pessoa, vale o hábito de
  pedir "atualiza o CLAUDE.md" no fim de qualquer sessão que gerou commits reais, não só
  quando alguém lembra depois.
