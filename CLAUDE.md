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

**`hash_versao` — [RESOLVIDO, 2026-10-04]:** implementado em `pkg_gold_publicacao.
registrar_resultado` exatamente como proposto em `docs/gold-procedures-calculo-
publicacao.md` §4 — SHA-256 via `DBMS_CRYPTO.HASH` sobre uma string canônica
(`cod_imovel|regra_calculo_id|lotes_csv|indicador:valor:area:percentual`). Idempotente:
recalcular sem mudança de dado-fonte devolve o mesmo `indicador_versao_id` em vez de
duplicar linha.

**Transição Silver→Gold (cálculo dos indicadores + publicação) — [IMPLEMENTADO em
PL/SQL, 2026-10-04 — ver §9.33]**, diferente de Bronze→Silver (que é Java, §9.23-25).
4 dos 7 indicadores calculam de verdade contra dado real (ISAP, IAE, IDesmat, IFC);
ICV/IRL/IAPP seguem stub (bloqueio SICAR Temas). Testado contra os 4.608 imóveis reais
do CAR/PR via `POST /api/gold/publicar`. Design original, cobertura por indicador e
trade-offs em `docs/gold-procedures-calculo-publicacao.md`.

### 2.7. Schema de controle (transversal)
- ~~`usuario`, `papel`, `usuario_papel`~~ — **não serão criadas**: identidade e RBAC
  moram no Keycloak (§9.39, decisão de 2026-10-12)
- `fonte_dado` — catálogo de fontes (ver seção 2.8, estendido para agendamento)
- `lote_ingestao` (fonte_dado_id, arquivo_origem, data_upload, usuário, hash_arquivo)
- `pipeline_execucao` — histórico de execuções (substitui o que seria "DAG run" do Airflow)
- `log_auditoria` — **append-only**, sem UPDATE/DELETE possível (RN025 do DRN) —
  **criada em 2026-10-13 (`V075`, LIZARDS-48, §9.39)**

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
    cron_expressao      VARCHAR2(100),         -- lido pelo Quartz na subida e re-sincronizado
                                                -- a cada salvamento da fonte (§3.2, §9.28, §9.30)
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
tese) `DOWNLOAD_URL`; desde 2026-09-21 o `cron_expressao` é lido pelo Quartz (§3.2, §9.28): fonte ativa com
cron preenchido roda sozinha; o disparo manual via `POST /api/fontes/{id}/executar`
continua existindo.

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

### 3.2. Agendamento dinâmico de ingestão — **[DECIDIDO: Quartz Scheduler — IMPLEMENTADO 2026-09-21]**
Necessidade: cron configurável **por fonte de dado**, editável pelo usuário em tela,
sobrevivendo a restart da aplicação.

- `@Scheduled`/`TaskScheduler` puro do Spring foi **descartado** — cron fixo em tempo de
  compilação, não serve para N crons dinâmicos definidos em runtime; exigiria bookkeeping
  manual de `ScheduledFuture` por fonte e reconstrução manual no boot.
- **Escolhido: Quartz Scheduler** (`spring-boot-starter-quartz`) — suporta `CronTrigger`
  dinâmico (criar/atualizar/remover em runtime via `scheduler.rescheduleJob(...)`), com
  `JobStore` persistido em banco (`quartz-jobstore-jdbc`), sobrevivendo a restarts.
  Confirmar suporte oficial do Quartz JobStore para Oracle antes de gerar o schema dele.
- **Suporte a Oracle confirmado** (item acima): funciona com `OracleDelegate`, mas as
  colunas booleanas do schema precisam ser `VARCHAR2(5)`, não o `VARCHAR2(1)` do
  `tables_oracle.sql` oficial — ver pegadinha em §9.28.
- **Status em 2026-09-21: ligado** (§9.28) — substitui o status de 2026-09-09, quando
  nada estava criado. JobStore JDBC no schema `QUARTZ` (tabelas pela `V034`); na subida,
  o `AgendadorInicializacaoRunner` registra/atualiza um `CronTrigger` por fonte ativa com
  `cron_expressao`. O disparo manual (`POST /api/fontes/{id}/executar`) continua.
  Desde 2026-09-23 o requisito "editável em tela" está atendido: salvar a fonte pela tela
  cria, atualiza ou remove o trigger na hora, sem reiniciar (§9.30).

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
**Desde 2026-10-12 são 4**: entrou o `keycloak` (login/RBAC, porta 8180 — §9.39).
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
  **Isso só vale no `docker compose up`** — no reboot do PC o Docker religa tudo junto
  (`restart: unless-stopped` ignora `depends_on`). Por isso o backend também espera o
  banco sozinho: `spring.flyway.connect-retries=60` + `connect-retries-interval=5`
  (até 5 min) no `application.properties` (2026-10-01).
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
- **`docs/tutorial-acesso-fontes-pendentes.md` (2026-10-03, prioridade máxima) —**
  passo a passo real do credenciamento Conecta Gov (autocadastro + certificado digital
  ICP-Brasil + pedido formal de órgão à SGD/ME + IP allowlist Serpro — processo pesado,
  provavelmente exige apoio institucional da FATEC/Visiona, não é algo que o time resolve
  sozinho) **e** reteste ao vivo do Plano B (vegetação nativa via IAT): achado novo que
  **muda o status anterior** — o candidato `Vegetacao_Nativa_IAT_2021` é só tabela
  (197.963 registros, sem geometria consultável), não serve pra interseção geoespacial
  do jeito que está hoje. Plano B segue em aberto, não resolvido.
  **Atualizado no mesmo dia** com uma §0 nova: cruzamento das 10 linhas do slide oficial
  contra o `controle.fonte_dado` **consultado direto no banco** (não documentação) — 6
  linhas 100% cobertas, 3 gaps de verdade (os de cima), e **2 achados novos que não são
  gap de pesquisa, só de cadastro**: IBGE (limites de biomas) e INCRA (quilombolas +
  assentamentos) já tinham fonte validada desde a v2 mas nunca viraram `fonte_dado` —
  URLs prontas pra cadastrar no próprio tutorial §0.

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

### 5.3. Spring Security — bypass temporário — **[SUBSTITUÍDO em 2026-10-12 pelo RBAC com Keycloak, §9.39]**
> Histórico. O `SecurityConfig` agora valida JWT do Keycloak e libera cada rota por
> permissão — ver §9.39 e `docs/controle-de-acesso-rbac.md`.

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
- **Achado real comparando com a fonte via WFS**: o Shapefile bulk traz o histórico
  completo do CAR (`situacao_imovel` = `AT`/`CA`/`PE`). Refina a regra de dedup do §3.5
  do doc de modelagem — duplicata **dentro do mesmo lote** (não entre lotes) precisa de
  regra própria (manter só `AT`).
  **[CORRIGIDO em 2026-10-07]** A premissa original aqui ("o WFS só devolve o vigente")
  estava **errada** — testado ao vivo comparando as 2 fontes (`docs/tutorial-acesso-
  fontes-pendentes.md` §0-bis): o WFS, sem filtro nenhum, já devolve AT+PE+CA+SU
  (confirmado via `CQL_FILTER` por status, soma bate exato com o total sem filtro —
  557.854 em 2026-10-07). Ou seja, o WFS automatizado (fonte `#1`, já ativa) **já tem o
  histórico completo que achávamos que só o Shapefile manual (`#12`) trazia** — a
  necessidade de upload manual pra esse propósito específico é bem menor do que
  documentado aqui originalmente. A regra de dedup continua válida (os dois têm
  histórico, então os dois precisam dela), mas a justificativa de "por que cadastramos
  o upload manual" deixa de ser "é a única fonte com histórico".

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

### 9.28. Agendamento automático de ingestão via Quartz — **[FEITO e testado contra o Oracle real, 2026-09-21, `LIZARDS-10`]**
Branch `feat/servico-de-ingestao`. Liga de verdade a decisão do §3.2. Resumo:

- **Peças novas:** `IngestaoAgendadaJob` (Job do Quartz — recebe `fonteId` pelo
  `JobDataMap` e delega pro `IngestaoExecucaoService`, o mesmo caminho do disparo
  manual); `AgendadorInicializacaoRunner` (`ApplicationRunner` — na subida lê
  `findByAtivoTrueAndCronExpressaoIsNotNull()` e cria/atualiza o JobDetail
  `ingestao-fonte-{id}` + CronTrigger `trigger-fonte-{id}`, grupo `ingestao`);
  `AutowiringSpringBeanJobFactory` + `QuartzConfig`.
- **JobStore JDBC no schema `QUARTZ`:** `V034__criar_tabelas_quartz.sql`,
  `spring.quartz.job-store-type=jdbc`, `initialize-schema=never` (o schema vem do
  Flyway, não do Spring).
- **`V035__preencher_cron_expressao_fontes.sql`:** cron das 11 fontes automatizáveis. A
  fonte 12 (CAR via Shapefile, `UPLOAD_MANUAL`) fica sem cron de propósito.
- **Pegadinha 1 — colunas booleanas do Quartz precisam ser `VARCHAR2(5)`.** O ojdbc do
  Oracle 23ai grava boolean como `"true"`/`"false"`; o `tables_oracle.sql` oficial usa
  `VARCHAR2(1)` → `ORA-12899: value too large for column ... IS_DURABLE (actual: 4,
  maximum: 1)`. A `V034` já vem corrigida (8 colunas); **regerar o schema a partir do
  script oficial traz o bug de volta.**
- **Pegadinha 2 — Job do Quartz não recebe `@Autowired` sozinho.** O Quartz instancia o
  Job por reflexão, fora do Spring, então o `IngestaoExecucaoService` chegava `null`.
  Resolvido com `AutowiringSpringBeanJobFactory` (sem `@Component`), registrado via
  `SchedulerFactoryBeanCustomizer` no `QuartzConfig`.
- **Testes:** `AgendadorInicializacaoRunnerTest` e `AutowiringSpringBeanJobFactoryTest`
  (unitários) + `IngestaoAgendadaOracleIT` (integração, Oracle real): cria uma fonte CSV
  falsa, o runner registra o trigger, o Quartz dispara, e o teste confere status
  `SUCESSO`, 1 lote, 3 registros no Bronze com geometria e 3 na Silver (a promoção pra
  Silver é a já existente do §9.25). Limpa tudo o que criou no final.
- **Como rodar os `*OracleIT`:** só rodam com `DATAGIS_ORACLE_IT=true` (senão aparecem
  como *skipped* — não quebram o `mvn test` de ninguém). Rodar **na máquina**, não no
  container do backend (ele não tem Maven), com o Oracle do compose de pé:
  ```powershell
  $env:DATAGIS_ORACLE_IT="true"
  $env:SPRING_DATASOURCE_URL="jdbc:oracle:thin:@localhost:1521/FREEPDB1"
  $env:SPRING_DATASOURCE_USERNAME="<APP_USER do .env>"
  $env:SPRING_DATASOURCE_PASSWORD="<APP_USER_PASSWORD do .env>"
  .\mvnw.cmd test -Dtest=IngestaoAgendadaOracleIT
  ```
  Cuidado ao ler o `.env`: alguns valores têm comentário na mesma linha. O
  `IngestaoAgendadaOracleIT` usa `spring.quartz.scheduler-name=datagisTesteIT` pra não
  colidir com o scheduler do backend que estiver rodando (o `SCHED_NAME` faz parte da
  chave de todas as tabelas `QRTZ_*`).
- **[RESOLVIDO em 2026-09-23 — ver §9.30]** Os dois primeiros itens que ficaram em aberto
  aqui — trigger sincronizado só na subida, e trigger que não some quando a fonte é
  desativada ou perde o cron — foram corrigidos pelo `fix<LIZARDS-10>`.
- **[EM ABERTO]**
  - `proxima_execucao_em` ainda não é preenchida (dá pra obter de
    `trigger.getNextFireTime()`).

### 9.29. Cadastro de Fontes em Modal e Exibição do Agendamento na Listagem (Frontend) — [FEITO, 2026-09-23, `LIZARDS-11`]
Repositório `datagis-frontend`, branch `feat/tela-upload-status` (nome herdado do título
do card no board, que não descreve o escopo real acordado com o time).
- **Modal de Cadastro/Edição (`CadastroFontes.vue`):**
  - O formulário deixou de ficar permanentemente aberto acima da listagem e passou a viver dentro de um modal (`.modal-backdrop` / `.modal-dialog`), seguindo o mesmo padrão visual já adotado em `ModalMapeamentoColunas.vue`.
  - Abertura pelo botão `+ Adicionar fonte`, no topo da listagem; fechamento pelo `✕`, pela tecla `Esc` (listener registrado em `onMounted` e removido em `onUnmounted`) ou automaticamente após salvar com sucesso.
  - `watch(modalAberto)` trava `document.body.style.overflow` enquanto o modal está aberto, evitando que a página de fundo role junto.
  - O botão de edição (lápis) passou a abrir o mesmo modal já preenchido com a fonte selecionada, substituindo o antigo `window.scrollTo` até o formulário.
  - `Cancelar` unificado com o fechamento do modal (função `cancelarEdicao()` removida por duplicar comportamento).
  - Alerta de sucesso movido para fora do formulário, acima da listagem, com autodesaparecimento em 4s (`mostrarSucesso()` + `clearTimeout` no `onUnmounted`) — dentro do modal ele ficava invisível, já que o modal fecha ao salvar.
- **Coluna "Agendamento" na listagem (`CadastroFontes.vue`):**
  - `descreverCron(expressao)` traduz para linguagem natural os três formatos gerados pelo `ConstrutorCron.vue`: diário (`0 m H * * ?` → "Todo dia às HH:MM"), semanal (`0 m H ? * DOW` → "Toda segunda-feira às HH:MM") e mensal (`0 m H D * ?` → "Todo dia D às HH:MM").
  - Expressões fora desses padrões são exibidas na forma original, sem tradução; fontes sem `cron_expressao` aparecem como "Somente manual".
  - A expressão original é mantida visível abaixo da descrição, em fonte monoespaçada, para conferência pelo operador.
  - Comentário desatualizado em `ConstrutorCron.vue` corrigido para apontar o consumo real do valor (`controle.fonte_dado.cron_expressao` → `AgendadorInicializacaoRunner`, §9.28).
- **Testes automatizados do frontend (`src/components/CadastroFontes.test.js`):**
  - 7 cenários em Vitest + `@vue/test-utils`: modal fechado ao montar, abertura pelo botão de adicionar, fechamento pelo `Cancelar`, abertura em modo edição já preenchida pelo botão de lápis, e as três situações da coluna de agendamento (tradução, cron fora do padrão e fonte sem agendamento).
  - `fetch` substituído por `vi.stubGlobal` e `vue-router` por `vi.mock` — a suíte roda **sem backend e sem Oracle**.
  - Execução (a máquina de desenvolvimento não tem Node instalado; roda pelo container, a partir da pasta do `docker-compose.yml`): `docker compose exec frontend npm test`.
  - Estado em 2026-09-23: 3 arquivos, 24 testes, 100% de sucesso (`conjuntoService.test.js` 10, `ConjuntosView.test.js` 7, `CadastroFontes.test.js` 7).
- **[EM ABERTO]**
  - A API não expõe `DELETE /api/fontes/{id}` — fonte cadastrada só pode ser desativada, nunca excluída pela tela.

### 9.30. Reagendamento no Quartz ao salvar a fonte — [FEITO e testado ao vivo, 2026-09-23, `fix<LIZARDS-10>`]
Branch `fix/reagendamento-fonte`. Fecha os dois primeiros itens que a §9.28 deixou em
aberto: o cron editado pela tela passa a valer no ato, e a fonte desativada para de
disparar.
- **`AgendamentoFonteService` (novo, pacote `ingestao`):**
  - Recebeu o método `agendar` que vivia dentro do `AgendadorInicializacaoRunner` — mesmo código, casa nova. Como um `ApplicationRunner` só roda na subida, a lógica ficava inalcançável para qualquer outro caminho.
  - `sincronizar(fonte)` decide pelo estado da fonte: ativa e com cron preenchido cria ou atualiza o trigger; inativa ou sem cron chama `remover(id)`.
  - `remover(id)` apaga o `JobDetail` `ingestao-fonte-{id}` — o Quartz remove os triggers ligados a ele junto. Era o que faltava: como o JobStore é persistido no banco, um trigger criado continuava gravado e a fonte seguia disparando mesmo depois de desativada.
- **`AgendadorInicializacaoRunner` (enxugado de 90 para 45 linhas):** virou só o laço que percorre `findByAtivoTrueAndCronExpressaoIsNotNull()` e delega ao serviço. Comportamento na subida idêntico ao de antes.
- **`FonteDadoService` (passa a agendar):** `cadastrar` e `atualizar` chamam `sincronizar` **depois** do `save` (num cadastro novo, sem o id devolvido pelo banco não há como montar a chave do job). A `SchedulerException`, que é checada, é convertida em `IllegalStateException` e vira 502 pelo `TratadorDeErro`. Entrou também `validarCronExpressao`, que recusa expressão malformada com 400 na hora de salvar, via `CronExpression.isValidExpression`.
- **Testes:** `AgendamentoFonteServiceTest` (5 cenários: cria, reagenda, remove ao desativar, remove ao apagar o cron, e não tenta apagar o que não existe) e 2 casos novos no `FonteDadoServiceTest` (o `verify` de que salvar sincroniza o agendamento — é ele que trava a regressão — e a recusa de cron inválida). `AgendadorInicializacaoRunnerTest` seguiu igual, só trocando o construtor. Suíte completa: **349 testes, 0 falhas, 1 erro** — o erro é o `DatagisApplicationTests` de sempre (§9.3).
- **Validação ao vivo (2026-09-23):** com o backend de pé, editar o cron da fonte 5 pela tela gerou `Trigger atualizado para a fonte 5 (IAT/PR - Embargos IBAMA), cron = 0 0 5 ? * MON` na thread `nio-8080-exec-2` — ou seja, dentro da requisição HTTP, quase dois minutos depois da subida. Antes deste fix, salvar pela tela não produzia log nenhum do agendador.
- **[EM ABERTO]**
  - O `sincronizar` roda fora de transação com o `save`. Se a gravação for desfeita depois, o trigger continua lá. Não acontece hoje (o `FonteDadoService` não é `@Transactional`, cada `save` commita sozinho), mas vira problema se alguém envolver o cadastro numa transação maior.
  - `proxima_execucao_em` continua sem ser preenchida (§9.28).

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

### 9.31. Seeds: só fontes + lote de municípios do PR — [DECIDIDO, 2026-10-01, branch `feat/ajuste-seeds`]
- **Seeds de demonstração removidas** (`seed-demo/V021`–`V023`, `V026`, `V027`) e a location
  saiu do `spring.flyway.locations`. Bronze/Silver/Gold/Quarentena nascem vazias, exceto
  tabelas de referência/catálogo (V011, V015/V017/V030, V016/V018), que os validadores e
  as FKs exigem. Banco de dev antigo precisa de `docker compose down -v` (Flyway recusa
  versão aplicada que sumiu dos arquivos).
- **Única seed de dado: `V037__seed_lote_ibge_malha_municipal_pr.sql`**, arquivo SQL pronto (gerado uma vez a partir do serviço do IAT; o gerador não fica no repo).
  1 `lote_ingestao`
  (`PROMOVIDO_SILVER`) da fonte 'IBGE - Malha Municipal (PR)' → 399 `registro_bruto` →
  399 `silver.municipio`, linhagem ligada. Existe pra nenhuma fonte cair em
  `REFERENCIA_NAO_ENCONTRADA` por falta de município.
- **Resolução da geometria:** mesmo serviço do IAT da fonte 7, com
  `maxAllowableOffset=0.00005` (~5 m, ~14 MB; com 0.0001 o servidor gera auto-interseção em 3 municípios). Resolução total = ~62 MB (pesado pra
  migration e pro mapa); API do IBGE `qualidade=maxima` (3,6 MB) descartada porque não
  traz nome nem área, e o validador casa município também pelo nome (focos, ICMBio).
- **Conferido contra as fontes reais:** CAR, Focos IAT, Embargos/Autos IBAMA casam 100%.
  Embargos/Autos ICMBio têm ~37 registros com nome que não é município do PR ou está
  escrito errado na origem ("Cap. Leônidas Marques", "Tuneiras de Oeste", Naviraí/MS,
  "Mar territorial"...) — esses vão pra Quarentena por desenho.
- **[EM ABERTO] `bronze.registro_bruto.hash_registro` nunca é preenchido** (achado
  2026-10-01). O `LoteIngestaoWriter` monta o `RegistroBruto` sem `.hashRegistro(...)`
  nos dois caminhos de gravação — por isso `hash_origem` também sai vazio em toda a
  Silver. Não confundir com `lote_ingestao.hash_conteudo` (hash do lote inteiro, que
  funciona). Correção: SHA-256 de `payload` + `geometria_wkt` por registro. Combinado
  fazer depois, em branch `fix/` própria.

### 9.32. Design de Silver→Gold via PL/SQL — [DOCUMENTADO, 2026-10-03, pré-desenvolvimento]
Nada implementado ainda — sessão de documentação, a pedido do time, antes de abrir as 3
tasks do board (`LIZARDS-31` Conservação, Territorial, Ambiental). Ver
`docs/gold-procedures-calculo-publicacao.md` (novo) e §2.6.

- **Decisão proposta**: Silver→Gold (cálculo dos 7 indicadores + publicação nos 3
  blocos da Gold) vira **PL/SQL** (packages por task), diferente de Bronze→Silver
  (Java). Trade-offs documentados, não só vantagens — time ainda não escreveu PL/SQL
  neste projeto, teste só via Oracle real (sem mock), mesmo padrão `*OracleIT` do §9.28.
- **Achado que muda o escopo das 3 tasks**: `LIZARDS-31` (Conservação/ICV) e metade de
  Territorial (IRL, IAPP) **não têm fonte de dado hoje** (`tipo_indicador.tem_fonte=0`
  na `V016`, gap já conhecido desde `docs/checklist-cobertura-fontes.md` — API SICAR
  Temas exige credenciamento Serpro fora do alcance do time). Só ISAP, IAE, IDesmat e
  IFC são calculáveis de verdade agora; o resto vira stub até a fonte aparecer.
- Proposta de algoritmo pra `hash_versao` (§2.6) — primeira vez que esse ponto em
  aberto ganha um desenho concreto.
- **Itens em aberto que a equipe ainda precisa fechar**: confirmar a ida pra PL/SQL;
  validar o algoritmo de hash; Quartz+Job vs. `DBMS_SCHEDULER` pra disparar a
  publicação; tolerância de generalização de geometria do mapa; donos de cada
  package/task (tabela em branco no documento, a preencher).

### 9.33. Implementação das procedures Gold + achado crítico de índice espacial — **[FEITO e testado contra o Oracle real, 2026-10-04, `LIZARDS-31`/`LIZARDS-32`/`LIZARDS-33`]**
Branch `feat/procedures-indicadores`, a partir da `sprint-1`. Implementa de verdade o
design do §9.32: `pkg_gold_publicacao` (compartilhada) + `pkg_calculo_ambiental`
(IDesmat, IFC) + `pkg_calculo_territorial` (ISAP, IAE reais; IRL, IAPP stub) +
`pkg_calculo_conservacao` (ICV stub) — migrations `V043`-`V047`. Orquestração
"pra cada imóvel, calcula os 4 reais, publica" fica no Java (`GoldPublicacaoService`,
`GoldPublicacaoController`, `POST /api/gold/publicar` e `.../imoveis/{cod}/recalcular`),
não em PL/SQL — pacote não pode chamar de volta quem o chama sem dependência circular.

**Achado crítico, só aparece em volume real (não no teste sintético) — `SDO_ANYINTERACT`
nunca usa índice espacial:** `SDO_ANYINTERACT(a, b) = 'TRUE'` **nunca** aciona índice
espacial no Oracle desta instância, mesmo com o índice existindo e válido — confirmado
via `EXPLAIN PLAN`/`DBMS_XPLAN.DISPLAY`, sempre `TABLE ACCESS FULL` com filtro, nunca
`DOMAIN INDEX`. Só `SDO_RELATE(a, b, 'mask=ANYINTERACT') = 'TRUE'` é reconhecido pelo
otimizador como acesso indexado. Sem saber disso, calcular ISAP/IAE pra 200 imóveis
contra os 16 mil `area_protegida`/2,5 mil `embargo_ambiental` reais levava **~42
minutos**; trocando pra `SDO_RELATE` + índice espacial de verdade (`V049`, silver),
caiu pra **~1 segundo** (~2.400x). Todo `SDO_ANYINTERACT` da Gold foi trocado por
`SDO_RELATE(...,'mask=ANYINTERACT')`.

**Criar o índice espacial em si teve 3 pegadinhas de privilégio/recurso reais** (doc
completo no cabeçalho de `V049__indices_espaciais_calculo.sql`):
1. `CREATE INDEX ... INDEXTYPE IS MDSYS.SPATIAL_INDEX` roda rotina ODCI interna que
   precisa de `CREATE TABLE`/`CREATE SEQUENCE`/`UNLIMITED TABLESPACE` **diretos** (não
   "ANY") no schema **dono da tabela** (`silver`), não no `CONTROLE` que tem privilégio
   `ANY` de sobra — sem isso dá `ORA-01031`/`ORA-29855`. Mesma exigência pro `DROP`
   (`DROP ANY INDEX` do `CONTROLE` não basta; precisou conectar como `silver` direto).
   Grants permanentes adicionados em `infra/oracle-init/01-criar-schemas.sh`.
2. `area_protegida` tem polígonos nacionais (MMA/SFB) de até **164 mil vértices** —
   construir o R-tree estourava `PGA_AGGREGATE_LIMIT` (`ORA-04036`) até no default de
   2G. Resolvido subindo pra **8G**, e precisa subir **primeiro no `CDB$ROOT`, depois
   no PDB** (senão `ORA-56747`, limite do PDB não pode exceder o do root) — também
   persistido no init script.
3. Um índice com `STATUS='VALID'` no catálogo (`all_indexes` e
   `mdsys.sdo_index_metadata_table`) pode não funcionar de verdade se a criação morreu
   no meio (ex. pelo `ORA-04036` acima) — só o `EXPLAIN PLAN` mostrando `DOMAIN INDEX`
   de fato confirma. Confiar só no `STATUS` é enganoso.

**Segundo bug achado testando a publicação completa contra os 4.608 imóveis reais —
`publicar_camada_mapa` não era resiliente:** fazia um único `INSERT...SELECT` com
`SDO_UTIL.SIMPLIFY` por linha; uma geometria ruim de `area_protegida` dava
`ORA-13199: the given geometry cannot be rectified` e abortava a camada inteira (0
features publicadas, apesar de 16 mil boas). Reescrita linha a linha (`FOR` +
`BEGIN/EXCEPTION WHEN OTHERS`), mesmo princípio já usado na ingestão Bronze→Silver
(§5.8 item 2: pula e loga a linha ruim, publica as demais). Resultado real: **16.084 de
16.086** features de `AREA_PROTEGIDA` publicadas, só 2 geometrias genuinamente
inválidas puladas (logadas por `id`/`codigo_origem`). Mesmo achado revelou que uma
geometria pode ser **lenta sem ser inválida** (uma UC de 146 mil vértices levou ~5min
pra simplificar sozinha, sem erro) — não dá pra prever só pela contagem de vértices.

**Terceiro bug — `publicar_imovel` não era idempotente:** `registrar_resultado` já
reusa o mesmo `indicador_versao_id` (via `hash_versao`) quando nada mudou, mas
`publicar_imovel` fazia um `INSERT` direto em `imovel_publicado` — rodar a publicação
de novo sem mudança de dado violava `UQ_IMOVEL_PUBLICADO`. Corrigido com um guard
(`SELECT COUNT(*)` antes do insert) que torna republicar um no-op quando a versão já
foi publicada.

**Resultado final validado contra o Oracle real:** `gold.imovel_publicado` = 4.608
linhas (100% dos imóveis Silver). Camadas: `MUNICIPIO` 399, `EMBARGO` 2.519,
`IMOVEL_RURAL` 4.606 (2 a menos = os 2 imóveis com `geometria IS NULL` na Silver,
esperado), `AREA_PROTEGIDA` 16.084. `DESMATAMENTO` e `FOCO_CALOR` seguem em 0 —
**não são bug desta sessão**: `silver.desmatamento` está vazia (WFS do PRODES/INPE
instável, todas as tentativas de ingestão falharam hoje) e `silver.foco_calor` também
(bug real e separado: o endpoint de liberar quarentena trava em lotes muito grandes —
333 mil e 152 mil registros — fica pra uma sessão própria). Suíte completa rodada ao
final: **401 testes, 0 falhas, 1 erro** (o `DatagisApplicationTests` de sempre, §9.3 —
sem Docker-in-Docker dentro do container do backend), zero regressão.

**Commits divididos pelas 3 tasks do board** conforme o conteúdo de cada um (pacote
compartilhado + indicadores ambientais + as correções de mapeamento ficaram no
commit de `LIZARDS-33`; ISAP/IAE + o índice espacial ficaram em `LIZARDS-32`; o stub
do ICV em `LIZARDS-31`) — não existe uma divisão 1:1 perfeita entre arquivo e task
porque `pkg_gold_publicacao` é infraestrutura compartilhada pelos 3.

### 9.34. IAPP/IRL/ICV deixam de ser gap — fonte achada e cadastrada, sem credenciamento — **[FONTE CADASTRADA, 2026-10-07, `LIZARDS-24`]**
Os 3 indicadores que só tinham stub nas procedures Gold (§9.33) por falta de fonte
ganharam fonte real, achada a partir de uma URL do portal ArcGIS do IAT/PR. Detalhe
completo, verificação ao vivo e os 4 passos (cadastrar → ingerir → modelar Silver →
calcular) em `docs/tutorial-acesso-fontes-pendentes.md` §0-bis — resumo aqui:

- **Serviço**: `00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural` no geoportal IAT/PR
  (`geopr.iat.pr.gov.br`), público, sem credenciamento. 9 camadas do CAR; as 3
  relevantes pros indicadores: `APP Total` (381.962 polígonos → IAPP), `Reserva Legal`
  (347.867 → IRL), `Vegetação Nativa` (393.153 → ICV + numerador do IAPP via
  interseção com APP Total).
- **Verificado via `curl` direto no ArcGIS REST**, não suposição: geometria real com
  `outSR=4674`, `cod_imovel` no mesmo formato de `silver.imovel_rural.cod_imovel`,
  `des_condic` com o mesmo texto do SICAR que `ValidadorDominio` já resolve (§9.17).
- **Comparação de confiabilidade feita ao vivo** (mesma sessão) entre as 3 fontes de
  *perímetro* de imóvel disponíveis (WFS `#1`, Shapefile `#12`, e a camada `Área
  Imóvel` deste mesmo serviço IAT): WFS SICAR (`#1`) é a mais confiável — domínio
  oficial direto (`geoserver.car.gov.br`), `cod_municipio_ibge` numérico como chave
  (evita erro de nome de município, já visto nesta sessão), e teste cruzado do mesmo
  `cod_imovel` entre WFS e IAT deu área/geometria idênticas (24 vértices nos dois). A
  camada `Área Imóvel` do IAT **não foi cadastrada** por ser redundante e menos
  completa (sem status `CA`).
- **Achado que corrige §9.12**: o WFS SICAR, testado sem filtro, já devolve
  AT+PE+CA+SU (não só o vigente como a nota antiga dizia) — ver correção em §9.12.
- **Cadastrado em `V053__seed_fontes_car_app_rl_vegetacao_nativa.sql`**: as 3 fontes
  (`API_REST`/`GEOJSON`, mesma família ArcGIS já suportada por
  `IngestaoGeoJsonExecutor`, zero código novo) + `mapeamento_coluna` deliberadamente
  mínimo (mesmo motivo do `V041`/MMA-SFB: ativar integridade cruzada com
  `imovel_rural` agora quarentenaria quase tudo, já que a Silver só tem os ~4.608
  imóveis do circuito de homologação, não os ~350-390 mil que essas camadas cobrem).
- **[EM ABERTO]** Nada ingerido ainda. Faltam os passos 2-4 do tutorial §0-bis: rodar
  ingestão de teste real, decidir a modelagem Silver (tabelas novas tipo
  `silver.reserva_legal`/`silver.app_declarada`/`silver.vegetacao_nativa_declarada`,
  cada uma com FK pra `imovel_rural`), e implementar `calcular_irl`/`calcular_iapp`/
  `calcular_icv` de verdade (hoje stub, `pkg_calculo_territorial`/`pkg_calculo_
  conservacao`) usando `SDO_RELATE` desde o início (não repetir o achado do
  `SDO_ANYINTERACT`, §9.33).

### 9.35. Catálogo de fontes reordenado por ordem de ingestão + nenhuma fonte com cron — **[DECIDIDO e aplicado, 2026-10-09]**
As 18 fontes nasciam com o ID na ordem em que foram descobertas/cadastradas ao
longo das sprints (espalhadas por 7 migrations: `V003`, `V006`, `V009`, `V025`,
`V039`, `V041`, `V053`). Pedido do time: reordenar pra refletir a **ordem desejável
de ingestão** (pré-requisitos primeiro) em vez da ordem histórica de descoberta, e
tirar o `cron_expressao` de todas (nenhuma fonte roda mais por agendamento
automático por ora — só disparo manual, até o fluxo de cálculo/publicação da Gold
estar fechado de ponta a ponta).

**IDs novos (consolidados em `V003`, que passou de "cadastro manual do CAR" pra
seed único de todo o catálogo):**

| ID | Fonte | ID antigo |
|---|---|---|
| 1 | IBGE - Malha Municipal (PR) | 7 |
| 2 | IBGE - Limites de biomas | 13 |
| 3 | CAR - Imóveis (PR) | 1 |
| 4 | IAT/PR - APP Total (CAR) | 16 |
| 5 | IAT/PR - Reserva Legal (CAR) | 17 |
| 6 | IAT/PR - Vegetação Nativa (CAR) | 18 |
| 7 | ICMBio - Unidades de Conservação (PR) | 2 |
| 8 | IAT/PR - Terras Indígenas (FUNAI) | 8 |
| 9 | MMA - Unidades de Conservacao (CNUC nacional) | 14 |
| 10 | SFB - Cadastro Nacional de Florestas Publicas (CNFP) | 15 |
| 11 | IAT/PR - Embargos IBAMA | 5 |
| 12 | IAT/PR - Embargos ICMBio | 9 |
| 13 | IAT/PR - Autos de Infração IBAMA | 10 |
| 14 | IAT/PR - Autos de Infração ICMBio | 11 |
| 15 | INPE (PRODES) - Desmatamento Mata Atlântica (PR) | 3 |
| 16 | IAT/PR - Focos de Calor (INPE) | 4 |
| 17 | INPE (Queimadas) - Focos de calor, Brasil (mensal) | 6 |
| 18 | CAR - Imóveis (PR) via Shapefile (upload) | 12 |

**3 pegadinhas reais achadas fazendo isso (nenhuma óbvia antes de tentar) — guardar
pra não repetir o retrabalho:**
1. **`fonte_dado.entidade_silver` e `campo_geometria_lat`/`campo_geometria_lon` não
   existem ainda no ponto em que o seed consolidado roda** (`V003`, a migration
   mais antiga que semeia `fonte_dado`) — essas colunas só nascem em `V010`
   (`ADD entidade_silver`) e `V005` (`ADD campo_geometria_lat/lon`), ambas
   numericamente DEPOIS de `V003`. Botar essas colunas direto no `INSERT` do `V003`
   quebra com `ORA-00904: invalid identifier`. Corrigido: `V003` só usa as colunas
   que já existiam desde `V001`; `entidade_silver` passou a ser setado em bloco no
   `V031` (que já roda depois do `V010`), **por NOME, não por id** — e
   `campo_geometria_lat/lon` da fonte CSV do INPE em um `UPDATE` no `V006` (que já
   roda depois do `V005`). Regra geral: ao consolidar um seed na migration mais
   antiga possível, cada coluna usada no `INSERT` tem que já existir **naquela
   versão**, não na versão atual da tabela.
2. **`V031__seed_mapeamentos_fontes_padrao.sql` tinha ~90 ocorrências de
   `fonte_dado_id` **hard-coded por número** (`WHERE id IN (1, 12)`,
   `VALUES (2, 'cnuc', ...)` etc.), um padrão bem diferente do resto do projeto
   (que resolve por `nome` via subquery) — passou batido numa primeira varredura
   por grep porque o padrão de busca procurava `fonte_dado_id = N`, não `f.id IN
   (N)`/`VALUES (N,`. **Lição**: ao reordenar/renumerar qualquer catálogo com ID
   `IDENTITY`, rodar pelo menos 2 buscas (uma pelo nome da coluna FK, outra por
   `VALUES (` seguido de número pequeno) antes de assumir que achou todas as
   referências. Corrigido com substituição em passe único (script Python, evitando
   colisão entre id antigo e novo no meio da troca) + reescrita do bloco de
   `entidade_silver` pra usar nome em vez de id (elimina a fragilidade de vez).
3. **`V048` e `V051` também tinham `fonte_dado_id` fixo por número** (correções de
   mapeamento achadas testando ingestão real, ex. `WHERE fonte_dado_id = 2`) -
   atualizados pros IDs novos, com o número antigo anotado em comentário pra
   rastreabilidade.

Migrations que perderam efeito prático (`V035`, `V052` — preenchiam/ajustavam cron;
`V055`, `V056`, `V058` — fixes das 3 fontes novas do CAR, agora já corretas desde o
cadastro) foram mantidas como **no-op documentado** (comentário `[SEM EFEITO - ver
X]`), não apagadas — apagar um número de versão já no histórico do Flyway é pior
que deixar um arquivo sem statement executável.

**Verificado ao vivo, banco resetado do zero**: `docker compose down` + `docker
volume rm` + `up -d` aplica as 61 migrations limpo; `fonte_dado` confere exatamente
a tabela acima, nenhuma linha com `cron_expressao`; ingestão de teste no CAR (novo
id 3) e na APP Total (novo id 4) promovem pra Silver normalmente (4.611 e 3.180
registros, bate com os números já conhecidos); suíte completa **401 testes, 0
falhas, 1 erro** (o `DatagisApplicationTests` de sempre, §9.3).

### 9.36. 4 indicadores funcionando de ponta a ponta no circuito + simplificação de mapa migrada pra Java/JTS — **[FEITO e validado contra o Oracle real, 2026-10-11, `LIZARDS-24`/`LIZARDS-50`]**
Sessão longa, trocou Oracle Free por Enterprise Edition (§9.34/tutorial próprio),
filtrou os endpoints restantes pelo circuito (`V061`) e destravou os 4 indicadores
com fonte (ISAP, IAE, IDesmat, IFC) de verdade contra dado real, não só sintético.
Design original e formulário em `docs/gold-procedures-calculo-publicacao.md` §9.

**Bug 1 — ISAP/IAE/IDesmat zerando ou explodindo com imóvel real (`V060`/`V063`/`V064`):**
- `V060` já corrigia o padrão "dissolver antes de interseccionar quebra com geometria
  'inválida' real" pra ISAP/IAE (achado anterior a esta sessão). `V064` replicou o
  mesmo fix pro IDesmat, que tinha o mesmo bug e nunca tinha sido corrigido lá.
- **Achado novo nesta sessão**: alguns imóveis tinham `SDO_GEOM.SDO_AREA` da própria
  geometria do imóvel devolvendo um valor absurdo (12,75 **bilhões** de hectares pra
  um imóvel de 0,18 ha segundo `area_ha`, calculado em Java/JTS na ingestão) —
  geometria com vértice duplicado faz o Oracle devolver área/interseção sem sentido
  **sem lançar erro**. Como `area_ha` (JTS) já é confiável, a correção (`V063`/
  `V064`) é um sanity clamp: a área de interseção nunca pode exceder a área do
  próprio imóvel — se `SDO_GEOM` devolver mais que isso, trunca pra `area_ha`, nunca
  propaga um percentual impossível (chegava a 12 milhões% antes do fix).

**Bug 2 — camada de mapa DESMATAMENTO travando/estourando memória (resolvido
removendo a causa, não contornando) — `LIZARDS-50`:**
Publicar a camada de mapa (bloco 2, `SDO_UTIL.SIMPLIFY` em PL/SQL) contra os 1.428
polígonos reais do PRODES expôs, em sequência, 4 sintomas que pareciam problemas
diferentes mas eram o mesmo limite de fundo:
1. 1 geometria com 634 mil ordenadas fazia o `SIMPLIFY` sozinho estourar
   `ORA-04036` (PGA 8GB) — tentativas de mitigar (pular por tamanho de vértice,
   lote pequeno + timeout, liberar memória de sessão a cada N linhas) reduziam o
   estrago mas nunca resolviam de vez, porque **outras** geometrias, isoladas,
   também estouravam sozinhas (não era 1 outlier, era o motor de `SIMPLIFY` do
   Oracle sendo caro demais em memória pra geometria complexa em geral).
2. **Decisão de negócio: nenhum registro pode ser descartado** (nem o mais pesado)
   — forçou resolver de verdade, não mascarar. Solução: `SimplificadorGeometriaMapa`
   (Java/JTS, `TopologyPreservingSimplifier`) substitui o `SDO_UTIL.SIMPLIFY` **só**
   na publicação de camada de mapa (não nos cálculos de indicador, que continuam em
   PL/SQL — fazem interseção espacial de verdade, perto do dado). Roda no heap da
   JVM do backend (`-Xmx8g`), memória separada do PGA do Oracle. `V070` removeu a
   procedure `publicar_camada_mapa_lote` (ficou sem uso).
3. Daí dois bugs secundários, achados só depois que o motor de memória parou de
   ser o problema: `ORA-01461` (WKT simplificado >4000 caracteres não pode ser bind
   de `String` comum — corrigido com `java.sql.Clob`, mesmo padrão já usado em
   `PersistidorSilverService`) e colisão de número de versão (`ORA-00001`) com dado
   publicado **antes** da tabela de controle de versão existir — corrigido calculando
   a próxima versão como `GREATEST` entre `gold.publicacao_camada_mapa` e
   `gold.feature_publicada`, não só a primeira.
- **Resultado final**: as 6 camadas de mapa publicam 100%, 0 pulados (399 município,
  16.059 área protegida, 1.428 desmatamento, 4.611 imóvel, 2.949 foco de calor,
  incluindo a geometria de 634 mil ordenadas que antes era sempre descartada).
- **Controle de execução novo** (`V067`): `gold.publicacao_camada_mapa`
  (status/andamento — `total_processado`/`total_publicado`/`total_pulado`) +
  `gold.publicacao_camada_mapa_erro` (1 linha por registro pulado, sempre com
  motivo — nunca mais só `DBMS_OUTPUT.PUT_LINE` que ninguém olhava). Endpoints:
  `POST /api/gold/mapa-camadas/{codigo}/publicar`,
  `GET /api/gold/mapa-camadas/{codigo}/status`,
  `GET /api/gold/publicacoes/{id}/erros`. A versão nova só fica "visível" (quem for
  ler depois filtra por `status='CONCLUIDA'`) se processar inteira — nunca aparece
  pela metade.

**Bug 3 — CPF/CNPJ sem zero à esquerda rejeitando Embargos/Autos de verdade:**
Fontes que mandam CPF/CNPJ como **número** (não string) no JSON/GeoJSON perdem
zero(s) à esquerda no parsing (`"4579237927"` chegando com 10 dígitos em vez de 11,
`"365707961"` com 9). `ValidadorCpfCnpj` agora repõe o(s) zero(s) perdido(s) antes
de validar — o dígito verificador (módulo 11) continua sendo a prova real, se não
bater mesmo com o zero reposto segue inválido. Resolveu Autos ICMBio (30/30
promovido) de primeira.

**Bug 4 — referência `num_auto_infracao` nos Embargos bloqueando mesmo sem CPF
inválido, causa raiz diferente da esperada:**
Depois do fix de CPF, Embargos IBAMA/ICMBio continuavam retidos por
`REFERENCIA_NAO_ENCONTRADA` (`num_auto_infracao` → `silver.auto_infracao`).
Cruzando contra os serviços reais do IAT/PR: o campo `num_auto_infracao` do
**Embargo IBAMA** é alfanumérico (`"DBK2XZIO"`), mas o `num_auto_infracao` real dos
**Autos IBAMA** é sempre numérico puro (`"368471"`) — formatos incompatíveis, nunca
foi o mesmo identificador (provavelmente um código interno do GIS do IAT/PR, não
uma FK de verdade). Pro ICMBio a referência é legítima (mesmo formato alfanumérico
dos dois lados), só incompleta pelo recorte do circuito. Como o campo não é
necessário pro cálculo do IAE (usa a geometria, não essa referência), relaxado nos
dois (`V071`, `referencia_entidade_silver = NULL`). **Achado no caminho**: só mudar
a config no banco não bastou — `OrquestradorValidacao.inferirReferenciaEntidadeSilverCanonico`
tinha um fallback hardcoded que reaplicava a checagem por nome de atributo mesmo
com a config explícita desligada; removido o caso `num_auto_infracao`/`auto_infracao`
dessa inferência (teste `OrquestradorValidacaoTest` ajustado).
**Novo endpoint** `POST /api/lotes/{id}/reprocessar` (`LoteIngestaoController`) —
revalida um lote já ingerido contra a esteira de validação **atual**, sem precisar
que a fonte externa mude (a detecção de "sem mudança", RN018, bloquearia uma nova
ingestão real) — útil sempre que um bug de validação for corrigido depois que um
lote já foi pra quarentena.

**Resultado final, `POST /api/gold/publicar` completo contra os 4.611 imóveis**:
4.611/4.611 publicados, 0 falha; 6/6 camadas publicadas, 0 falha. Indicadores com
valor real (versão mais recente por imóvel, não histórico acumulado): ISAP 782
imóveis, IAE 24, IDesmat 1.670, IFC 10. `silver.embargo_ambiental` = 124 registros
(era 0 a sessão inteira). ICV/IRL/IAPP seguem stub — ver §9.37 pro gap achado
analisando o que falta pra implementar de verdade.

### 9.37. IRL/IAPP/ICV — implementados, decisão do percentual mínimo de RL tomada — **[FEITO, 2026-10-11, `V072`/`V073`]**
`docs/gold-procedures-calculo-publicacao.md` §9.1 (2026-10-10) já mapeava a fórmula
e confirmava a fonte ingerida (`app_declarada` 3.180, `reserva_legal` 2.777,
`vegetacao_nativa_declarada` 3.182 — conferido de novo ao vivo em 2026-10-11, bate
exato). **Achado analisando o que faltava pra implementar de verdade**: a fórmula do
IRL pede comparar `SUM(area_ha)` de `reserva_legal` contra "o mínimo exigido pelo
bioma" — mas `silver.bioma` **não tem nenhuma coluna de percentual mínimo**, e
**nenhum dos 399 municípios tem `bioma_predominante_id` preenchido** (tudo NULL).
Esse vínculo nunca foi populado. **Decisão tomada**: fixar 20% como constante na
procedure (`c_percentual_minimo_mata_atlantica`, `V072`) em vez de popular o vínculo
de verdade — defensável porque o recorte territorial do desafio **é** Paraná, bioma
predominante Mata Atlântica (CLAUDE.md §1). Reversível com esforço pequeno e
contido se o projeto um dia sair desse recorte (3 passos documentados no comentário
da migration: coluna nova em `silver.bioma`, popular `bioma_predominante_id` via
`SDO_RELATE` município×bioma, trocar a constante por JOIN na procedure - nenhum
deles afeta os outros 6 indicadores nem invalida resultado já publicado, graças ao
versionamento por hash). IAPP (`V072`, interseção `app_declarada` × `vegetacao_nativa_declarada`,
mesmo padrão de intersecção-individual-depois-dissolve da V060) e ICV (`V073`, razão
direta de área) não dependiam dessa peça - implementados junto. **Testado ao vivo**:
imóvel real deu ICV=20,27%, IAPP=99,71%, IRL=18,93% (abaixo do mínimo de 20% - caso
real de não conformidade, exatamente o que o indicador deve capturar). Os 7
indicadores do desafio agora calculam de verdade no circuito de homologação.

### 9.38. Roadmap: rastreamento de status/andamento ainda só existe na Gold, falta em Ingestão e Bronze→Silver
Construído nesta sessão (§9.36) um padrão de controle de execução pra publicação Gold
(indicadores + camadas de mapa): tabela de controle com status
`EM_ANDAMENTO`/`CONCLUIDA`/`FALHOU`, contadores de andamento, tabela de erro por
registro, endpoint `GET` de status. Motivo: o time vai construir uma tela de
acompanhamento de processos, e precisa saber o que está rodando e o estado atual sem
adivinhar.

**Achado que expõe a mesma lacuna em outros 2 lugares**: tanto a execução de
ingestão (`IngestaoController`/`IngestaoExecucaoService.executar`, dispara fetch +
grava Bronze) quanto a transição Bronze→Silver (`TransicaoBronzeSilverService.processarLote`,
chamada automaticamente ao fim de cada ingestão, e manualmente via
`POST /api/lotes/{id}/reprocessar`, §9.36) são **100% síncronas, sem rastreamento
nenhum** - a chamada HTTP só retorna quando tudo termina, e nada fica gravado como
"em andamento" enquanto roda. Confirmado em código (sem `@Async`,
sem `CompletableFuture`): se o usuário atualizar a tela no meio, o processo **continua
rodando no backend** (não depende do navegador), mas a tela fica cega até o processo
terminar sozinho e gravar o resultado final - não tem como saber que algo está
rodando, só esperar.

**Decisão de abordagem, já validada 2x nesta sessão (indicadores, camadas de mapa)**:
envolver por fora, nunca mexer na lógica interna que já funciona e já foi testada a
fundo (ingestão contra as 18 fontes reais, Bronze→Silver com toda a resiliência
documentada em §5.8/§9.23-25). Concretamente: grava uma linha "EM_ANDAMENTO" antes
de chamar o método existente, grava o resultado final depois (sucesso ou erro,
relançando a exceção original pro `TratadorDeErro` continuar convertendo em
400/502 do jeito que já faz hoje), nunca muda a assinatura nem o corpo do método
existente, nunca muda a resposta do endpoint HTTP já consumido pela tela atual - o
status novo é um efeito colateral consultável por um endpoint `GET` separado.

**[EM ABERTO, registrado como roadmap, não implementado ainda]** - decisão da equipe
de focar primeiro em fechar o que falta na Gold (ver §9.36/§9.37, "endpoint de status
unificado" em andamento) antes de estender esse padrão pra Ingestão e Bronze→Silver.

### 9.39. Login e controle de acesso (RBAC) com Keycloak — **[FEITO e testado ao vivo, 2026-10-12, `LIZARDS-47`]**
Branch `feat/rbac-administrador` (backend e frontend), a partir da `sprint-2`. Guia de uso
e de manutenção em **`docs/controle-de-acesso-rbac.md`**. Substitui o bypass da §5.3.

- **Decisão da equipe (aprovada 2026-10-12):** identidade no **Keycloak**, não no Oracle —
  as tabelas `usuario`/`papel`/`usuario_papel` previstas na §2.7 **não serão criadas**.
- **Dois níveis:** o código confere só **roles de permissão** (`fonte:visualizar`,
  `lote:gerenciar`, ... — enum `Permissao`); **perfis** são roles compostas do Keycloak
  (enum `Perfil`). Usuário pode ter vários perfis + permissões avulsas. Os 4 tickets do
  board (LIZARDS-45 Operador, 46 Analista, 47 Administrador, 48 Auditor) = 1 perfil cada,
  **1 PR por ticket**; o 47 foi primeiro e carrega a fundação.
- **Backend:** `spring-boot-starter-oauth2-resource-server` valida o JWT. Matriz de acesso
  inteira no `SecurityConfig`, por rota; `anyRequest().denyAll()` no fim (rota nova nasce
  fechada). `ConversorRolesKeycloak` mapeia `realm_access.roles` → authorities sem prefixo.
  `KeycloakProvisionador` sincroniza o catálogo do código com o Keycloak na subida (cria,
  ajusta composição, remove permissão que saiu do perfil; cria usuário de teste por perfil
  se `app.keycloak.usuarios-dev=true`). `/api/admin/**` (cadastro de usuário, roles, senha
  temporária) via `KeycloakAdminClient` (`RestClient` puro — a lib `keycloak-admin-client`
  traz Jackson 2). Trava: admin não se desativa nem tira de si `usuario:gerenciar`.
- **Frontend:** `keycloak-js` (Authorization Code + PKCE), app só monta logado; `apiFetch`
  (`services/http.js`) substituiu os 36 `fetch` crus; `meta.permissao` nas rotas + guarda
  no router; menu filtrado; rodapé com usuário real + sair; tela `/admin/usuarios`.
- **Infra:** serviço `keycloak` (26.8.0, `start-dev`, H2 no volume `keycloak-data`), porta
  8180. Realm `datagis` em `infra/keycloak/realm-datagis.json` (só clients + conta de
  serviço — importado só na 1ª subida). `KC_HOSTNAME` fixo pra o `iss` não variar entre
  navegador (`localhost:8180`) e backend (`keycloak:8080`).
- **Testes:** `MatrizAcessoTest` (56 casos — libera com a permissão, nega com todas as
  outras, 401 sem token, e varre todos os controllers pra pegar endpoint sem regra),
  `ConversorRolesKeycloakTest`, `UsuarioAdminServiceTest`, `KeycloakProvisionadorTest`.
  Backend **473 testes, 0 falhas** (incluindo os `*OracleIT`). Frontend 60 testes.
- **Validado ao vivo:** 401 sem token; 401 com payload adulterado, assinatura adulterada e
  token de outro realm; usuário só com `lote:visualizar` → 200 em `GET /api/lotes`, 403 em
  liberar lote, fontes e admin.
- **[EM ABERTO]**
  - `conjuntosDadosService` (tela `/conjuntos-dados`, `dados:visualizar`) também chama
    `GET /api/quarentena` → exige `quarentena:visualizar`. O Analista (LIZARDS-46) não tem
    essa permissão: decidir no ticket dele se o perfil ganha ou a tela degrada.
  - `analista_responsavel_id NUMBER` da quarentena não comporta o id do Keycloak (UUID);
    as telas ainda mandam `analistaId = 1` fixo. Identidade real do autor vai pro log de
    auditoria (LIZARDS-48).
  - Botões de ação ainda não escondem por permissão (o backend já barra com 403) — cada
    ticket de perfil faz isso nas telas dele. **Fontes, Mapeamento, Lotes e Quarentena
    feitos na LIZARDS-45.**
- **Mudança de plano (2026-10-13):** os 4 tickets vão na **mesma PR**
  (`feat/rbac-administrador`), um commit por ticket.
- **LIZARDS-45 (Operador) — FEITO 2026-10-13:** `Perfil.OPERADOR` (`fonte:*`, `lote:*`,
  `quarentena:*`, `dados:visualizar`) + usuário de teste `operador`. Telas escondem ações
  sem a permissão de escrita (tabela em `docs/controle-de-acesso-rbac.md`).
  `MatrizAcessoTest.perfilAcessaSoOQueOPacoteDele` testa todo perfil × toda rota (perfil
  novo entra sozinho); `PerfilTest` trava a composição aprovada. Confirmado ao vivo que o
  provisionador cria perfil novo num Keycloak já existente, sem recriar volume. Backend 538
  testes, frontend 64.
- **LIZARDS-46 (Analista) — FEITO 2026-10-13:** `Perfil.ANALISTA` (`conjunto:*`,
  `indicador:*`, `fonte:visualizar`, `dados:visualizar`) + usuário `analista`. Resolve o
  [EM ABERTO] da `/conjuntos-dados`: a aba/atalhos "Quarentena" só aparecem com
  `quarentena:visualizar` (Analista compara Bronze e Silver). Formulário de Conjuntos e
  ações de tipo de indicador (Gold) escondidos sem permissão de escrita. Gold continua fora
  do menu (LIZARDS-27). Backend 565 testes, frontend 67.
- **LIZARDS-48 (Auditor) — FEITO 2026-10-13:** `Perfil.AUDITOR` (todas as `:visualizar` +
  `auditoria:visualizar`, listadas uma a uma) + usuário `auditor`. **Log de auditoria**:
  `V075__criar_log_auditoria.sql` (`controle.log_auditoria`, trigger append-only
  `ORA-20025` — RN025, finalmente implementada; ver §2.7). `FiltroAuditoria` dentro da
  cadeia do Spring Security, **antes do `ExceptionTranslationFilter`** — assim já sabe o
  usuário do token e ainda vê o 403 (tentativa negada também fica registrada). Não é
  `@Component` (seria registrado 2x). `DescricaoAcao` traduz método+rota em ação legível
  casando os moldes por conta própria (o 403 nunca chega no controller).
  `GET /api/auditoria` + tela `/auditoria`. Testes: `DescricaoAcaoTest` (varre controllers:
  todo endpoint de escrita precisa de descrição), `LogAuditoriaOracleIT` (grava, filtra,
  pagina, e UPDATE/DELETE dão `ORA-20025` no Oracle real), 2 casos no `MatrizAcessoTest`
  provando a posição do filtro. Backend 604 unitários + 11 de Oracle, frontend 70.
- **Pegadinha de contagem de testes:** o surefire (config padrão) **não roda `*IT`** no
  `mvn test` comum — só `*Test`/`*Tests`. Os `*OracleIT` só executam com
  `-Dtest=*OracleIT` (+ `DATAGIS_ORACLE_IT=true`). Contagens de "suíte completa" citadas
  neste arquivo antes de 2026-10-13 não incluíam os ITs.

### 9.40. Consulta dos dados finais em tabela — backend — **[FEITO e validado contra o Oracle real, 2026-10-17, `LIZARDS-34`]**
Branch `feat/visualizacao-dados-tabela`, a partir da `sprint-2`. Esta entrega contempla
exclusivamente o backend; a interface corresponde à segunda metade do ticket e será
desenvolvida no repositório do frontend.

- **Objetivo da funcionalidade:** apresentar a relação entre os 7 indicadores em um
  determinado recorte territorial, em duas visões sobre o mesmo conjunto de dados: **por
  imóvel** (uma linha por imóvel publicado) e **por município** (uma linha por município,
  com indicadores agregados).
- **Agregação executada no banco de dados.** O recorte de homologação contém 4.611
  imóveis. A centralização do cálculo em uma única camada garante consistência entre a
  tela de tabela e a de mapa (LIZARDS-35), que consomem o mesmo indicador.
- **Média ponderada pela área, não média aritmética simples.** Em um município com uma
  propriedade de 10.000 ha e cinquenta de 2 ha, os dois métodos produzem resultados
  divergentes; para indicadores ambientais, a unidade de análise relevante é o território,
  não o número de propriedades. O denominador considera a área apenas dos imóveis que
  possuem valor para o indicador em questão — incluir os demais faria com que a ausência
  de cálculo fosse interpretada como valor zero, reduzindo indevidamente a média.
- **Alcance da ponderação por área.** Para os indicadores cujo denominador é a área do
  imóvel — ICV, IRL, ISAP, IAE e IDESMAT — a média ponderada reproduz exatamente o
  indicador territorial, pois a soma dos numeradores dividida pela soma das áreas é a
  própria definição do índice no conjunto. O IAPP é exceção: a V072 o calcula sobre a área
  de APP declarada, não sobre a área do imóvel, de modo que a ponderação pela área do
  imóvel constitui aproximação. O IFC, apesar de ser indicador de densidade, também é
  reproduzido de forma exata: como `valor_ifc = focos / área × 1000`, a soma de
  `valor_ifc × área` dividida pela soma das áreas resulta em `Σfocos × 1000 / Σárea`, que é
  a própria densidade de focos do município. A correção do IAPP exigiria publicar a área de
  APP por imóvel na Gold, alteração que pertence às procedures (§9.37).
- **Distinção entre `NULL` e zero.** O `NULLIF(..., 0)` no denominador previne divisão por
  zero, e o `RowMapper` utiliza `rs.wasNull()` porque `getDouble` retorna `0` para valores
  nulos. Indicador não calculado é serializado como `null` e renderizado como célula vazia;
  valor zero efetivamente calculado é preservado e classificado na faixa correspondente. A
  interface depende dessa distinção para não classificar como crítico um imóvel que apenas
  não teve o indicador calculado.
- **Exposição de `qtdImoveis` e `areaTotalHa`:** fornecem o contexto necessário à
  interpretação do resultado agregado. Um município com 2 imóveis e 60.000 ha indica
  concentração — o resultado é determinado por uma única propriedade.
- **`V076__tipo_indicador_dimensao_e_faixas.sql`:** a tabela `gold.tipo_indicador` recebe
  as colunas `dimensao` (CONSERVACAO | TERRITORIAL | AMBIENTAL), `sentido` (MAIOR_MELHOR |
  MENOR_MELHOR), `limite_bom`, `limite_atencao` e `ordem_exibicao`, com restrições CHECK
  sobre os dois domínios. O agrupamento por dimensão e os limites das faixas de
  classificação residem no banco de dados: alterações de limite são efetuadas por `UPDATE`,
  sem necessidade de nova versão do frontend. A coluna `sentido` é necessária porque ISAP,
  IAE, IDESMAT e IFC são indicadores de polaridade inversa, nos quais valores menores
  representam melhor desempenho; sem essa informação, o pior resultado seria classificado
  como adequado.
- **Polaridade do ISAP.** Apesar do nome sugerir o contrário, o ISAP é `MENOR_MELHOR`. A
  `pkg_calculo_territorial` (V063) o define como a sobreposição do imóvel com unidade de
  conservação, ou seja conflito territorial, sendo gêmeo do IAE em fórmula e em leitura. A
  primeira versão da V076 o classificou como `MAIOR_MELHOR`, o que pintaria de verde
  justamente o imóvel em situação mais irregular.
- **Origem dos limites de faixa.** O PDF do desafio define a fórmula e a unidade de cada
  indicador, não faixas de classificação; os valores gravados são portanto decisão de
  produto e podem ser revistos pela equipe por `UPDATE`, sem alteração de código. A única
  exceção é o IRL, que recebe 20 em `limite_bom` e em `limite_atencao` porque o percentual
  mínimo de Reserva Legal na Mata Atlântica é 20% (Código Florestal, Lei 12.651/2012,
  constante já fixada na V072): abaixo desse valor o imóvel está em desacordo com a lei,
  acima está em conformidade, e não há faixa intermediária a representar.
- **`V077__unidade_do_tipo_indicador.sql`:** acrescenta `unidade` (PERCENTUAL | DENSIDADE)
  e `unidade_simbolo`, com restrição CHECK sobre o primeiro domínio e `DEFAULT` nas duas
  colunas, de modo que indicador cadastrado posteriormente pela tela de administração
  nasça como percentual. Seis dos sete indicadores são percentuais; o IFC não é: a
  `pkg_calculo_ambiental` (V045) o calcula como focos de calor dentro do imóvel dividido
  pela área do imóvel multiplicado por mil — focos por mil hectares — e grava `NULL` na
  coluna percentual do resultado, deliberadamente. A página 6 do PDF do desafio define a
  mesma fórmula. Sem essa informação no catálogo, a interface desenharia a barra do IFC em
  escala percentual e exibiria o símbolo incorreto ao lado do valor. O comentário da V045
  documenta a unidade para quem lê o SQL, mas não serve à aplicação para decidir como
  exibir o número — razão pela qual se tornou coluna.
- **Catálogo de indicadores sem endpoint adicional:** o `TipoIndicadorResponse` passou a
  expor os sete campos, de modo que o `GET /api/indicadores/tipos` já existente atende ao
  consumo de dimensões, faixas e unidades pelo frontend.
- **Endpoints:** `GET /api/indicadores/imoveis` e `GET /api/indicadores/municipios`
  (`ConsultaIndicadoresController` e `ConsultaIndicadoresService`). A implementação utiliza
  `JdbcTemplate` com SQL nativo, seguindo o padrão adotado no `DominioService`, por se
  tratar de consultas que cruzam schemas (`GOLD.imovel_publicado` e `SILVER.municipio`) e
  realizam agregação. Os nomes de schema são obtidos via
  `@Value("${spring.flyway.placeholders.*Schema}")`, sem valores fixos no código.
- **Justificativa do uso exclusivo da camada Gold:** os valores de indicador são gravados
  pelas procedures durante a promoção (§9.33, §9.36, §9.37) e existem somente nessa
  camada; a Silver não possui a coluna `valor_icv`. A única informação obtida fora da Gold
  é o nome do município (`LEFT JOIN SILVER.municipio`), tabela de referência cadastral. O
  `LEFT JOIN` é intencional: imóvel cujo município ainda não foi carregado na Silver
  permanece na listagem, sem o nome resolvido.
- **Controle de acesso:** as rotas são cobertas pela regra preexistente para
  `GET /api/indicadores/**`, que exige a permissão `indicador:visualizar`. Nenhuma regra
  nova foi adicionada ao `SecurityConfig`.
- **Testes:** o `ConsultaIndicadoresServiceTest` (11 casos) captura o SQL gerado e valida o
  acesso à Gold, o join com a Silver, o `GROUP BY` e a ponderação por área — incluindo
  asserção `doesNotContain("AVG(")`, que impede a substituição por média aritmética
  simples. Em seguida captura o `RowMapper` e o executa contra um `ResultSet` simulado,
  comprovando a conversão de `NULL` para `null` e a preservação do valor zero. O
  `ConsultaIndicadoresControllerTest` (5 casos) valida o contrato JSON dos dois endpoints.
  O `TipoIndicadorTest` passou de 5 para 6 casos, cobrindo os campos de catálogo no builder
  — com o IFC como caso real de densidade — e conferindo por reflexão os nomes de coluna
  que divergem do atributo (`limite_bom`, `limite_atencao`, `ordem_exibicao`,
  `unidade_simbolo`), divergência que só se manifestaria em tempo de execução contra o
  banco. O `TipoIndicadorControllerTest` passou de 10 para 11 casos, validando os sete
  campos novos no JSON; o ISAP é montado sem unidade e a asserção exige `PERCENTUAL` e `%`
  na resposta, cobrindo o caminho completo do valor padrão, do `@Builder.Default` até a
  serialização. **Total: 18 testes novos, 0 falhas.** Nenhum deles inicializa o contexto
  Spring ou acessa o Oracle, permitindo execução em pipeline sem banco de dados.
  **Suíte completa: 631 testes, 0 falhas, 10 pulados.**
- **Validação em ambiente real (token do perfil `analista`):** `/municipios` retorna
  Curitiba (111 imóveis, 1.316,97 ha), Maringá (1.843 imóveis, 35.839,45 ha) e Ponta Grossa
  (2.657 imóveis, 187.029,84 ha); `/imoveis` retorna o código completo e o nome do
  município resolvido a partir da Silver. Os totais são consistentes com
  `SELECT COUNT(DISTINCT municipio_cod_ibge), COUNT(*) FROM GOLD.imovel_publicado`, que
  retorna 3 e 4.611. A consulta ao catálogo após as duas migrations devolve as sete linhas
  esperadas: ICV, IRL e IAPP como `MAIOR_MELHOR`; ISAP, IAE, IDESMAT e IFC como
  `MENOR_MELHOR`; IRL com 20 em ambos os limites; IFC como `DENSIDADE` com símbolo
  `focos/1.000 ha` e os demais como `PERCENTUAL` com `%`.
- **Escopo limitado a municípios com dado publicado.** A tabela `SILVER.municipio` contém
  os 399 municípios do Paraná; a consulta retorna 3 porque apenas esses possuem imóveis
  promovidos à Gold. Municípios sem promoção não possuem indicadores calculados, e sua
  inclusão resultaria em 396 linhas sem conteúdo. A listagem é ampliada automaticamente à
  medida que novos municípios forem ingeridos, sem alteração de código.
- **Identificação do imóvel pelo código CAR.** A camada pública do IAT/PR
  (`geopr.iat.pr.gov.br/.../Base_Geo_Cadastro_Ambiental_rural`) não disponibiliza o nome do
  imóvel, por se tratar de dado pessoal sob a LGPD; o campo `nomeImovel` está disponível
  apenas na API oficial do SICAR, cujo acesso exige credenciamento. Essa é a razão de
  `silver.imovel_rural` não possuir coluna `nome`, enquanto `silver.area_protegida` possui.
  A API retorna o código integral (`PR-4106902-00B2EA81...`); a abreviação é decisão de
  apresentação do frontend, uma vez que a busca pelo código completo deve permanecer
  funcional — há teste automatizado assegurando esse comportamento.
- **Restrição de privilégio em migrations sobre schema de terceiros:** a instrução
  `COMMENT ON COLUMN` aplicada a tabela de outro schema resulta em **`ORA-41900`**, pois o
  usuário da aplicação possui `CREATE/ALTER ANY TABLE`, mas não `COMMENT ANY TABLE`. Como
  o Oracle executa commit implícito em comandos DDL, o `ALTER TABLE ... ADD` é persistido
  mesmo quando a migration falha em instrução posterior. O saneamento exige o `DROP` das
  colunas criadas e a remoção do registro correspondente em
  `DELETE FROM flyway_schema_history WHERE "version" = '0XX'` (nomes de coluna em minúsculas
  e entre aspas). Por esse motivo, a V076 e a V077 foram escritas sem instruções `COMMENT`.
- **Alteração de migration antes do merge, e o custo dela.** A V076 foi corrigida dentro
  da própria branch, após o primeiro push e antes do merge, por conter valores errados de
  `sentido` e de limite. A opção foi legítima porque a branch não havia sido integrada e
  nenhum outro ambiente havia aplicado o arquivo — condição confirmada com a equipe antes
  da alteração. O custo é que o Flyway guarda o checksum do arquivo no momento da
  aplicação e aborta a inicialização quando ele diverge; o banco local exigiu
  `UPDATE flyway_schema_history SET "checksum" = NULL WHERE "version" = '076'`, após o que
  o próprio Flyway regrava o valor correto na subida seguinte. O nulo vale uma única vez e
  substitui uma verificação automática por uma conferência manual, que neste caso foi feita
  consultando as sete linhas de `tipo_indicador`. **Após o merge essa opção deixa de
  existir:** migration integrada não se altera, e qualquer ajuste passa a exigir migration
  nova, sob pena de impedir a inicialização do ambiente de todos os integrantes.
- **Somente a versão mais recente de cada imóvel (commit `e2a4ac9`, 2026-10-17):** `gold.imovel_publicado` guarda uma linha por versão publicada
  (chave `cod_imovel + indicador_versao_id`, histórico de auditoria). As duas consultas
  do `ConsultaIndicadoresService` liam a tabela inteira: imóvel republicado aparecia
  **duas vezes** na visão por imóvel e entrava **em dobro** na visão por município
  (quantidade, área e média). Reproduzido ao vivo ingerindo os embargos e recalculando 1
  imóvel de Ponta Grossa: 4.611 → 4.612 linhas, Ponta Grossa 2.657 → 2.658 imóveis e
  187.061 → 189.297 ha. Corrigido com `ROW_NUMBER() OVER (PARTITION BY cod_imovel ORDER
  BY data_publicacao DESC, id DESC)` + `WHERE i.ordem_versao = 1` nas duas consultas —
  mesmo critério da comparação Silver → Gold da LIZARDS-30. 2 testes novos no
  `ConsultaIndicadoresServiceTest` (um por visão). **Regra para qualquer consulta futura
  sobre `imovel_publicado`: filtrar a versão mais recente** — ler a tabela inteira mostra
  o histórico, não o estado atual.
- **[EM ABERTO]**
  - **Perfil Operador sem acesso à tela.** O `Perfil.OPERADOR` não possui a permissão
    `indicador:visualizar` (§9.39, LIZARDS-45); a tela estará disponível para
    Administrador, Analista e Auditor, mas não para o Operador. Caso a equipe decida
    conceder a permissão, a alteração consiste em uma linha no `Perfil.java` e o ajuste
    correspondente no `PerfilTest`, a ser entregue como `fix<LIZARDS-34>`.
  - **Zeros por ausência de declaração — verificado em 2026-10-19.** Os zeros de IRL, ICV
    e IAPP não são erro de cálculo nem perda no pipeline: a contagem na fonte (ArcGIS do
    IAT/PR, filtrada pelo circuito), na Bronze e na Silver é idêntica — APP 3.180, Reserva
    Legal 2.777, Vegetação Nativa 3.182 — e a quarentena dessas entidades está vazia. A
    origem dos zeros é a inicialização `v_percentual := 0` nas procedures (V072/V073),
    gravada mesmo quando o imóvel não possui linha de origem; não é regra do desafio, que
    define a fórmula de cada indicador mas não o tratamento da ausência de declaração.
    Classificação dos zeros publicados: IRL 1.876 (1.875 sem Reserva Legal declarada, 1
    com declaração de área 0); ICV 1.440 (todos sem vegetação nativa declarada); IAPP 2.054
    (1.434 sem APP declarada, 504 com APP e sem vegetação declarada, 116 com ambas e sem
    sobreposição). Para IRL e ICV o zero é defensável do ponto de vista de conformidade — o
    imóvel não comprova o percentual exigido —, embora "não declarou" e "declarou zero"
    permaneçam indistinguíveis. Para o IAPP o zero é incorreto nos 1.434 casos sem APP
    declarada: o denominador é a área de APP, e sem ela o indicador é indefinido, devendo
    ser gravado `NULL`. A correção pertence às procedures (migration nova, branch própria).
  - **Imóveis com CAR cancelado ou pendente publicados na Gold.** Dos 4.611 imóveis, 69
    têm situação `CA` (cancelado) e 30 `PE` (pendente); os 69 cancelados apresentam IRL e
    ICV zerados e entram nas médias por município. Cabe à equipe decidir se a publicação
    deve se restringir a `AT`.
  - **Cadastro de indicador pela tela não preenche os campos de exibição.** O
    `TipoIndicadorRequest` não expõe `dimensao`, `sentido`, `limite_bom`, `limite_atencao`
    nem `ordem_exibicao`; um indicador cadastrado pela tela nasce com esses campos nulos e
    é exibido em grupo "sem dimensão", com coluna vazia, porque `gold.imovel_publicado`
    possui somente as sete colunas `valor_*` fixas. A alteração de limites de faixa,
    portanto, só é possível por `UPDATE` direto no banco.
  - **`GET /api/indicadores/imoveis` sem paginação.** Adequado ao circuito de homologação
    (4.611 linhas), inviável no estado inteiro (~556 mil imóveis no CAR/PR). Paginar no
    servidor antes de ampliar o recorte.
  - **Javadoc divergente do catálogo.** O comentário do campo `sentido` em
    `TipoIndicador.java` ainda classifica o ISAP como "quanto maior melhor", contrariando a
    V076 e o banco (`MENOR_MELHOR`). Corrigir apenas o comentário.
  - **ISAP e IAE em exatamente 100%.** O travamento de sanidade da V063 compara a área da
    interseção, calculada pelo `SDO_GEOM`, com a área do imóvel, calculada em Java/JTS na
    ingestão. São dois motores distintos: num imóvel inteiramente contido em área
    protegida os dois valores diferem nas últimas casas decimais e, quando o do Oracle vem
    maior, o travamento corta para a área do imóvel e o percentual fecha em exatamente 100.
    São 679 imóveis nessa condição, de 4.611. Amostragem confirmou geometria válida
    (`VALIDATE_GEOMETRY_WITH_CONTEXT` = TRUE) e diferença na sétima casa decimal entre as
    duas áreas, ou seja sobreposição real e não defeito. O valor exato de 100 continua não
    distinguindo o caso legítimo do caso de geometria corrompida descrito na V063, mas
    deixou de ser indício de problema por si só.
  - **Textos do catálogo sem acentuação.** As descrições em `tipo_indicador.descricao`
    foram cadastradas sem acento e são exibidas assim na interface. Adicionalmente, a
    `regra_calculo` de ICV, IRL e IAPP ainda os descreve como não implementados, texto
    anterior à V072 e à V073. A migration de correção está escrita, mas não foi incluída
    nesta entrega por alterar conteúdo cadastrado por outro integrante.
  - **Interseção com a LIZARDS-35 (visualização em mapa).** A agregação por município
    atende às duas telas; recomenda-se o reaproveitamento de
    `GET /api/indicadores/municipios` em vez de implementação duplicada. No frontend, ambas
    as tarefas alteram `Sidebar.vue` e `router/index.js`, sendo necessário alinhar a ordem
    de merge.

### 9.41. Consulta dos dados finais em tabela — frontend (Frontend) — **[FEITO e validado contra o backend real, 2026-10-17, `LIZARDS-34`]**
Repositório `datagis-frontend`, branch `feat/visualizacao-dados-tabela`, a partir da
`sprint-2`. Segunda metade do ticket cuja parte de backend está na §9.40. **Depende do
merge do PR de backend:** a tela lê `unidade` e `unidade_simbolo` do catálogo, colunas
criadas pela `V077`; sem elas o IFC é desenhado em escala percentual.

- **Serviço (`src/services/consultaIndicadoresService.js`):** instância Axios própria com
  `baseURL: '/api'`, seguindo o padrão do `goldService`, e as duas funções
  `listarIndicadoresPorMunicipio()` e `listarIndicadoresPorImovel()`. O tratamento de erro
  propaga a mensagem devolvida pelo backend (`response.data.erro`) e recorre a texto padrão
  quando ela não existe, de modo que a tela sempre tenha o que exibir.
- **Duas visões em abas, não duas telas.** `Por município` e `Por imóvel` consomem o mesmo
  componente e o mesmo conjunto de dados, alternando apenas a origem da listagem. A aba de
  município é a inicial porque é o recorte que responde à pergunta do desafio; a de imóvel
  é o detalhamento. Ao trocar de aba o detalhe aberto é fechado, evitando que o acordeão de
  um município permaneça visível sobre a lista de imóveis.
- **Faixa de resumo no topo:** municípios publicados, imóveis rurais e área total em
  hectares. Os três números vêm da própria resposta agregada, sem chamada adicional, e dão
  ao leitor a escala do recorte antes de ele interpretar qualquer indicador.
- **Cabeçalho agrupado por dimensão.** As colunas dos 7 indicadores são agrupadas sob
  CONSERVAÇÃO, TERRITORIAL e AMBIENTAL, e a ordem vem de `ordem_exibicao`. Nada disso está
  escrito no componente: o agrupamento, a ordem, as faixas e a unidade são lidos do
  catálogo (`GET /api/indicadores/tipos`). Acrescentar um indicador novo ou mudar um limite
  não exige alteração de frontend.
- **Detalhe em acordeão, não em modal.** Clicar na linha abre uma `<tr>` logo abaixo, com
  `colspan` sobre a largura da tabela, trazendo os 7 indicadores em barras com os limites
  marcados. Clicar de novo fecha. A escolha pelo acordeão preserva o contexto — o leitor
  continua vendo as linhas vizinhas, o que importa quando o objetivo é comparar municípios.
  Todos os detalhes começam fechados.
- **Barra consciente da unidade.** Indicador percentual usa escala fixa de 0 a 100.
  Indicador de densidade usa como escala o próprio `limite_atencao`, de modo que a barra
  cheia corresponde ao limite crítico — não existe "100%" para focos por mil hectares. A
  marca intermediária é suprimida nos dois casos em que não significa nada: em densidade, e
  quando `limite_bom` e `limite_atencao` são iguais, como no IRL. Sem isso o IRL exibiria
  duas marcas sobrepostas no mesmo ponto.
- **Cor pelo sentido, não pelo valor bruto.** A classificação em `badge-tag--sucesso`,
  `--alerta` e `--erro` compara o valor com os limites na direção indicada por `sentido`.
  Indicador sem valor recebe `--neutro` e célula vazia, nunca a faixa crítica. Zero
  efetivamente calculado recebe `min-width: 0.25rem` na barra preenchida, para permanecer
  visível em vez de desaparecer e ser confundido com ausência de dado.
- **Largura da barra arredondada.** A conversão do valor para percentual de largura produzia
  resultados como `28.939999999999998%` no atributo de estilo. O valor é arredondado em duas
  casas antes de ir ao DOM — não muda o desenho, mas evita ruído na inspeção e em teste que
  compare o estilo gerado.
- **Variáveis de tema do projeto, sem cor fixa.** Todas as cores vêm das variáveis já
  definidas em `style.css`. A consequência prática é que a LIZARDS-38, que trata do tema
  escuro, passa a cobrir esta tela sem alteração nenhuma aqui.
- **Navegação:** item "Indicadores e Resultados" no `Sidebar.vue` e rota `/indicadores` no
  `router/index.js`, com `meta.permissao: PERMISSOES.INDICADOR_VISUALIZAR` — mesma permissão
  exigida pelo backend, de modo que o item não aparece para quem não poderia consultar os
  dados de qualquer forma.
- **Testes (`src/components/IndicadoresView.test.js`, 26 casos, e
  `src/services/consultaIndicadoresService.test.js`, 5 casos):** cobrem a montagem a partir
  do que o serviço devolve, a troca de abas com fechamento do detalhe, o acordeão abrindo e
  fechando, a classificação de cor nos dois sentidos, a escala da barra em percentual e em
  densidade, o símbolo exibido ao lado do valor, célula vazia para indicador nulo, zero
  calculado permanecendo visível, a busca por município e as duas rotas do serviço com o
  tratamento de erro. `axios` e `vue-router` substituídos por `vi.mock` — a suíte roda **sem
  backend e sem Oracle**.
  - Estado em 2026-10-17: 13 arquivos, **101 testes, 100% de sucesso**.
- **`vi.resetModules()` em teste de serviço custa caro.** O teste que confere a `baseURL`
  do Axios falhava por tempo, estourando os 5 segundos padrão do Vitest. Não era lentidão
  do código e sim do `vi.resetModules()`, que descarta o registro de módulos e obriga o
  Vitest a reprocessar o `axios` inteiro. A garantia de importação limpa que ele comprava já
  existe de graça, porque o registro de módulos do Vitest é isolado por arquivo de teste.
  Removido o `resetModules`, o mesmo caso passa em poucos milissegundos, com a asserção
  intacta. **O `goldService.test.js` tem o mesmo padrão e levou 3,4 s na mesma execução** —
  passa por pouco hoje e tende a falhar em máquina carregada. Não foi alterado por pertencer
  a outra entrega; fica o registro para quem cuidar dele.
- **[EM ABERTO]**
  - **Zeros que significam ausência de declaração.** A tela distingue nulo de zero e
    possui estado neutro para indicador sem cálculo, mas depende de a Gold gravar `NULL`.
    Verificação de 2026-10-19 (§9.40): os zeros vêm da fonte, não de erro de cálculo; para
    o IAPP, 1.434 imóveis sem APP declarada deveriam chegar como `NULL` e hoje aparecem em
    vermelho. Quando a procedure for corrigida, a tela passa a exibi-los como célula vazia
    sem nenhuma alteração no frontend.
  - **Perfil Operador não vê o item de menu**, pela mesma razão descrita na §9.40: ele não
    possui `indicador:visualizar`.
  - **Interseção com a LIZARDS-35 (visualização em mapa).** Ambas as tarefas alteram
    `Sidebar.vue` e `router/index.js`; convém alinhar a ordem de merge para evitar conflito
    nesses dois arquivos.

### 9.42. Tema escuro com opção de claro ou escuro (Frontend) — **[FEITO e validado na tela, 2026-10-21, `LIZARDS-38`]**
Repositório `datagis-frontend`, branch `feat/tema-escuro`, a partir da `sprint-2`. Cobre
todas as telas do sistema. A tela de login não entra: ela é do Keycloak, outro container,
e o CSS do front não a alcança (ver o último item).

- **Regra da entrega: só acrescentar.** Nenhuma variável, classe ou linha existente foi
  renomeada ou alterada. O tema claro continua idêntico ao que era, e o escuro é uma
  camada a mais por cima: na branch são 3.945 linhas acrescentadas contra 0 removidas em
  relação à `sprint-2`.
- **Mecanismo (`src/tema.js`):** o tema vive no atributo `data-tema` do `<html>`.
  `iniciarTema()` roda no `main.js` antes de montar o app, para a tela não piscar no tema
  errado. Vale a escolha salva no `localStorage` (chave `datagis.tema`); sem escolha
  salva, segue `prefers-color-scheme` do sistema operacional, e o claro é o padrão. Sem
  `localStorage` (aba anônima, bloqueio do navegador) o tema funciona igual, só não é
  lembrado ao recarregar.
- **Paleta (`src/style.css`):** um bloco `:root[data-tema="escuro"]` com os tokens da
  paleta aprovada (`--cor-fundo`, `--cor-superficie`, `--cor-superficie-elevada`, três
  níveis de borda, seis níveis de texto, primária com hover/contraste/tênue/borda,
  `--cor-link`, cinco cores de estado e os `--tenue-*` a 12%). Um segundo bloco aponta as
  variáveis que já existiam (`--cor-branco`, `--cor-slate-*`, `--status-*`) para essa
  paleta, mudando só o valor e nunca o nome. Por isso telas que já usavam variáveis, como
  Indicadores e Gold, escureceram sem alteração nenhuma; a `IndicadoresView` é a única
  tela sem bloco escuro próprio, porque não tem cor fixa.
- **Telas com cor fixa:** cada componente ganhou, no fim do `<style scoped>`, um bloco
  `:root[data-tema="escuro"] .classe { ... }` que repinta só o que o claro define por cor
  fixa. Mais de mil cores fixas foram mapeadas para os tokens conforme o papel (fundo,
  borda, texto) e a família da cor. Uma única regra usa `!important`: a linha em edição do
  Mapeamento de Colunas, porque a regra original do claro (`.linha-ativa`) já usa.
- **Pegadinha do `<style scoped>`, achada na prática:** escrever
  `:global(:root[data-tema="escuro"]) .classe` faz o Vue descartar o `.classe`, e a regra
  vira só `:root`, pintando a página inteira. O certo é escrever
  `:root[data-tema="escuro"] .classe` direto, e o Vue escopa só a classe. Elementos criados
  por JavaScript, como os do Leaflet, precisam de `:deep(...)`. Na dúvida, conferir o
  seletor no CSS gerado pelo `vite build`.
- **Mapa sem trocar o mapa do projeto:** os tiles do OpenStreetMap continuam os mesmos; no
  escuro só o `.leaflet-tile-pane` recebe `filter: invert(1) hue-rotate(180deg)
  brightness(0.95) contrast(0.9)`. Os polígonos, desenhados em outra camada, mantêm as
  cores originais. Controles, atribuição e popups do Leaflet ganharam cores da paleta via
  `:deep()`.
- **Botão de tema na sidebar:** lua no claro e sol no escuro, no rodapé, ao lado do Sair.
  Com a sidebar recolhida, avatar, tema e Sair ficam empilhados. **O Sair visível com a
  sidebar recolhida foi combinado com o Benjamin**, autor do recuo (LIZARDS-37): antes, a
  sidebar recolhida escondia o Sair.
- **Barra de rolagem da sidebar discreta:** fica invisível e aparece ao passar o mouse. No
  Chrome, `scrollbar-width`/`scrollbar-color` fazem o `::-webkit-scrollbar` ser ignorado,
  então os dois jeitos estão escritos.
- **Campos de formulário sem classe:** uma regra global com `:where(...)` dá fundo e texto
  escuros a `input`, `select` e `textarea`. O `:where` deixa a prioridade em zero, então
  ela só preenche o que a tela não definiu e nunca passa por cima de regra de tela.
- **Menus de seleção modernos, nos dois temas (a pedido, durante a task):** a lista aberta
  do `<select>` é desenhada pelo navegador e ficava no visual do Windows. Com
  `appearance: base-select` dentro de `@supports`, ela passa a ter cantos arredondados,
  sombra, opções arredondadas e ✓ no item escolhido. Funciona no Chrome/Edge 135+; no
  Firefox e no Safari o `@supports` pula o bloco e o menu fica como antes. A tela de
  Conjuntos usa `appearance: none` com a seta própria (`.select-chevron`), então ganhou uma
  regra própria e esconde a seta do navegador (`::picker-icon`).
- **Defeito anterior que ficou visível no escuro:** na tabela do Mapeamento de Colunas, a
  célula "Regras Ativas" (`.regras-tags`) usa `display: flex`, o que a tira do alinhamento
  da linha e desloca a borda dela das outras colunas. No claro a borda é quase branca e o
  defeito não aparece. Corrigido só no escuro (a célula volta a `table-cell` e o
  espaçamento passa para as tags), para não alterar o claro.
- **Testes:**
  - `src/tema.test.js` (6 casos): preferência do sistema (escuro e claro), escolha salva
    valendo mais que o sistema, valor salvo desconhecido ignorado, alternância gravando a
    escolha e funcionamento sem `localStorage`.
  - `src/components/Sidebar.test.js` (5 casos): botão de tema, alternância, ordem
    tema/Sair, e sidebar recolhida com tema e Sair, incluindo o clique no Sair.
  - `src/temaEscuro.test.js` (65 casos): o jsdom não aplica folha de estilo, então este
    arquivo lê o CSS como texto. Confere cada token da paleta com o valor exato, que o
    `:root` sem atributo continua sendo o claro, que nenhuma tela usa `:global(:root` nem
    tem `:root[data-tema="escuro"]` solto, e que as 1.043 `var(--...)` usadas no tema
    escuro existem no `style.css` (nome de variável digitado errado não dá erro, a cor só
    some). Validado colocando três erros de propósito numa cópia temporária: cada um
    derrubou o teste certo.
  - Estado em 2026-10-21: 20 arquivos, **204 testes, 100% de sucesso**.
- **[EM ABERTO]**
  - **Tela de login (Keycloak).** Mudar o visual exige um tema de login do Keycloak (CSS
    herdando o tema padrão, montado no container e ativado no realm `datagis`), tudo no
    repositório do backend. O Keycloak não sabe qual tema o usuário escolheu no app, por
    ser outro endereço: só pode seguir a preferência do sistema ou ter visual fixo.
    Sugerida como task própria.
  - **Paleta do gráfico de diagnóstico.** As cores do `DiagnosticoChart` vêm de um array
    no JavaScript (linha 37) e o CSS não as alcança; o cinza `#475569` fica apagado no
    escuro.
  - **401 ao dar F5 em `/conjuntos`.** Já acontecia antes desta branch: o proxy do Vite
    manda `^/conjuntos($|/)` para o backend, e recarregar a tela nesse endereço cai no
    backend em vez da SPA.
  - **Telas novas precisam do bloco escuro.** Tela que use só as variáveis do `style.css`
    já nasce coberta; tela com cor fixa precisa do seu bloco `:root[data-tema="escuro"]`.
    O `temaEscuro.test.js` passa a conferir automaticamente as regras acima em qualquer
    componente novo que tenha tema escuro.

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

### 10.5. Otimização de Performance Silver & Modal de Progresso em Tempo Real (2026-09-14)
- **Diagnóstico do atraso na transição:** a promoção de lotes com 4.595 registros (CAR PR) levava de 35s a 50s devido a:
  1. `resolverIdMunicipio` executava 1 a 2 consultas sequenciais ao banco por registro (~9.000 SELECTs via TCP);
  2. Cada registro recriava e recompilava um PreparedStatement com a query MERGE espacial complexa e SDO_GEOMETRY.
- **Otimizações no Backend (`PersistidorSilverService`):**
  - Implementado `municipioCache` thread-safe (`ConcurrentHashMap`) carregando todo o catálogo dimensional de municípios uma única vez na memória ($O(1)$ lookup por código IBGE ou nome);
  - Pre-compilação e reutilização de um único `PreparedStatement` para todo o lote com liberação imediata de CLOBs temporários;
  - Logs periódicos de progresso no backend a cada 1.000 registros para monitoramento em tempo real;
  - Redução do tempo do loop de 4.595 registros para ~18s (~4ms/registro).
- **Feedback Visual em Tempo Real no Frontend (`LotesView.vue`):**
  - Substituído o popup nativo `window.confirm()` por um modal de confirmação e progresso com estética Glassmorphism;
  - Barra de progresso animada com gradiente e porcentagem dinâmica;
  - Stepper interativo com 4 etapas: (1) Validação & Deduplicação, (2) Conversão Espacial MDSYS.SDO_GEOMETRY, (3) Persistência Otimizada na Silver, (4) Baixa na Quarentena & Conclusão;
  - Atualização otimista imediata na tabela (`lote.status = 'PROMOVIDO_SILVER'` e coluna de ação `✓ Disponível na Silver`).

### 10.6. Evolução da Quarentena, Motor de Validação e Migração Unificada V036 (2026-09-24)
Branch: `bugfix/ajustes-quarentena` (Tasks: `LIZARDS-20`, `LIZARDS-23`).

1. **Correções de Inserção e Payload na Quarentena (`LIZARDS-20`):**
   - Removidos limites artificiais de tamanho no payload JSON na persistência da Quarentena (`quarentena.q_*`), assegurando suporte total a polígonos extensos (ex.: CAR com milhares de vértices).
   - Ajustada a esteira de rejeição para evitar estouro de colunas e garantir a integridade dos metadados de auditoria (`tipo_erro_id`, `campo_problema`, `valor_recebido`, `motivo_rejeicao`).

2. **Separação Canônica entre Domínio (Lookup) e Integridade Cruzada (Silver):**
   - Separação conceitual estrita:
     - `ValidadorDominio` (`tabela_referencia`): restrito a tabelas de enumeração/referência da Silver (`situacao_imovel`, `condicao_imovel`, `tipo_imovel`, `bioma`, `tipo_auto_infracao`, etc.).
     - `ValidadorIntegridadeCruzada` (`referencia_entidade_silver`): valida relacionamentos com entidades canônicas dimensionais da Silver (`municipio` e `auto_infracao`).
   - Mapeamentos atualizados de forma consistente nas fontes padrão.

3. **Gestão Dinâmica de Domínios em Tempo de Execução (`DominioService`):**
   - Implementado o serviço `DominioService` e o endpoint `POST /api/dominios/{tabela}` permitindo que analistas cadastrem novos valores de domínio diretamente durante o tratamento de registros retidos na Quarentena.
   - O cadastro dispara invalidação imediata do cache thread-safe em memória do `ValidadorDominio`, permitindo revalidação instantânea do lote.

4. **Revalidação Progressiva de Registros na Quarentena (`QuarentenaService`):**
   - Implementado método `revalidarRegistro(...)` com suporte a múltiplos erros encadeados: ao corrigir um erro (ex.: valor fora do domínio), se o registro contiver outra falha subsequente (ex.: geometria com auto-interseção), o sistema atualiza a Quarentena com o próximo erro (`ResultadoRevalidacaoQuarentena`), orientando o analista até a resolução completa do item.

5. **Preservação Estrita da Imutabilidade da Camada Bronze:**
   - As edições e correções realizadas pelo analista na Quarentena **nunca modificam** o registro bruto original em `BRONZE.registro_bruto`.
   - O dado bruto permanece preservado para auditoria e reproducibilidade; as correções são salvas em `QUARENTENA.q_*` e propagadas diretamente para a Silver após aprovação.

6. **Edição Especializada de Geometria na Quarentena:**
   - Em registros com inconsistência de geometria (`GEOMETRIA_INVALIDA`, `GEOMETRIA_MALFORMADA`, etc.), o modal de inspeção e edição na Quarentena exibe e recebe as coordenadas formatadas como lista JSON (`[ [lon, lat], ... ]`), em vez de exigir manipulação do payload bruto original.
   - `ValidadorGeometria` estendido para parsear diretamente listas de coordenadas JSON, além de WKT e GeoJSON.

7. **Cumprimento Estrito da Regra All-or-Nothing:**
   - Registros individuais corrigidos na Quarentena recebem status `CORRIGIDO_REPROCESSADO`, mas **não** são promovidos isoladamente para a Silver.
   - O lote permanece retido (`RETIDO_QUARENTENA`) até que 100% dos seus registros com inconsistências sejam resolvidos. Ao solucionar o último registro pendente, o lote inteiro é promovido atomicamente para a Silver (`PROMOVIDO_SILVER`).

8. **Unificação das Migrations Flyway (`V033` + `V034` → `V036`):**
   - Visando evitar conflitos de versionamento de migrações com branches paralelas da equipe (que criaram `V033`, `V034` e `V035`), as migrações anteriores foram unificadas no arquivo:
     - `src/main/resources/db/migration/controle/V036__atualizar_mapeamentos_dominio_e_integridade_cruzada.sql`
   - Os arquivos redundantes `V033` e `V034` foram removidos e o schema do banco sincronizado na versão `036`.


