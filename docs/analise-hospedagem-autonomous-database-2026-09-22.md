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

---

## Resposta direta

**Sim, é tecnicamente viável** — e é exatamente o candidato que o CLAUDE.md já
apontava como mais provável, só nunca testado na prática. O produto se chama hoje
**"Autonomous AI Database"** (renomeado de "Autonomous Database" — mesmo motor, nome
novo). Oracle Spatial (`SDO_GEOMETRY`) e PL/SQL são suportados sem downgrade. **Duas
coisas não dá pra confirmar sem testar na prática**, e as duas têm plano B já mapeado
neste documento se não funcionarem como o ambiente local espera:
1. Se o modelo de privilégios do Always Free permite recriar exatamente os grants
   "ANY" que sustentam a arquitetura de 1 usuário conectando + Flyway cross-schema
   (§2.1 do CLAUDE.md) — §3.2 abaixo.
2. Se a instância vem em Oracle 19c ou 26ai (depende da home region da tenancy — não
   é escolha manual), porque isso muda se o tipo `JSON` nativo que a Bronze usa hoje
   funciona como está ou precisa de DDL alternativa — §3.1 abaixo.

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

### 3.1. Versão do banco (19c vs 26ai) — depende da região, e muda o tipo `JSON` nativo

**Achado novo, a partir da documentação oficial que a equipe trouxe (2026-09-22),
que corrige uma suposição errada que eu tinha feito neste documento antes.** O Always
Free Autonomous AI Database **não roda sempre a mesma versão**:

- **Oracle Database 19c** — disponível em qualquer região.
- **Oracle AI Database 26ai** — só disponível se a **home region** da tenancy da
  equipe for uma destas 8: Phoenix, Ashburn, Londres, Paris, Sydney, Mumbai, Singapura
  ou Tóquio. Fora dessas regiões, o Always Free só oferece 19c. **A home region é
  fixada no momento em que a conta OCI é criada e não dá pra trocar depois** — vale a
  equipe checar isso antes de criar a conta, se quiser garantir acesso ao 26ai.

**Por que isso importa para o projeto:** eu tinha assumido, sem confirmar, que o Always
Free rodava "a mesma geração de banco" que o ambiente local
(`gvenzl/oracle-free:23.26.3-faststart`, Oracle 23ai) — **isso não está confirmado, e a
comparação certa agora é 19c/26ai, não 23ai**. Isso é relevante porque
`bronze.registro_bruto.payload` é declarado como tipo **`JSON` nativo** na migration
(`V004__criar_registro_bruto.sql`), mapeado no Java via `@JdbcTypeCode(SqlTypes.JSON)` —
o tipo de coluna nativo `JSON` (armazenamento binário OSON) é recurso que só existe a
partir do **Oracle 21c**. O Oracle 19c **não tem** esse tipo nativo (dá pra simular JSON
em 19c com `CLOB CHECK (coluna IS JSON)`, mas é outra sintaxe de DDL, não
`payload JSON NOT NULL` como a migration atual declara).

**Consequência prática:** se a home region da equipe não estiver na lista do 26ai, a
migration `V004` provavelmente **não aplica como está** contra um Always Free 19c — a
equipe precisaria ou (a) confirmar/mudar a home region da tenancy antes de criar a conta
(só é possível decidir uma vez, na criação), ou (b) ter uma migration alternativa para
o schema Bronze usando `CLOB CHECK (... IS JSON)` em vez do tipo `JSON` nativo,
condicional à versão do banco de destino. Isso também depende de confirmar se Oracle
Spatial (`SDO_GEOMETRY`) tem a mesma cobertura em 19c — a fonte usada nesta análise fala
de "Autonomous AI Database" de forma geral, sem detalhar por versão; vale checar
especificamente para 19c antes de assumir.

### 3.2. Modelo de privilégios — o ponto mais importante, e o menos certo

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

**Não encontrei confirmação direta, nas fontes pesquisadas, de que o `ADMIN` do
Always Free consegue conceder exatamente os mesmos privilégios `... ANY ...` que o
`SYS` local concede hoje ao `CONTROLE`.** Isso é uma lacuna real de informação, não uma
certeza de que vai falhar — só não dá pra assumir que vai funcionar sem testar.

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

Se o Always Free não permitir conceder `SELECT ANY SEQUENCE` do jeito que o script
local faz, **nenhuma tabela do projeto fora do schema owner consegue nem ser criada**
pelo caminho atual — é literalmente o primeiro coisa a quebrar, não um detalhe
secundário.

**Plano B, se o modelo "1 usuário com privilégios ANY" não passar no Always Free:** o
CLAUDE.md já tinha essa alternativa desenhada e **conscientemente descartada** para o
ambiente local por complexidade (§2.1.1): "um bean Flyway por schema... ~60 linhas de
config e um conceito a mais... voltar a considerar só se a linha de versão global
incomodar." Migrar pra nuvem no Always Free pode ser exatamente o gatilho que torna essa
alternativa necessária — cada schema (`bronze`, `silver`, `gold`, `quarentena`,
`quartz`, `batch`) ganharia seu próprio usuário/schema-owner com privilégios só sobre
si mesmo, e o Flyway rodaria uma instância por schema, sem precisar de nenhum privilégio
`ANY`. Mais trabalho de configuração, mas evita depender de um modelo de permissão que
o serviço gerenciado pode não expor.

### 3.3. Forma de conexão muda, não só o host

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

**Recomendação de caminho, se a equipe for adiante:** mirar em TLS sem wallet — é o mais
compatível com o padrão já estabelecido (`SPRING_DATASOURCE_URL/USERNAME/PASSWORD` via
`environment:` do compose, sem arquivo de config adicional, §4.6). De qualquer forma, o
`SPRING_DATASOURCE_URL` muda de formato (não é só trocar o host `oracle` por um
hostname da nuvem) — é uma mudança de configuração real, não *drop-in*.

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

### 5.3. Instância já provisionada

A equipe criou a instância Always Free **`datagis`** em 2026-09-22 (Autonomous AI
Database, tier "Always Free"). Nesta análise ela ainda não tinha terminado de
provisionar — os itens de validação prática recomendados na seção 7 (versão do banco,
grants, conexão JDBC via TLS) seguem como próximo passo assim que ela estiver `AVAILABLE`.

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

## 7. Recomendação prática

A equipe já tem o hábito de fazer **spike descartável antes de construir em cima de algo
não validado** (CLAUDE.md §5.5 — foi assim com hibernate-spatial/GeoTools/Spring Batch
no início do projeto). O mesmo raciocínio se aplica aqui, e é pouco custo: criar a
instância Always Free, checar a versão do banco que veio (19c ou 26ai — §3.1), tentar
recriar — só como teste, com 1-2 schemas, não o projeto inteiro — os grants que
`01-criar-schemas.sh` faz hoje (§3.2), e testar uma conexão Spring Boot real via TLS
sem wallet (§3.3). Isso responde as duas perguntas que mais importam antes de qualquer
decisão de "vamos migrar para lá" virar compromisso.

Se os grants `ANY` **não** passarem no Always Free, não é motivo pra descartar o
Always Free como hospedagem — só confirma que o Plano B (§3.2, Flyway por schema) precisa
entrar em ação antes do deploy, não é um bloqueio do serviço em si. Da mesma forma, se
a instância vier em 19c, o tipo `JSON` nativo da Bronze precisa de uma migration
alternativa — também não inviabiliza o Always Free, só muda 1 arquivo de DDL.

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

**Ainda não confirmado, candidato a próxima checagem:** cobertura do Oracle Spatial
(`SDO_GEOMETRY`) especificamente em 19c (as fontes acima falam de "Autonomous AI
Database" de forma geral, sem detalhar por versão de banco) — relevante só se a home
region da equipe cair fora da lista do 26ai (§3.1).

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
