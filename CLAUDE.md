# GeoRural DataHub — Contexto do Projeto

> Este arquivo é lido automaticamente pelo Claude Code ao abrir este diretório.
> Ele resume as decisões de arquitetura tomadas até o momento em discussões fora
> deste ambiente (chat Claude.ai). Mantenha-o atualizado conforme novas decisões
> forem tomadas — é a fonte de verdade de contexto entre sessões.

---

## 1. Visão Geral do Projeto

**Nome:** GeoRural DataHub (também referido como "Geo Data Lake")
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
  ANY TABLE`, `CREATE ANY INDEX/SEQUENCE/TRIGGER/VIEW` — permite criar e manipular objetos
  **qualificados** (`bronze.registro_bruto`) em qualquer schema do PDB.
- Adequado para **dev/acadêmico**; em ambiente real isso seria restrito por schema com
  roles dedicados em vez de privilégios `ANY`. Ver §4.6 para o compose completo.

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
    id                 NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    fonte_dado_id      NUMBER NOT NULL REFERENCES controle.fonte_dado(id),
    coluna_origem      VARCHAR2(100) NOT NULL,    -- ex: 'WKT_GEOM_AREA_EMBARGADA'
    atributo_canonico  VARCHAR2(100) NOT NULL,    -- ex: 'geometria'
    tipo_dado          VARCHAR2(30),              -- ex: 'geometry_wkt', 'date_ddmmyyyy_hhmmss', 'cpf_cnpj'
    obrigatorio        NUMBER(1) DEFAULT 0
);
```

O pipeline Bronze→Silver lê essa tabela em runtime para saber como interpretar/converter
cada coluna. Isola a variação de schema na tabela de mapeamento; a Silver continua com
colunas fixas e tipadas.

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

### 2.5. Silver (esboço conceitual — ainda não é DDL final)
Entidades canônicas de domínio, todas normalizadas via `mapeamento_coluna`:
- `imovel_rural` (numero_car, geometria SDO_GEOMETRY, area_ha, município, proprietário)
- `area_restricao` (tipo: APP/RL/embargo/UC/terra_indígena/quilombola, geometria, órgão
  de origem, fonte_dado_id, data_competência)
- `lista_trabalho_escravo`, `desmatamento_prodes`, `uso_solo_mapbiomas`

**Reprojeção de SRID — [EM ABERTO, decidir na camada Silver]:** fontes chegam em SRIDs
variados; RN013 quer tudo em SIRGAS 2000 (EPSG:4674). Bronze guarda o WKT cru como veio
(não reprojeta). Na Silver, reprojetar no Oracle (`SDO_CS.TRANSFORM(geom, 4674)` — sem
lib Java extra, opção com menos risco) ou em Java (GeoTools `gt-referencing` +
`gt-epsg-hsql` — mais classpath, mesma dúvida de compat com Boot 4). Decidir na hora.

### 2.6. Gold (esboço conceitual — ainda não é DDL final)
- `indicador_versao` (hash_versao imutável, imovel_id, data_processamento, regra_calculo_id)
- `indicador_versao_fonte` (N:N — quais versões de quais fontes entraram no cálculo →
  espinha da linhagem)
- `indicador_resultado` (percentual_sobreposição, tipo_restrição, área_afetada_ha)
- `regra_calculo` (também versionada, para saber qual regra estava vigente)

**Ponto crítico ainda em aberto:** o hash de versão precisa amarrar simultaneamente
versão do dado-fonte + versão da regra de cálculo + resultado, ou a reprodutibilidade
exigida (RNF05 do doc de requisitos / RN016-RN019 do DRN) não se sustenta. Detalhamento
do algoritmo de hashing ainda não foi feito.

### 2.7. Schema de controle (transversal)
- `usuario`, `papel`, `usuario_papel` — RBAC (**adiado**, ver seção 4)
- `fonte_dado` — catálogo de fontes (ver seção 2.8, estendido para agendamento)
- `lote_ingestao` (fonte_dado_id, arquivo_origem, data_upload, usuário, hash_arquivo)
- `pipeline_execucao` — histórico de execuções (substitui o que seria "DAG run" do Airflow)
- `log_auditoria` — **append-only**, sem UPDATE/DELETE possível (RN025 do DRN)

### 2.8. `fonte_dado` — extensão para agendamento automático
Discutido: além do cadastro básico, a fonte de dado pode ter aquisição automatizada
(API ou download por URL) com frequência configurável:

```sql
CREATE TABLE controle.fonte_dado (
    id                  NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nome                VARCHAR2(200) NOT NULL,
    orgao               VARCHAR2(200),
    tipo_aquisicao      VARCHAR2(20) NOT NULL, -- UPLOAD_MANUAL | API_REST | DOWNLOAD_URL
    url_origem          VARCHAR2(1000),        -- nulo se UPLOAD_MANUAL
    cron_expressao      VARCHAR2(100),         -- só relevante se automatizado
    formato_esperado    VARCHAR2(30),          -- CSV | GEOJSON | SHAPEFILE | JSON
    ativo               NUMBER(1) DEFAULT 1,
    ultima_execucao_em  TIMESTAMP,
    proxima_execucao_em TIMESTAMP,
    data_add            TIMESTAMP DEFAULT SYSTIMESTAMP
);
```

**Risco em aberto, ainda não resolvido:** para fontes `DOWNLOAD_URL`, como detectar se o
arquivo baixado mudou desde a última execução (evitar reprocessar/duplicar lote se o
conteúdo é idêntico)? Provável solução: hash do arquivo baixado comparado ao
`hash_arquivo` do último `lote_ingestao` da mesma fonte — mas o desenho exato do fluxo
ainda não foi feito. Isso amarra com RN018 (data de competência).

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
| `frontend/Dockerfile.dev` | imagem dev do frontend: `node:22-slim` + `npm run dev` |
| `infra/oracle-init/01-criar-schemas.sh` | cria os 6 schemas extras + grants `ANY` ao `CONTROLE` (ver §2.1) |
| `.env.example` (raiz, rastreado) | modelo de variáveis — copiar para `.env` (gitignored) |

- **Modo dev, não "empacotado":** backend e frontend rodam a toolchain
  (`./mvnw spring-boot:run`, `npm run dev`) com o **código-fonte montado do host**
  (bind mount). Nada de código dentro da imagem.
- **Dockerfile multi-stage (build imutável) fica para quando hospedagem entrar** (§3.4).
  Barato de escrever depois; opcionalmente o CI compila cedo para pegar quebra.
- `oracle`: imagem `gvenzl/oracle-free:23.26.3-slim` (tag exata pinada — checar tags novas
  periodicamente em hub.docker.com/r/gvenzl/oracle-free/tags), volume nomeado
  `oracle-data` para datafiles, `infra/oracle-init/` montado em
  `/container-entrypoint-initdb.d`, `healthcheck: healthcheck.sh` (nativo da imagem).
  `backend` usa `depends_on: { oracle: { condition: service_healthy } }`.
- `backend`: volume nomeado `maven-repo` → `/root/.m2` (cache de dependência entre subidas).
  `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` vêm direto do `.env` via `environment:` do
  compose — Spring Boot faz o binding automático, **sem precisar de
  `application-docker.properties`** por enquanto.
- `frontend`: `node_modules` em **volume nomeado** `frontend-node-modules` (nunca bind
  mount do host); comando reinstala só se o volume estiver vazio
  (`[ -d node_modules/vite ] || npm install`). Vite com `server.proxy` mandando `/api` →
  `http://backend:8080` (sem CORS em dev, sem `rewrite` — os controllers já nascem com
  `/api/...` no `@RequestMapping`, ex. §5.2).
- Profiles Spring: `dev` (backend na IDE do host → `localhost:1521`) e `docker` (dentro
  do compose → host `oracle`, já setado via `SPRING_PROFILES_ACTIVE` no compose). Os dois
  cenários funcionam sem trocar código.
- **Subir/derrubar:** `docker compose up -d` (tudo) ou `--build` após mudar Dockerfile;
  `docker compose down` (mantém volumes) / `down -v` (apaga também os dados do Oracle —
  reseta os schemas, útil se o init script mudar).

**Cuidados de WSL (toda a equipe está em WSL/Ubuntu):**
- **Repositório vive no filesystem do WSL** (`~/projetos/datagis`), nunca em `/mnt/c/...`
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

### 5.2. Escopo da primeira fatia — **[DECIDIDO]**
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

### 5.7. Catálogo de fontes de dados do desafio — **[NOVO, ver `docs/fontes-de-dados.md`]**
Documento dedicado (não neste arquivo, pelo tamanho) com o levantamento e teste real, em
2026-09-04, de **todos os endpoints/serviços citados na página "Dados necessários, fontes
e formatos" do desafio oficial da Visiona** — CAR, IBGE (malha e biomas), ICMBio/MMA
(UCs), FUNAI (Terras Indígenas), INCRA, IBAMA (embargos), INPE PRODES (desmatamento) e
INPE Queimadas (focos de calor). Cada fonte tem: endpoint testado, resultado real
(contagem, CRS, atributos), e observações de acesso (ex.: FUNAI exige WFS 1.1.0 + User-
Agent de navegador; embargos do IBAMA têm o link publicado quebrado agora; INCRA não tem
WFS estável encontrado). **8 das 10 fontes têm caminho técnico confirmado**; os 2
pendentes (INCRA e APP/RL/vegetação do CAR) não travam a fatia 1.

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

### 5.5. Spike de compatibilidade — **[PARCIALMENTE FEITO]**
½–1 dia, uma pessoa, branch descartável. Objetivo: provar que a stack bleeding-edge
(Boot 4.1.1 / Framework 7 / Jakarta 11 / Hibernate 7.4 / Jackson 3) funciona ANTES de
construir em cima:
1. ✅ `mvn dependency:tree` — feito em 2026-09-04 (§4.4): **sem conflito de versão
   reportado**, todas as libs (hibernate-spatial, GeoTools 35.1, jts-core 1.20.0,
   springdoc 3.0.3, Quartz, Spring Batch) resolveram limpo.
2. ⬜ 1 teste: Testcontainer `gvenzl/oracle-free` + Hibernate salva/lê um `SDO_GEOMETRY`
   (JTS `Polygon`) round-trip.
3. ⬜ 1 teste: GeoTools abre um `.shp` pequeno e lê a primeira feição.
4. ⬜ Subir app, abrir `/swagger-ui.html` (springdoc 3.x).
5. ⬜ Spring Batch: job trivial roda no Boot 4.1.1 (Spring Batch 6 / Framework 7).
6. ⬜ Anotar o que quebrou / versões pinadas. Se GeoTools conflitar → plano B já em §4.4.

**Resolver dependência sem conflito não prova que funciona em runtime** — os passos
2-5 (o que realmente importa) ainda não foram feitos. Não tratar o item 1 como spike
concluído.

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
