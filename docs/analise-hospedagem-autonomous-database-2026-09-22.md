# Viabilidade do Oracle Always Free (Autonomous AI Database) para o DataGis — 2026-09-22

> Análise de viabilidade técnica para fechar o item **[ADIADO]** do CLAUDE.md §3.4
> ("a instância gratuita provável é OCI — Autonomous Database com Spatial, ou VM ARM
> Ampere rodando o mesmo compose"). Baseada em pesquisa na documentação oficial da
> Oracle e fontes de terceiros atualizadas (2026), cruzada com as decisões de
> arquitetura já tomadas pela equipe (CLAUDE.md §2.1, §2.1.1, §4.6). **Sem alteração de
> código** — só diagnóstico, para embasar a decisão de hospedagem quando a equipe
> quiser fechar esse ponto.
>
> **Nota de atualização de conhecimento:** meu treinamento vai até janeiro/2026; a
> oferta da Oracle mudou nome e limites desde então (inclusive o corte do Ampere A1
> Always Free de 4 para 2 OCPUs em 2026-06-15). As informações abaixo vêm de busca na
> web feita nesta sessão (2026-09-22), com fontes citadas ao final — vale reconferir
> antes de decidir, porque tier gratuito de nuvem é a coisa que a Oracle mais reajusta
> sem aviso (o próprio ajuste do Ampere A1 foi feito "silenciosamente", sem anúncio
> público formal, segundo uma das fontes).
>
> **Atualização 2026-09-22 (mesmo dia, tarde) — deixou de ser só análise, foi testado
> de verdade.** A instância Always Free `datagis` foi criada e o backend conectou nela
> de ponta a ponta via mTLS/Wallet, branch `feat/conexao-oracle` (`LIZARDS-3`): as 36
> migrations do projeto aplicaram e o backend subiu completo (Quartz inicializado, 11
> fontes agendadas). Isso **confirma com teste real**, não mais suposição, as duas
> perguntas em aberto do parágrafo "Resposta direta" abaixo — ver §3.1 e §3.2
> atualizados, e a nova §3.5 com os 3 bugs reais encontrados (nenhum é dos que este
> documento tinha previsto) e como cada um foi corrigido.

---

## Resposta direta

**Sim, é tecnicamente viável — e agora confirmado com teste real, não só suposição.**
O produto se chama hoje **"Autonomous AI Database"** (renomeado de "Autonomous
Database" — mesmo motor, nome novo). Oracle Spatial (`SDO_GEOMETRY`) e PL/SQL são
suportados sem downgrade. As duas perguntas que este documento originalmente não
conseguia responder sem testar já foram testadas de verdade em 2026-09-22
(`feat/conexao-oracle`, `LIZARDS-3`):
1. **Modelo de privilégios**: o `ADMIN` do Always Free, **sem nenhum grant extra**,
   já sustenta a arquitetura de 1 usuário + Flyway cross-schema — as 36 migrations do
   projeto aplicaram limpo, incluindo `IDENTITY` cross-schema e FK cross-schema. Plano
   B (Flyway por schema) **não é necessário** — §3.2 abaixo.
2. **Versão do banco**: a instância `datagis` veio em **Oracle 23.26 (família 23ai)**,
   não 19c nem 26ai como as fontes de documentação sugeriam por região — o tipo `JSON`
   nativo da Bronze funciona exatamente como no ambiente local, sem precisar de DDL
   alternativa — §3.1 abaixo.

Três bugs reais apareceram no teste — nenhum dos dois pontos acima, mas específicos de
como o Autonomous Database se comporta perante certas operações — corrigidos e
documentados na nova §3.5.

---

## 1. O que o Always Free Autonomous AI Database oferece hoje

| Item | Limite (2026) |
|---|---|
| Instâncias Always Free por tenancy | até **2** |
| Storage por instância | ~**20 GB** (Exadata storage) |
| Sessões simultâneas de banco | até **30** |
| Usuários simultâneos (APEX/ORDS/Database Actions) | ~3-6 |
| Tipos de workload disponíveis | Transaction Processing (ATP), Lakehouse, JSON, APEX Service |
| Escalonamento (OCPU/storage) | **fixo** — Always Free não escala manual nem automaticamente |

Para este projeto, o workload certo é **Transaction Processing** (ATP) — é o
propósito geral/relacional (o que a Silver e o Bronze do DataGis são); `Lakehouse` é
orientado a analytics colunar, `JSON` é mais document-DB-cêntrico (mesmo suportando SQL
completo por baixo).

**20 GB deve ser folgado** para o volume atual do projeto (circuito de homologação —
Curitiba/Maringá/Ponta Grossa, ~4.590 imóveis — e as demais fontes recortadas para o
Paraná, na casa de centenas de milhares de registros no Bronze, não milhões). Vale só
monitorar se o circuito crescer ou se uploads de Shapefile nacionais (como o teste de
218 MB / 559 mil registros já feito, CLAUDE.md §9.12) virarem rotina em vez de teste
pontual.

---

## 2. O que funciona sem ressalva

- **Oracle Spatial / `SDO_GEOMETRY`** vem incluso no Autonomous AI Database, inclusive
  no tier Always Free — a Silver do projeto (`MDSYS.SDO_GEOMETRY`, SRID 4674, usada em
  `imovel_rural`, `area_protegida`, `embargo_ambiental`, `desmatamento`, `foco_calor`,
  `municipio` — CLAUDE.md §2.5/§9.11) roda sem precisar de plano pago nem de trocar de
  abordagem espacial.
- **PL/SQL funciona normalmente** — é o mesmo motor Oracle por baixo. Relevante direto
  para a frente de procedures do Silver→Gold discutida em
  `docs/analise-fluxo-etl-2026-09-21.md` (seções 5-8) — nada ali fica inviável rodando
  em Autonomous Database.
- Tabelas de terceiros que o projeto já carrega via migration (`QRTZ_*` do Quartz,
  `BATCH_*` do Spring Batch — ainda não exercitadas de verdade, CLAUDE.md §3.2/§3.3) são
  tabelas relacionais comuns, sem recurso especial — não há motivo técnico pra não
  funcionarem, mas (como no ambiente local) só ficam confirmadas quando alguém
  efetivamente ligar Quartz/Batch e testar.

---

## 3. O que precisa de validação prática antes de comprometer

### 3.1. Versão do banco — **[CONFIRMADO 2026-09-22: Oracle 23.26, família 23ai]**

**Histórico da dúvida:** a documentação oficial da Oracle (pesquisa web) dizia que o
Always Free roda **Oracle Database 19c** em qualquer região, ou **Oracle AI Database
26ai** só nas 8 home regions específicas (Phoenix, Ashburn, Londres, Paris, Sydney,
Mumbai, Singapura, Tóquio). Isso tinha corrigido uma suposição anterior deste documento
de que a nuvem rodaria "a mesma geração" que o ambiente local
(`gvenzl/oracle-free:23.26.3-faststart`, Oracle 23ai).

**Teste real, 2026-09-22 (`feat/conexao-oracle`):** o log de boot do backend contra a
instância `datagis` mostrou:
```
Database: jdbc:oracle:thin:@datagis_high (Oracle 23.26)
...
Database version: 23.26.3
```
**A instância veio em Oracle 23.26 (família 23ai) — exatamente a mesma geração do
ambiente local**, não 19c nem 26ai. Isso **contradiz a pesquisa documental** que
motivou esta seção originalmente (possivelmente porque a oferta mudou entre a
documentação consultada e a data real de provisionamento, ou porque a home region da
tenancy da equipe não é nenhuma das 8 listadas para 26ai e o "padrão fora dessas
regiões" já deixou de ser 19c). **Vale registrar como lição**: para tier gratuito de
nuvem, testar é mais confiável que documentação — a oferta muda rápido demais.

**Consequência prática, positiva:** `bronze.registro_bruto.payload JSON` (tipo nativo,
`@JdbcTypeCode(SqlTypes.JSON)`) **funcionou sem nenhuma alteração** — a migration
`V004` aplicou igual ao ambiente local, confirmado no mesmo teste (36/36 migrations
aplicadas). **A DDL alternativa com `CLOB CHECK (... IS JSON)` discutida abaixo não é
necessária** — mantida só como registro histórico do raciocínio, caso uma home region
diferente da equipe (ou uma mudança futura da oferta Oracle) volte a colocar 19c em
jogo:

> Oracle 19c **não tem** o tipo `JSON` nativo (armazenamento binário OSON, só a partir
> do 21c) — precisaria de `CLOB CHECK (coluna IS JSON)` como DDL alternativa,
> condicional à versão do banco de destino. Como a instância real veio em 23ai, esse
> caminho não precisou ser percorrido.

### 3.2. Modelo de privilégios — **[CONFIRMADO 2026-09-22: funciona sem grant extra nenhum]**

A arquitetura atual (CLAUDE.md §2.1) depende de um único usuário conectando
(`CONTROLE`, criado pela imagem `gvenzl/oracle-free`) com privilégios de sistema
amplos — `CREATE/ALTER/DROP ANY TABLE`, `SELECT/INSERT/UPDATE/DELETE ANY TABLE`,
`CREATE ANY INDEX/SEQUENCE/TRIGGER/VIEW`, `SELECT ANY SEQUENCE` — concedidos por `SYS`
via `infra/oracle-init/01-criar-schemas.sh`, o que permite ao Flyway criar/alterar
objetos **qualificados** (`bronze.registro_bruto`) em qualquer um dos 6 outros schemas
a partir de uma única conexão.

No Autonomous AI Database, o usuário `ADMIN`:
- **Não tem `SYSDBA`.**
- **Não tem `GRANT ANY PRIVILEGE`** (a Oracle removeu esse privilégio do ADMIN por
  design de segurança do serviço gerenciado).
- Em vez disso, expõe `GRANT ANY OBJECT PRIVILEGE`, `GRANT ANY SCHEMA PRIVILEGE` e
  `GRANT ANY ROLE` — que são privilégios "meta" (permitem *conceder* certos privilégios
  a outros), **não necessariamente idênticos** aos privilégios de sistema crus
  (`CREATE ANY TABLE`, `SELECT ANY SEQUENCE`) que o script local concede hoje.

**Teste real, 2026-09-22:** o `ADMIN` da instância `datagis`, **sem nenhum `GRANT`
adicional rodado pela equipe** (usamos as credenciais de criação da instância, sem
tocar em privilégios), aplicou as 36 migrations do projeto de ponta a ponta —
incluindo as duas pegadinhas exatamente descritas abaixo (`IDENTITY` cross-schema e FK
cross-schema). **O `ADMIN` do Always Free já vem, por padrão, com privilégios
suficientes** para sustentar o mesmo modelo de "1 usuário com privilégios amplos" que o
`CONTROLE` local tem via script — não foi preciso descobrir/replicar os `GRANT`s
específicos do §2.1 do CLAUDE.md, o `ADMIN` gerenciado já cobre o equivalente. A lacuna
de informação que motivou esta seção está resolvida: **funciona**, e o Plano B (Flyway
por schema, mais abaixo) não precisa ser implementado.

**Por que isso importa especificamente para este projeto** (não é só burocracia
genérica de permissão): o CLAUDE.md já documentou **duas pegadinhas reais** descobertas
testando localmente, que dependem exatamente desses privilégios "ANY":
1. FK cross-schema (`bronze.registro_bruto` → `controle.lote_ingestao`) não é coberta
   por `CREATE ANY TABLE` — precisa de `GRANT REFERENCES` feito pelo dono da tabela pai
   (§2.1, pegadinha 1).
2. Coluna `GENERATED ALWAYS AS IDENTITY` criada via `CREATE ANY TABLE` falha com
   `ORA-41900` sem `SELECT ANY SEQUENCE` (§2.1, pegadinha 2) — e **toda tabela do
   projeto usa `IDENTITY` como PK** (Bronze, Silver, Gold, Quarentena, Controle — é o
   padrão do projeto inteiro).

**Confirmado que isso não quebrou**: as 36 migrations, incluindo toda tabela
`IDENTITY` fora do schema `CONTROLE` (Bronze, Silver, Gold, Quarentena) e toda FK
cross-schema, aplicaram sem nenhum `ORA-41900`/`ORA-00990` de privilégio.

**Plano B (Flyway por schema) — mantido só como registro histórico, não é mais
necessário.** O CLAUDE.md já tinha essa alternativa desenhada e **conscientemente
descartada** para o ambiente local por complexidade (§2.1.1): "um bean Flyway por
schema... ~60 linhas de config e um conceito a mais... voltar a considerar só se a
linha de versão global incomodar." A preocupação de que migrar pra nuvem forçaria essa
alternativa **não se confirmou** — o modelo atual (1 usuário, privilégios amplos)
funciona igual na nuvem.

### 3.3. Forma de conexão muda, não só o host — **[DECIDIDO 2026-09-22: mTLS com Wallet]**

Hoje, dev local conecta assim (CLAUDE.md §4.6, via `docker-compose.yml`):
```
SPRING_DATASOURCE_URL=jdbc:oracle:thin:@oracle:1521/FREEPDB1
```
Sem TLS, string simples. O Autonomous Database **exige conexão criptografada**, com duas
opções:

| Opção | O que exige | Complexidade |
|---|---|---|
| **mTLS (com wallet)** | Baixar o arquivo de wallet da instância, configurar `TNS_ADMIN` apontando pra ele (ou embutir no classpath/imagem) | Maior — mais um artefato pra gerenciar e manter fora do git (credencial) |
| **TLS (sem wallet)** | Só usuário/senha + string de conexão `tcps://...` (suportado desde JDK 8u162+) | Menor — mais parecido com o padrão atual de "tudo via variável de ambiente" do compose |

**Decisão revista:** esta análise recomendava originalmente TLS sem wallet, por ser mais
simples e parecido com o padrão do compose. **A equipe optou por mTLS com Wallet**,
porque é **pré-requisito explícito da FATEC** para esta entrega — não é uma escolha
técnica livre. Implementado em `feat/conexao-oracle` (`LIZARDS-3`):

- **Dependências novas no `pom.xml`**: `oraclepki`, `osdt_cert`, `osdt_core`
  (`com.oracle.database.security`, versão `21.18.0.0` — é a mais recente que existe
  desses dois últimos no Maven Central; `oraclepki` sozinho já tem linha `23.x`, mas os
  três precisam existir juntos, então fixamos na versão em comum mais nova). O driver
  em si continua sendo o `ojdbc17` já presente — **não** trocado pelo `ojdbc11-production`
  de tutoriais mais antigos, que seria um driver mais velho que o que o projeto já usa.
- **Wallet**: baixada do console OCI (`datagis` → Database Connection → Download
  Wallet) e extraída em `wallet/` na raiz do repo — **gitignored** (chave privada +
  keystores, é credencial real, mesmo princípio do §5.2). Caminho relativo `./wallet`
  funciona rodando o Maven a partir da raiz do projeto.
- **Novo perfil Spring `cloud`** (`application-cloud.properties`), separado dos perfis
  `dev`/`docker` existentes — não substitui nenhum dos dois, é só mais uma opção de
  datasource. Senha/nome do TNS entram via variáveis de ambiente
  (`CLOUD_DB_PASSWORD`/`CLOUD_TNS_NAME`), seguindo `.env.cloud.example` (novo, rastreado)
  → copiado para `.env.cloud` (gitignored) com os valores reais.
- **Atenção que o tutorial de referência não cobria**: `spring.jpa.hibernate.ddl-auto`
  continua `none` (herdado do `application.properties` base) — **não** seguir a sugestão
  comum de tutorial de usar `update` aqui, porque quebraria a premissa do projeto de que
  o Flyway é a única fonte de verdade do schema (CLAUDE.md §2.1.1).
- **Achado que nenhum tutorial cobre, específico deste projeto**: localmente o backend
  conecta *como* o dono das tabelas (usuário `CONTROLE`), então toda referência SQL sem
  prefixo de schema — tanto o JPA/Hibernate quanto as várias queries JDBC cruas do
  projeto (`PersistidorSilverService`, `OrquestradorValidacao`, etc.) — resolve certo
  por conta disso. Na nuvem conectamos como `ADMIN` (dono da conexão ≠ dono das
  tabelas, que continuam em `CONTROLE`), e **qualquer SQL sem prefixo quebra com
  `ORA-00942` mesmo a tabela existindo** — foi o que aconteceu no boot, no primeiro
  `SELECT` que o `AgendadorInicializacaoRunner` faz em `fonte_dado`. Corrigido fixando
  o schema padrão da sessão na própria conexão, sem tocar em nenhuma SQL do projeto:
  ```properties
  spring.datasource.hikari.connection-init-sql=ALTER SESSION SET CURRENT_SCHEMA=CONTROLE
  ```
  Já está em `application-cloud.properties`.

**Como rodar o backend contra a nuvem (perfil `cloud`), testado e funcionando:**

```powershell
# Pré-requisitos: wallet extraída em wallet/ (raiz do projeto, gitignored) e
# .env.cloud preenchido a partir de .env.cloud.example (também gitignored).
$env:JAVA_HOME = "<caminho do seu JDK 21>"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
Get-Content .env.cloud | ForEach-Object {
    if ($_ -match '^\s*([^#=]+)=(.*)$') { Set-Item "Env:$($matches[1].Trim())" $matches[2].Trim() }
}
$env:SPRING_PROFILES_ACTIVE = "cloud"
.\mvnw.cmd spring-boot:run
```

Isso **não mexe em nada do fluxo padrão** — `docker compose up` continua com o perfil
`docker` e o Oracle local, sem alteração (`docker-compose.yml` fixa
`SPRING_PROFILES_ACTIVE: docker` explicitamente). O perfil `cloud` só entra em ação
quando alguém roda esse comando manualmente, fora do container.

### 3.4. Coisas a monitorar, não bloqueantes agora

- **Storage (20 GB)**: folgado hoje, mas sem elasticidade — Always Free não escala. Se o
  circuito de homologação crescer muito além do atual, ou se uploads grandes (Shapefile
  nacional) virarem rotina, vale reavaliar.
- **30 sessões simultâneas**: mais que suficiente pra uma equipe pequena + Spring Boot
  com pool de conexão padrão — não deve ser um limite prático neste estágio do projeto.
- **Rate limit HTTP (429) de 3-6 usuários simultâneos**: só se aplica à **interface
  HTTP** (APEX, Oracle REST Data Services, Database Actions) — **não afeta conexão
  JDBC Thin via `SQL*Net`**, que é como o Spring Boot do projeto conecta hoje
  (`SPRING_DATASOURCE_URL`) e continuaria conectando. Não é uma preocupação real para
  este projeto, só vale não confundir os dois tipos de acesso.

### 3.5. Bugs reais encontrados no teste (2026-09-22) e como foram corrigidos

Nenhum destes 3 estava previsto nas seções acima — apareceram só rodando as 36
migrations de verdade contra a `datagis`. Todos específicos do Autonomous Database
(nenhum acontece no Oracle local); corrigidos em `feat/conexao-oracle` (`LIZARDS-3`),
código e migrations já commitados.

| # | Erro | Onde | Causa | Correção |
|---|---|---|---|---|
| 1 | `ORA-41900: missing INSERT privilege on MDSYS.SDO_GEOM_METADATA_TABLE` | `V013__silver_registrar_metadata_espacial.sql`, `V020__gold_registrar_metadata_espacial.sql` | `INSERT` direto em `MDSYS.SDO_GEOM_METADATA_TABLE` (funciona no Oracle local via `INSERT ANY TABLE`) nunca foi o caminho suportado pela Oracle — a própria documentação diz que é uma tabela global que usuário nunca deveria atualizar direto; o Autonomous Database é mais rígido sobre isso que o Oracle local. | Trocado por `SDO_UTIL.INSERT_SDO_GEOM_METADATA('SILVER', 'MUNICIPIO', 'GEOMETRIA', SDO_DIM_ARRAY(...), 4674)` — procedure oficial da Oracle pra registrar metadata espacial de tabela de outro schema, exige só `SELECT` na tabela alvo. Funciona igual no Oracle local. |
| 2 | `ORA-12801`/`ORA-12860: deadlock detectado ao aguardar um bloqueio de linha` | `V021__seed_demo_silver.sql` (seed de demonstração da Silver) | Autonomous Database aplica paralelismo automático (**Auto DOP**) mesmo em tabelas minúsculas — um `INSERT ... SELECT` lendo `area_protegida` logo depois de a mesma transação ter inserido linhas ali (commit só no fim do script) fazia os processos paralelos colidirem entre si. | `ALTER SESSION DISABLE PARALLEL QUERY;` + `ALTER SESSION DISABLE PARALLEL DML;` no topo do script — sem necessidade nenhuma de paralelismo pra volumes de poucas linhas (seed demo). |
| 3 | `ORA-00942: tabela ou view "ADMIN"."FONTE_DADO" não existe` | Boot do backend, primeira query do `AgendadorInicializacaoRunner` | Conectar como `ADMIN` (dono da conexão) em vez de `CONTROLE` (dono das tabelas) quebra toda referência SQL sem prefixo de schema — Oracle resolve pelo schema da sessão, não de quem criou o objeto. | `spring.datasource.hikari.connection-init-sql=ALTER SESSION SET CURRENT_SCHEMA=CONTROLE` em `application-cloud.properties` — resolve pra JPA e pra toda query JDBC crua do projeto, sem prefixar nada em código. |

**Nota lateral, sem correção de código porque não é bug do projeto**: o DBeaver deu
`ORA-12506: TNS: o listener rejeitou a conexão com base na filtragem da ACL de serviço`
tentando conectar com a mesma wallet que o Maven usou com sucesso segundos antes —
aparenta ser incompatibilidade do driver JDBC bundled do DBeaver com o handshake mTLS
mais estrito do Autonomous Database, não um problema de conta/rede. Contornado usando
o próprio classpath do projeto (JDBC via Maven) para tarefas administrativas pontuais,
em vez de insistir no DBeaver.

**O que isso significa pra decisão de ir pra nuvem**: nenhum dos 3 bugs é
bloqueante — todos têm correção pequena e já aplicada. O padrão comum aos 3 é
"Autonomous Database é mais estrito/diferente em comportamentos que o Oracle local
tolera ou nem exercita" — vale a equipe ter isso em mente se outras migrations novas
(ex.: as procedures PL/SQL do Silver→Gold, `docs/analise-fluxo-etl-2026-09-21.md`
§5-8) também mexerem com `SDO_GEOM_METADATA_TABLE` ou rodarem `INSERT...SELECT` em
tabela recém-escrita — os mesmos 2 primeiros padrões podem se repetir.

---

## 4. Riscos operacionais do tier gratuito — específicos do ritmo de um projeto de semestre

Estes pontos vêm direto da documentação oficial (trazida pela equipe) e **não têm
correspondência com nada que já existe no ambiente local** — são particularidades do
serviço gerenciado que precisam entrar no planejamento se o Always Free for adiante:

- **Sem backup nem restore.** Always Free **não** suporta backup de longo prazo, backup
  manual, nem restaurar para um ponto no tempo anterior. Se algo corromper dado ou
  alguém rodar um `DELETE`/migration errada em produção, **não tem como voltar atrás
  via Oracle** — o único "backup" possível é o que a própria equipe organizar por fora
  (ex.: `expdp`/export lógico periódico, ou simplesmente reconstruir a partir da Bronze,
  já que ela é imutável por design — §2.2 do CLAUDE.md). Vale a equipe decidir uma
  rotina mínima de exportação antes de considerar essa instância "a fonte de verdade"
  de dado que não pode ser perdido (ex.: perto da apresentação final).
- **Parada automática por inatividade.** Uma instância Always Free sem nenhuma conexão
  SQL*Net/HTTPS bem-sucedida por **7 dias corridos** é **parada automaticamente**
  (dado preservado, mas o banco fica offline até alguém religar manualmente pelo
  console OCI — leva ~5 minutos até APEX/ORDS voltarem a responder depois de religar).
  **Isso é diretamente relevante pro ritmo de um projeto acadêmico**: entre sprints, fim
  de bimestre, época de prova, ou só uma semana sem ninguém mexer no banco de nuvem —
  é fácil bater os 7 dias. Não é destrutivo (só precisa religar), mas pode pegar a
  equipe de surpresa numa demonstração se ninguém tiver ligado o banco havia mais de uma
  semana.
- **Exclusão permanente após 90 dias parada.** Se a instância ficar parada (pelo motivo
  acima, ou parada manualmente) por **90 dias corridos acumulados**, ela pode ser
  **apagada permanentemente** — sem chance de recuperação, já que não há backup (ponto
  anterior). Para um projeto de semestre com possível hiato entre entregas (ex.: fim de
  um semestre e início do próximo), isso é um risco real de perder o ambiente de nuvem
  inteiro se ninguém entrar por 3 meses — a Oracle avisa por banner no console e
  e-mail antes de apagar, mas exige que alguém esteja de fato olhando.
- **Sem VCN / sem endpoint privado.** Always Free só oferece endpoint público (protegido
  por ACL/credencial, não por isolamento de rede). Não deve ser um problema para o
  escopo acadêmico atual (já é assim, na prática, com o Oracle local exposto na porta
  1521 do host), mas é uma diferença de postura de segurança a mencionar se o assunto
  vier à tona numa banca/avaliação.

## 5. Bônus: o tier Always Free já resolve a pergunta de topologia em aberto do CLAUDE.md

O CLAUDE.md §3.4 deixou em aberto: *"A escolha de topologia (VM única com compose vs.
ADB gerenciado + VM só do app) fica para quando deploy virar assunto."* A documentação
oficial mostra que **isso nem precisa ser uma escolha exclusiva**: o Always Free
inclui, ao mesmo tempo e sem custo, **2 Autonomous AI Databases *e* 2 Compute VMs**
(além de Block/Object/Archive Storage, Load Balancer e Monitoring). Ou seja, dá pra
combinar — 1 Autonomous AI Database (schemas do banco) + 1 VM Compute (rodando
`backend` + `frontend` via o mesmo `docker-compose.yml` já existente, só apontando o
datasource pra nuvem em vez de pro container `oracle` local) — **tudo dentro do Always
Free, sem misturar com plano pago**. Fecha a pergunta de topologia sem precisar de
decisão adicional de custo.

### 5.1. Dimensionamento real das 2 VMs Compute inclusas

O bônus do item acima não é uma VM só — são **duas ofertas diferentes** dentro do
mesmo "Always Free", com tamanhos bem diferentes, e a equipe precisa escolher a certa
para rodar `backend` + `frontend` via `docker-compose.yml`:

| Oferta | Specs (por instância) | Serve para este projeto? |
|---|---|---|
| **2× AMD Micro** (`VM.Standard.E2.1.Micro`) | 1/8 OCPU, **1 GB RAM** cada, sempre incluídas | **Não, sozinhas.** 1 GB de RAM não é suficiente para `oracle`+`backend`+`frontend` via compose — e nem precisaria ser, já que o banco sai da VM e vai para o Autonomous AI Database (§1). Ainda assim, 1 GB é apertado para `backend` (JVM Spring Boot) + `frontend` (Node/Vite) juntos num único container host, mesmo sem o Oracle local. |
| **Ampere A1 Flex** (ARM) | Pool compartilhado de OCPU/RAM, **provisionável como 1 VM só** (não precisa ser 4 VMs pequenas) | **Sim, esta é a opção viável.** Pool **reduzido de 4 OCPU/24 GB para 2 OCPU/12 GB em 2026-06-15** (corte silencioso da Oracle, sem anúncio formal — ver fonte InfoQ já citada em §1). Mesmo com o corte, **2 OCPU / 12 GB numa VM ARM única** é folgado para `backend`+`frontend` em modo dev (bind mount, sem Oracle local pra competir por RAM). |

**Consequência prática, ainda não registrada em nenhum outro lugar do projeto:** se a
equipe for pelo caminho "ADB gerenciado + VM só do app" (§5, parágrafo acima), a VM
certa é a **Ampere A1 Flex, provisionada como 1 instância só com o pool inteiro** (2
OCPU/12 GB) — as 2 AMD Micro ficam de fora do plano principal (podem servir para algo
auxiliar/pequeno, mas não para rodar a stack). Ponto de atenção arquitetural: a imagem
ARM64 do `node:22-slim`/`eclipse-temurin:21-jdk-jammy` usadas hoje em
`Dockerfile.dev`/`datagis-frontend/Dockerfile.dev` (CLAUDE.md §4.6) **precisam existir
em build multi-arch** — ambas já publicam manifest multi-arch (amd64+arm64) nas tags
usadas pelo projeto, então não é esperado bloqueio, mas vale confirmar com um
`docker compose up` de teste na própria VM Ampere antes de contar com isso como certo.

### 5.2. Credenciais de nuvem — não repetir a decisão do `.env` local

O CLAUDE.md (§4.6) registra uma decisão **deliberada e específica ao ambiente local**:
commitar o `.env` com as senhas do Oracle, porque são credenciais só do banco de
desenvolvimento no `docker compose` da máquina de cada dev, e o próprio texto já
antecipa isso: *"quando a hospedagem em nuvem entrar, as credenciais de lá são outras e
não vêm daqui"*. Vale deixar explícito, agora que a instância de nuvem já existe
(§5.3 abaixo), **o que muda na prática**:

- **Senha do `ADMIN`** do Autonomous AI Database, **wallet** (se a equipe optar por
  mTLS em vez de TLS sem wallet — §3.3) e qualquer chave SSH da VM Ampere **não devem
  ir para o `.env` versionado** — são credenciais de um recurso real, acessível pela
  internet, não de um container Oracle isolado na máquina de cada um.
- Caminho mais simples, coerente com o padrão do projeto de "tudo via variável de
  ambiente do compose" (§4.6): um `.env` **separado e não rastreado** (ex.:
  `.env.cloud`, já coberto por uma entrada nova no `.gitignore`) só para quando alguém
  for de fato apontar o `docker-compose.yml` pro ambiente de nuvem — nunca reaproveitar
  o `.env` commitado hoje.
- Nada disso exige um cofre de segredos (Vault, OCI Secrets) para o estágio atual do
  projeto — está fora de escopo para um projeto acadêmico de um semestre — mas vale a
  equipe combinar isso antes que a primeira pessoa cole a senha do `ADMIN` num commit
  por reflexo do hábito já estabelecido com o `.env` local.

### 5.3. Instância já provisionada e testada de ponta a ponta

A equipe criou a instância Always Free **`datagis`** em 2026-09-22 (Autonomous AI
Database, tier "Always Free"). **Já está `AVAILABLE` e validada**: as 36 migrations do
projeto aplicadas com sucesso e o backend completo rodando contra ela (§3.1, §3.2,
§3.3, §3.5) — os itens de validação prática da seção 7 foram todos respondidos com
teste real, não seguem mais como pendência.

### 5.4. Publicar a aplicação (`backend`+`frontend`) numa VM Compute — caminho conceitual

**Ainda não implementado nem provisionado — é o desenho de como fazer, não uma VM já
criada** (diferente da §5.3, que já é real). Registrado aqui porque a dúvida surgiu em
2026-09-22 na mesma sessão da conexão com a `datagis`, e o caminho técnico depende
diretamente do que já foi validado nas seções 3.1-3.5.

**Topologia recomendada**: 1 VM Ampere A1 Flex (§5.1) rodando só `backend`+`frontend`
via o mesmo `docker-compose.yml` já existente, apontando o datasource pra `datagis` em
vez de rodar um Oracle dentro da VM — evita o container `oracle` (que sozinho já usa
~2 GB) competir pelos 12 GB do pool com a aplicação, e reaproveita 100% do trabalho de
conexão cloud já feito e testado (§3.3, `feat/conexao-oracle`).

**Passos, em ordem:**

1. **Provisionar a VM** — Ampere A1 Flex, 2 OCPU / 12 GB (o pool inteiro pós-corte de
   2026-06-15, §5.1), imagem Ubuntu ou Oracle Linux (as duas têm build ARM64), IP
   público (incluso no Always Free).
2. **Rede — dois níveis de firewall, os dois precisam ser abertos**:
   - **Security List/NSG da VCN** (console OCI): liberar as portas que forem expostas
     (`8080`/`5173` em modo dev, ou `80`/`443` se um proxy entrar na frente depois).
   - **Firewall do próprio SO** (`firewalld` no Oracle Linux — vem ativo por padrão;
     `iptables`/`ufw` no Ubuntu — as imagens OCI de Ubuntu costumam vir com regras
     `iptables` restritivas pré-configuradas). **Esquecer este segundo nível é o erro
     mais comum** — abrir só a Security List no console e continuar sem conectar.
3. **Instalar Docker Engine + Compose plugin** na VM (pacotes ARM64 oficiais, mesmo
   processo de instalação em Ubuntu/Oracle Linux normal).
4. **Clonar o repositório** na VM (`git clone`, com chave SSH ou token configurado —
   repositório é privado).
5. **Levar a wallet e o `.env.cloud`** pra VM **por fora do git** (`scp`), mesmo
   princípio do §5.2 — nunca commitar, nunca reaproveitar o `.env` local.
6. **Subir com o perfil `cloud`** — o mesmo mecanismo de `docker-compose.override.yml`
   sobrescrevendo `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` e `SPRING_PROFILES_ACTIVE`
   já desenhado (e testado localmente) pra rodar o backend em container contra a
   `datagis`, só que agora a VM É o host, não o notebook de alguém do time.

**Duas decisões em aberto, para a equipe avaliar antes de ir adiante:**

- **Modo dev vs. build de produção.** O `docker-compose.yml` atual é modo dev de
  propósito (`./mvnw spring-boot:run` direto do código montado, servidor dev do Vite —
  CLAUDE.md §4.6 já registra "Dockerfile multi-stage fica para quando hospedagem
  entrar" como pendência consciente). Rodar assim numa VM funciona, mas recompila do
  fonte e usa mais RAM que um jar empacotado + build estático servido por algo como
  nginx. Para uma demonstração/homologação da disciplina, modo dev provavelmente já
  resolve; virar algo "no ar" de forma mais permanente pediria o Dockerfile de
  produção que já está mapeado como trabalho futuro.
- **Segurança.** `SecurityConfig` libera tudo (`permitAll()`, CLAUDE.md §5.3) — num
  `localhost` isso é inofensivo, numa VM com IP público de verdade já é outra
  exposição. Mitigação simples sem implementar RBAC: restringir a Security List da VCN
  só aos IPs do time/professor em vez de liberar geral para a internet.

**Imagens ARM64**: `eclipse-temurin:21-jdk-jammy` e `node:22-slim` (usadas em
`Dockerfile.dev`/`datagis-frontend/Dockerfile.dev`) já publicam manifest multi-arch
(amd64+arm64) — não é esperado bloqueio, mas só fica confirmado com um
`docker compose up` de teste na própria VM Ampere.

### 5.5. Achado real ao tentar provisionar (2026-09-22): `VM.Standard.A1.Flex` sem capacidade

Ao seguir o passo a passo da §5.4 e clicar em **Create**, a equipe recebeu:

```
API Error
Out of capacity for shape VM.Standard.A1.Flex in availability domain AD-1.
Create the instance in a different availability domain or try again later.
```

**Não é erro de configuração — é falta de capacidade física da Oracle para esse shape
naquele Availability Domain naquele momento.** É o problema mais reportado sobre o
Always Free: o Ampere A1 Flex é o recurso mais disputado do tier gratuito (único ARM
decente de graça), então a região fica sem estoque livre com frequência, principalmente
em regiões menores como São Paulo (que tipicamente só tem 1 Availability Domain,
eliminando a opção de simplesmente trocar de AD).

**Mitigações, nenhuma delas é uma correção de configuração, são só formas de esperar a
capacidade liberar:**
- **Tentar de novo repetidas vezes** — clicar em Create de novo sem refazer o
  formulário; capacidade libera e reaperta o tempo todo, às vezes só a 5ª-10ª
  tentativa. Não incomum levar um dia ou dois de tentativas numa região menor.
- **Trocar de Availability Domain**, se a região tiver mais de uma (verificar na etapa
  "Placement" do formulário) — não se aplica a regiões com um único AD.
- **Horários de menor disputa** — de madrugada/manhã cedo no horário local da região
  costuma ter mais chance.
- **Não vale a pena** trocar pra `VM.Standard.E2.1.Micro` (AMD) como atalho — 1 GB de
  RAM não serve pra rodar `backend`+`frontend` (§5.1), só trocaria um bloqueio por
  outro.

**Status no momento deste registro: pendente** — a equipe segue tentando provisionar a
VM; a instância `datagis` (banco, §5.3) não é afetada por isso, é um problema
isolado à criação da VM Compute.

---

## 6. Custo e conta de trial (Universal Credits) — Always Free não consome o saldo

A equipe mostrou a tela de faturamento da conta de estudante (Oracle Cloud, "Universal
Credits", saldo de **R$ 1.571,00**) antes de criar a instância — vale registrar por
escrito a resposta, porque é o tipo de dúvida que gera insegurança de "vou gastar o
crédito da faculdade sem querer" bem na hora de decidir hospedar:

- **Recursos marcados como "Always Free" não descontam do saldo de Universal
  Credits.** É a natureza do programa: a tag "Always Free" existe justamente para
  isolar esses recursos da cobrança, inclusive depois que o período de trial (normalmente
  30 dias / US$ 300 em créditos, o que a tela de "Universal Credits" reflete) expirar ou
  a conta virar "Pay As You Go". Enquanto a instância `datagis` (§5.3) permanecer dentro
  dos limites do Always Free (workload/OCPU/storage descritos em §1), ela **não gera
  cobrança e não depende do saldo de trial estar positivo.**
- **O risco de custo real só aparece se a equipe provisionar algo *fora* do Always
  Free** — ex.: uma 3ª Autonomous Database, uma VM maior que o pool Ampere gratuito, ou
  aumentar manualmente o OCPU/storage de um recurso que nasceu "Always Free" (o que tira
  ele da categoria gratuita). Nada disso é necessário para o escopo atual do projeto.
- **O que de fato acontece quando o trial acaba** (ainda vale confirmar com a Oracle se
  a conta específica da equipe é do tipo "trial" formal, ou só uma conta paga com saldo
  de Universal Credits pré-carregado — a tela sozinha não distingue os dois com certeza):
  recursos **pagos** provisionados durante o trial podem ser reclamados/desligados se a
  conta não for convertida para pagamento ativo; recursos **Always Free** continuam
  existindo e funcionando normalmente, sujeitos só aos próprios limites operacionais do
  tier gratuito (parada por inatividade, exclusão por 90 dias — seção 4), não ao fim do
  trial.
- **Recomendação prática**: não é preciso "economizar" o saldo de R$ 1.571,00 para este
  projeto — ele fica disponível para outra finalidade (ou simplesmente não é usado). O
  cuidado real não é de custo, é operacional: não deixar a instância `datagis` passar 7
  dias sem conexão (§4).

---

## 7. Recomendação prática — **[SPIKE CONCLUÍDO, 2026-09-22]**

A equipe já tem o hábito de fazer **spike descartável antes de construir em cima de algo
não validado** (CLAUDE.md §5.5 — foi assim com hibernate-spatial/GeoTools/Spring Batch
no início do projeto). O mesmo raciocínio se aplicou aqui — spike feito, resultado
positivo:

- ✅ Versão do banco confirmada (23.26/23ai — §3.1).
- ✅ Grants `ANY` do `01-criar-schemas.sh` local replicados de fábrica pelo `ADMIN` do
  Always Free, sem trabalho extra (§3.2).
- ✅ Conexão Spring Boot real via mTLS com Wallet, funcionando (§3.3).
- ✅ 3 bugs específicos do Autonomous Database encontrados e corrigidos (§3.5).

**Recomendação atualizada: seguir com o Always Free como hospedagem de banco é viável
e já está com o caminho técnico provado**, não é mais uma hipótese a validar. Os
próximos passos práticos, se a equipe for adiante com deploy de verdade:

1. Decidir a topologia final (§5) — VM Ampere A1 pra `backend`+`frontend`, ou manter
   local/CI e só o banco na nuvem por enquanto.
2. Resolver credenciais de nuvem sem repetir o padrão do `.env` local commitado (§5.2)
   — já esboçado (`.env.cloud` gitignored), falta só a equipe usar na prática em CI/CD
   se isso vier a existir.
3. Abrir PR de `feat/conexao-oracle` pra `sprint-1` quando o time achar adequado —
   inclui o aviso de que `V013`/`V020`/`V021` mudaram de checksum (§3.5), então quem
   pegar a branch precisa resetar o Oracle local (`docker compose down -v`) ou rodar
   `flyway repair`.

**Item de processo, não técnico**: se a equipe seguir adiante com Always Free, vale
combinar quem "cutuca" a instância periodicamente (mesmo só um `SELECT 1` via cron/
lembrete pessoal) pra nunca deixar passar os 7 dias de inatividade sem querer, e
organizar algum export lógico ocasional antes de datas importantes (entrega, banca),
já que não existe rede de segurança de backup gerenciado pelo serviço.

---

## Fontes consultadas

**Documentação oficial (primária, colada pela equipe em 2026-09-22 — página "Always
Free Autonomous AI Database" da Oracle, seção "Autonomous Database" da doc pública):**
é a fonte das seções 3.1 e 4 (versão do banco por região, ausência de backup/restore,
parada por inatividade/exclusão, ausência de VCN, VM grátis incluída) — mais confiável
que os resumos de busca abaixo, usada para corrigir a suposição inicial sobre a versão
do banco.

**Pesquisa web complementar (2026-09-22):**
- [Try Oracle Autonomous AI Database for Free](https://www.oracle.com/autonomous-database/free-trial/)
- [Always Free Autonomous AI Database (documentação oficial)](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/autonomous-always-free.html)
- [Always Free Resources — OCI](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)
- [Oracle Quietly Halves Free Tier Ampere A1 Compute Limits — InfoQ](https://www.infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)
- [Use Oracle Spatial with Autonomous AI Database](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/spatial-autonomous-database.html)
- [ADMIN User Roles and Privileges](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/autonomous-admin-user-roles.html)
- [Manage User Roles and Privileges on Autonomous AI Database](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/manage-users-privileges.html)
- [JDBC Thin Connections with a Wallet (mTLS)](https://docs.oracle.com/en/cloud/paas/autonomous-database/serverless/adbsb/connect-jdbc-thin-wallet.html)
- [JDBC Thin Connections Without a Wallet (TLS)](https://docs.oracle.com/en-us/iaas/autonomous-database-serverless/doc/connect-jdbc-thin-tls.html)

**Superado pelo teste real (§3.1):** a checagem de cobertura do Oracle Spatial em 19c
deixou de ser relevante — a instância `datagis` veio em 23.26/23ai, não 19c, e Spatial
funcionou sem ressalva (§2, confirmado na prática).

---

## Referências cruzadas neste repositório

- CLAUDE.md §2.1 — schemas, usuários e privilégios `ANY` do ambiente local (o que
  precisa ser replicado ou substituído em nuvem).
- CLAUDE.md §2.1.1 — estratégia de Flyway (1 instância global vs. 1 bean por schema —
  a alternativa descartada que pode virar necessária, §3.2 acima).
- CLAUDE.md §3.4 — decisão original de adiar hospedagem; este documento é o primeiro
  passo concreto para fechá-la.
- CLAUDE.md §4.6 — configuração de datasource via `environment:` no
  `docker-compose.yml` (o padrão que a string de conexão TLS precisaria seguir).
- `docs/analise-fluxo-etl-2026-09-21.md` §5-8 — desenho de procedures PL/SQL para
  Silver→Gold, que roda sem alteração em Autonomous AI Database (§2 acima).
- Branch `feat/conexao-oracle` (`LIZARDS-3`) — implementação real da conexão mTLS/Wallet
  (`application-cloud.properties`, `.env.cloud.example`, dependências de wallet no
  `pom.xml`) e as correções de `V013`/`V020`/`V021` descritas em §3.5.
