# Análise do Fluxo de ETL do DataGis — 2026-09-21

> Análise técnica do estado real do backend (`datagis-backend`, branch `sprint-1`) e do
> frontend (`datagis-frontend`) a partir da leitura do código-fonte, migrations Flyway e
> componentes Vue — não apenas do `CLAUDE.md` (que está desatualizado em alguns pontos:
> última atualização registrada em 2026-09-14, mas o repositório já avançou com o
> trabalho de `TipoIndicador`/Gold (`LIZARDS-6`) e `Conjunto` (`LIZARDS-8`/`LIZARDS-9`,
> PRs #12 e #13, mesclados em 2026-09-21). É, na origem, um documento de diagnóstico —
> a seção 5 foi adicionada depois, a pedido da equipe, para discutir viabilidade técnica
> de uma solução específica (procedures PL/SQL para o Silver→Gold); mesmo ali, é um
> esboço de desenho para discussão, não uma implementação.
>
> **Atualização de 2026-09-22:** duas frentes citadas neste documento como "em aberto"
> avançaram desde a redação original, via PRs mesclados em `sprint-1` em 2026-09-21:
> **PR #15 (`LIZARDS-10`, `feat/servico-de-ingestao`)** liga de fato o Quartz Scheduler
> ao `cron_expressao` de `fonte_dado` — o campo deixou de ser "gravado mas sem efeito"
> (ver seções 2 e 3 abaixo, revisadas). **PR #16 (`LIZARDS-6`, `feat/gold-imoveis`)**
> adiciona só um endpoint de leitura (`GET /api/gold/imoveis`,
> `GET /api/gold/imoveis/{codImovel}`) sobre a tabela `gold.imovel_publicado` — **não**
> altera a conclusão da seção 1: continua não existindo nenhum job/service que calcule
> indicador real a partir da Silver, então o que esse endpoint novo expõe hoje ainda é
> só o seed sintético do `V023`.

**Escopo da análise:** leitura de código e documentação, mais discussão de viabilidade
técnica registrada por escrito. Nenhuma alteração de código foi feita no projeto.

---

## Resumo executivo

| Pergunta | Resposta curta |
|---|---|
| 1. Existe envio dos dados da Silver para a Gold? | **Não.** Só existe DDL da Gold + um catálogo de tipos de indicador (`TipoIndicador`, CRUD completo) e uma carga de dados **falsos**, via migration de seed, marcada explicitamente como provisória. Não há nenhum serviço/job que leia a Silver, aplique uma `regra_calculo` e grave `indicador_versao`/`indicador_resultado` de verdade. |
| 2. A configuração do ETL funciona na tela de dados? | **Sim.** Cadastro de fonte e cadastro de regras de mapeamento/validação (de-para Bronze→Silver) são telas reais, com CRUD completo e testado ponta a ponta contra o backend. O campo de cron/agendamento automático, que até 2026-09-21 era editável mas sem efeito, **agora dispara de verdade via Quartz** (`LIZARDS-10`, PR #15) — ver seção 2. |
| 3. O sistema está compatível com o fluxo "normal" de ETL? | **Parcialmente.** Extract e o primeiro Transform+Load (Bronze→Silver, com validação e quarentena) são sólidos, testados contra Oracle real e end-to-end, e agora também **agendáveis automaticamente** (Quartz, desde 2026-09-21). Mas falta a etapa final que dá sentido de negócio ao projeto (Silver→Gold / cálculo de indicador), a orquestração *reprocessável* segue ausente (Spring Batch está no `pom.xml` mas não faz nada — Quartz só resolve "quando", não "como rodar com chunk/retry/restart"), não existe `pipeline_execucao` (apesar de estar "decidida" no CLAUDE.md), e a regra "tudo ou nada" por lote foge do padrão mais comum de ETL incremental (registro ruim isolado, resto segue) — é uma escolha deliberada de auditabilidade, não um bug, mas vale registrar como desvio consciente do "fluxo normal". |
| 4. Bronze / Silver / Gold / Quarentena existem corretamente? | **Sim, estruturalmente.** Os 4 schemas Oracle existem, com Flyway aplicado, e o desenho de dados é coerente com o padrão medalhão (bronze schema-on-read, silver por fenômeno canônico, quarentena espelho frouxo com motivo de erro, gold modelada para indicadores+mapa+detalhe). A ressalva é a mesma do item 1: a Gold estrutural existe, mas está **vazia de dado real** — só tem seed de demonstração. |

Em uma frase: **o pipeline está completo e funcional até a Silver (com validação,
quarentena e auditoria de verdade), mas para no meio do caminho — a etapa que
efetivamente entrega o produto do projeto (indicadores socioambientais calculados na
Gold, consumíveis por mapa/portal) ainda não foi construída.**

---

## 1. Existe envio dos dados da camada Silver para a Gold?

### O que existe

- **DDL completa da Gold** (`db/migration/gold/V016`, `V018`, `V019`, `V020`):
  - `tipo_indicador` — catálogo dos 7 indicadores do desafio (ICV, IRL, IAPP, ISAP, IAE,
    IDESMAT, IFC), com flag `tem_fonte` (4 têm fonte de dado hoje, 3 não — ver
    `docs/checklist-cobertura-fontes.md`).
  - `regra_calculo` — fórmula versionada por indicador (campo `expressao` é texto livre/
    pseudocódigo, não uma expressão executável).
  - `indicador_versao` / `indicador_resultado` / `indicador_versao_fonte` — resultado
    versionado por imóvel + linhagem (quais fontes/lotes entraram no cálculo).
  - `mapa_camada` + `feature_publicada` — camadas do mapa Leaflet.
  - `imovel_publicado` + `sobreposicao_publicada` — boletim congelado por imóvel.
- **`TipoIndicador` tem entidade JPA + repositório + service + controller REST completos**
  ([TipoIndicador.java](src/main/java/br/gov/sp/cps/datagis/gold/TipoIndicador.java),
  [TipoIndicadorController.java](src/main/java/br/gov/sp/cps/datagis/gold/TipoIndicadorController.java)),
  com `POST/GET/GET{id}/PUT/DELETE /api/indicadores/tipos` e testes (`TipoIndicadorControllerTest`,
  `TipoIndicadorServiceTest`, `TipoIndicadorTest`) — isso é trabalho recente (commits
  `LIZARDS-6`, mesclados no PR #13 pouco antes desta análise). **Mas é só o catálogo de
  tipos** (metadado: código, descrição, se tem fonte, se está ativo) — não tem nada de
  `regra_calculo`, `indicador_versao` ou `indicador_resultado` em código Java. Essas 3
  tabelas só existem como DDL, sem entidade JPA, sem repositório, sem service.
- **Carga de dado na Gold hoje = só a migration de seed**
  ([V023__seed_demo_gold.sql](src/main/resources/db/migration/seed-demo/V023__seed_demo_gold.sql)),
  cujo próprio cabeçalho diz:

  > `DADOS DE DEMONSTRACAO - GOLD. FALSOS. Pra o time desenvolver mapa / painel /
  > detalhe do imovel / relatorio antes da transformacao e do calculo existirem.`

  Ela faz `INSERT` direto via SQL estático: cria 1 `regra_calculo` fake por indicador
  (`descricao = 'DEMO: valor = f(sobreposicao, area_imovel) -- formula real na pag. 6
  do desafio'`), grava `indicador_versao` só para 6 imóveis fictícios cadastrados no
  próprio seed da Silver (`V021`, `cod_imovel` como `'PR-4106902-DEMO0001'`), e os
  valores de `indicador_resultado` são **números fixos escritos na mão** (`0.18`,
  `0.05`, `0.11`, `0.40` — não calculados a partir de geometria real).
- **Não existe nenhum job, service ou endpoint que leia a Silver de verdade, aplique
  interseção geométrica (`SDO_GEOM`/`SDO_ANYINTERACT`) contra área protegida/embargo/
  desmatamento, e grave um resultado real.** Busca por `IndicadorVersao`, `RegraCalculo`,
  `IndicadorResultado` como classe Java: nenhum resultado.
- O próprio `CLAUDE.md` (§2.6) já registrava isso como ponto em aberto ("o algoritmo de
  `hash_versao` ainda não foi detalhado — a coluna existe, a lógica de cálculo não") —
  confirmado: nada mudou nesse ponto até hoje.
- **Sem UI**: não existe nenhuma rota no frontend (`router/index.js`) para indicadores,
  mapa ou painel Gold. As 4 rotas ativas são `/fontes`, `/lotes`, `/quarentena`,
  `/conjuntos` — nenhuma delas toca a Gold.

### Conclusão

**Não.** O que existe é: (a) a modelagem de dados da Gold pronta e aplicada no banco,
(b) um cadastro/CRUD do catálogo de tipos de indicador (peça de configuração, não de
cálculo), e (c) uma carga de demonstração 100% sintética para desbloquear o
desenvolvimento de telas. **Nenhum dado real passou da Silver para a Gold até hoje** —
essa é a lacuna mais importante do projeto no momento, porque é o motor de cálculo dos
indicadores que é o entregável central do desafio da Visiona (rastreabilidade de
indicador socioambiental, não só ingestão de dado bruto).

---

## 2. A configuração do ETL funciona na tela de dados? (cadastrar fonte + regras)

### Cadastro de fonte — funciona de ponta a ponta

- Backend: `FonteDadoController` (`POST/GET/GET{id}/PUT /api/fontes`) —
  [FonteDadoController.java](src/main/java/br/gov/sp/cps/datagis/controle/FonteDadoController.java).
  Campos cobertos: nome, órgão, tipo de aquisição (`UPLOAD_MANUAL`/`API_REST`/
  `DOWNLOAD_URL`), URL, formato esperado, cron, campos de geometria por lat/lon (CSV).
- Frontend: `CadastroFontes.vue` (1439 linhas) — formulário completo de cadastro/edição,
  lista de fontes cadastradas, botão de "Executar" (dispara ingestão) e botão de upload
  para fontes `UPLOAD_MANUAL`. Consome a API real via proxy do Vite — não é mockado.
- Execução manual: `POST /api/fontes/{id}/executar` e `POST /api/fontes/{id}/upload`
  ([IngestaoController.java](src/main/java/br/gov/sp/cps/datagis/ingestao/IngestaoController.java))
  — testado ao vivo contra 9+ fontes reais (§5.8/§9.9 do CLAUDE.md), com feedback de
  progresso via `GET /api/fontes/{id}/progresso` (polling, `ProgressoExecucaoRegistry`).

### Cadastro de regras (de-para Bronze → Silver) — funciona de ponta a ponta

- Backend: `MapeamentoColunaController`
  ([MapeamentoColunaController.java](src/main/java/br/gov/sp/cps/datagis/controle/MapeamentoColunaController.java)) —
  `GET/POST/DELETE /api/fontes/{fonteId}/mapeamentos`. Persiste em
  `controle.mapeamento_coluna`, a mesma tabela lida em runtime pelo motor de validação
  (`OrquestradorValidacao`) — ou seja, **o que o analista cadastra na tela é
  efetivamente o que o pipeline usa**, não é uma tela decorativa.
- Frontend: `ModalMapeamentoColunas.vue` (680 linhas) — formulário para cadastrar
  coluna de origem, atributo de destino, tipo de destino (`COLUNA`/`JSON`/`GEOMETRIA`),
  tipo de dado, e as 5 flags de validação (`obrigatorio`, `validarGeometria`,
  `validarCpfCnpj`, `validarRecortePr`, `impedirRegressao`) — todas mapeadas 1:1 para as
  colunas reais de `mapeamento_coluna` (`V029`). Acessível a partir de um botão em cada
  linha de `CadastroFontes.vue`.
- **Duas regras de validação existentes no motor não são configuráveis pela tela**:
  `valor_minimo` (faixa numérica mínima) e `tabela_referencia`/`referencia_entidade_silver`
  (domínio/integridade cruzada) — existem na tabela e no motor de validação
  (`ValidadorFaixaMinima`, `ValidadorDominio`, `ValidadorIntegridadeCruzada`), mas não
  aparecem no formulário do `ModalMapeamentoColunas.vue`. Hoje só chegam ao banco via
  migration de seed (`V031`). Não é um bug — é cadastro incompleto na tela em relação ao
  que o motor já suporta.

### Agendamento (cron) — **[ATUALIZADO 2026-09-22] ligado de verdade via Quartz**

- `ConstrutorCron.vue` está embutido no formulário de `CadastroFontes.vue`
  (`<ConstrutorCron v-model="form.cronExpressao" />`) e salva de fato em
  `fonte_dado.cron_expressao` via `PUT/POST /api/fontes`.
- **Desde `LIZARDS-10` (PR #15, mesclado em 2026-09-21), o cron tem efeito real.**
  Confirmado por leitura de código:
  [`AgendadorInicializacaoRunner`](src/main/java/br/gov/sp/cps/datagis/ingestao/AgendadorInicializacaoRunner.java) —
  na subida do backend, lê `findByAtivoTrueAndCronExpressaoIsNotNull()` e registra um
  `CronTrigger` por fonte (`ingestao-fonte-{id}` / `trigger-fonte-{id}`, grupo
  `ingestao`); [`IngestaoAgendadaJob`](src/main/java/br/gov/sp/cps/datagis/ingestao/IngestaoAgendadaJob.java) —
  o Job que o Quartz efetivamente dispara, delegando pro mesmo
  `IngestaoExecucaoService` do disparo manual (`POST /api/fontes/{id}/executar`) — ou
  seja, a execução automática passa pelo **mesmo motor de validação/quarentena** já
  documentado na seção 8, não um caminho paralelo. `JobStore` persistido em JDBC no
  schema `QUARTZ` (`db/migration/quartz/V034__criar_tabelas_quartz.sql`), então
  sobrevive a restart do backend.
- Migration `V035__preencher_cron_expressao_fontes.sql` já preenche o cron das 11
  fontes automatizáveis cadastradas hoje (a 12ª, CAR via Shapefile, é `UPLOAD_MANUAL`
  e fica sem cron de propósito — upload não é algo que se agenda sozinho).
- **Ressalvas que continuam reais, não resolvidas por este PR** (documentadas no
  próprio `CLAUDE.md` §9.28 como "em aberto"):
  1. Os triggers só são sincronizados **na subida do backend** — editar
     `cron_expressao` pela tela (`PUT /api/fontes/{id}`) só passa a valer depois de
     reiniciar a aplicação. A tela edita o dado corretamente, mas ainda não "empurra"
     a mudança pro scheduler em execução.
  2. Desativar a fonte (`ativo=0`) ou apagar o cron **não remove** o trigger já
     gravado no `JobStore` — como ele é persistido, a fonte continuaria disparando.
  3. `proxima_execucao_em` ainda não é preenchida (o dado existiria via
     `trigger.getNextFireTime()`, só não foi conectado).
  - Na prática: **o caminho feliz (cadastrar fonte com cron, deixar rodando) já
    funciona de ponta a ponta**; o que falta é o caminho de edição/desativação em
    tempo real, que hoje exige reiniciar o backend para ter efeito garantido.

### Conclusão

**Sim, funciona — inclusive o agendamento, que era a única ressalva na versão anterior
desta análise.** Cadastro de fonte, cadastro de regras de validação/mapeamento e
agendamento automático (cron → Quartz) são reais, testados e efetivos: o que se
configura na tela altera o comportamento do pipeline, incluindo quando ele roda
sozinho. A ressalva que resta não é mais "não faz nada", e sim um detalhe operacional:
mudanças de cron/ativação só valem com certeza após um restart do backend.

---

## 3. O sistema está compatível com o fluxo "normal" de um processo de ETL?

Comparando com o desenho clássico de ETL (Extract → Transform → Load, orquestrado,
agendável, com tratamento de erro incremental):

### O que está alinhado com o "normal"

- **Extract genérico e resiliente**: um executor por formato (`IngestaoGeoJsonExecutor`,
  `IngestaoCsvExecutor`, `IngestaoShapefileExecutor`), paginação real para WFS/ArcGIS,
  retry antes de reduzir tamanho de página, hash de conteúdo para detectar "fonte não
  mudou" — isso é ETL incremental de verdade, testado contra 9+ fontes reais.
- **Transform com validação em cadeia**: `SanitizadorEntrada` + 7 validadores
  especializados (`ValidadorCpfCnpj`, `ValidadorDominio`, `ValidadorIntegridadeCruzada`,
  `ValidadorFaixaMinima`, `ValidadorGeometria`, `ValidadorRegressao`) orquestrados por
  `OrquestradorValidacao`, com deduplicação por chave natural (`DeduplicadorRegistros`).
  É comparável em sofisticação a um motor de qualidade de dados de ferramenta comercial.
- **Load com rastreabilidade real**: `TransicaoBronzeSilverService` grava lote a lote,
  com linhagem completa (`fonte_dado_id`/`lote_ingestao_id`/`registro_bruto_id`) em cada
  linha da Silver e da Quarentena. `GET /api/lotes` e `GET /api/lotes/{id}`
  ([LoteIngestaoController.java](src/main/java/br/gov/sp/cps/datagis/controle/LoteIngestaoController.java))
  dão auditoria de execução — o mais próximo que o projeto tem de um "log de pipeline".
- **Tela de monitoramento**: `LotesView.vue` (1616 linhas) mostra status por lote,
  métricas agregadas, e as ações de analista (liberar/descartar) — isso cobre a
  necessidade de "execuções visíveis" pedida pelo desafio da Visiona (RNF de logs).

### O que foge do "normal" ou está faltando

1. **[ATUALIZADO 2026-09-22] Orquestrador de "quando" existe (Quartz); orquestrador de
   "como" ainda não (Spring Batch).** Desde `LIZARDS-10` (PR #15), o Quartz decide
   *quando* uma ingestão dispara (seção 2). Mas Spring Batch — que resolveria chunk,
   retry nativo de framework e restart-do-meio — segue no `pom.xml` sem nenhum
   `Job`/`Step`/`Tasklet` escrito (confirmado por busca no código: zero classes
   estendendo `Tasklet`/`ItemProcessor`, zero uso de `JobBuilder`/`StepBuilder`). O
   pipeline em si (Bronze→Silver) continua rodando de forma síncrona dentro da mesma
   chamada — só que agora essa chamada pode ser disparada pelo Quartz, não só por clique
   manual (`LoteIngestaoWriter` ainda grava registro a registro, sem chunk, sem
   capacidade de retomar do meio se a aplicação cair no meio do lote). Em termos do
   CLAUDE.md (§3.2/§3.3): "quando" está resolvido, "como rodar de forma reprocessável"
   continua em aberto.
2. **Agendamento automático já existe.** Como visto no item 2 (seção 2 revisada) — cron
   configurável na tela **e agora com efeito real** via Quartz desde 2026-09-21. A
   execução manual (clique em "Executar"/upload) continua existindo em paralelo, mas
   deixou de ser o único caminho.
3. **Sem `pipeline_execucao`.** O CLAUDE.md (§2.7, §3.3) descreve essa tabela como
   "fonte de verdade do estado do pipeline" e already "decidida" — mas **a classe/tabela
   não existe em nenhuma migration nem em nenhum pacote Java**. O que faz esse papel na
   prática é `lote_ingestao` (com campo de status) — funciona, mas é uma tabela por
   ingestão de fonte, não uma visão de execução de pipeline completo
   (Bronze→Silver→Gold), porque a etapa Gold não existe ainda.
4. **Regra "tudo ou nada" por lote foge do padrão mais comum de ETL incremental.** Em
   ferramentas de ETL tradicionais, um registro ruim costuma ser isolado (vai para
   staging/erro) e o resto do lote segue adiante. Aqui, **um único registro reprovado
   retém o lote inteiro na quarentena — zero registros vão para a Silver até o analista
   decidir** (`TransicaoBronzeSilverService`, regra documentada e testada). Isso é uma
   escolha deliberada de auditabilidade (RN007: "o sistema nunca inventa dado faltante")
   e faz sentido para o domínio (crédito rural, dado tem que ser 100% confiável) — mas é
   um desvio real do "fluxo normal" de ETL, vale deixar isso explícito para quem for
   apresentar/avaliar o projeto, porque pode ser perguntado.
5. **Segurança desligada.** `SecurityConfig` libera tudo (`permitAll()`), sem
   autenticação. Um ETL "de produção" normalmente teria controle de quem dispara
   ingestão/libera lote — aqui qualquer requisição HTTP pode fazer qualquer ação.
   Já é uma decisão conhecida e documentada como temporária (CLAUDE.md §5.3), não é uma
   surpresa, mas é relevante para "compatibilidade com o fluxo normal" em produção.
6. **CSV sem paginação.** `IngestaoCsvExecutor` foi validado só por teste unitário com
   152 mil linhas — nunca rodou ao vivo por "desproporção de volume sem paginação de CSV
   ainda" (CLAUDE.md §5.8). Isso é uma limitação de escala, não de arquitetura.
7. **A etapa final (Silver→Gold) não existe** — já coberto em detalhe na pergunta 1;
   é o maior desvio do "fluxo normal" porque é a etapa que fecha o ciclo ETL do projeto.

### Conclusão

**Parcialmente compatível, mais próximo do "normal" do que na versão anterior desta
análise.** As etapas de Extract e a primeira metade do Transform+Load (Bronze→Silver)
seguem — e em alguns aspectos superam — o padrão esperado de um ETL (paginação, retry,
hash de mudança, validação em cadeia, quarentena rastreável), e **agora rodam também de
forma agendada** (Quartz, desde 2026-09-21), não só sob clique manual. O que o projeto
ainda não tem: orquestração *reprocessável* (chunk/retry/restart via framework dedicado
— Spring Batch segue não exercitado), um pipeline de execução visível ponta a ponta
(Bronze→Silver→**Gold**), nem a etapa de cálculo/publicação que fecharia a Load final do
indicador. Do ponto de vista de "ETL clássico", o projeto hoje é **E-T-l** (extract e
transform maduros e já agendáveis; load pela metade — só até a camada intermediária).

---

## 4. Temos camada Bronze, Silver, Gold e Quarentena — está correto?

Sim, as 4 camadas existem de verdade no banco e no código, não é só conceito de
documentação:

| Camada | Schema Oracle | Migrations | Estado real |
|---|---|---|---|
| **Bronze** | `bronze` | `V004` | ✅ Schema-on-read genérico (`registro_bruto`, payload JSON + WKT bruto). Recebe dado de 9+ fontes reais, ~350 mil+ registros históricos (conforme §9.11 do CLAUDE.md). Funcional e maduro. |
| **Silver** | `silver` | `V011`-`V014` | ✅ 7 tabelas de fato por fenômeno (`imovel_rural`, `area_protegida`, `embargo_ambiental`, `auto_infracao`, `desmatamento`, `foco_calor`, `municipio`) + ~16 tabelas de referência + view `vw_restricao_territorial`. Persistência espacial real (`MDSYS.SDO_GEOMETRY` SRID 4674, testado com lotes de milhares de registros e geometrias complexas). Recebe dado real via `TransicaoBronzeSilverService`, não só seed. |
| **Quarentena** | `quarentena` | `V015`, `V017`, `V030` | ✅ Espelho frouxo por entidade (`q_imovel_rural`, `q_area_protegida`, etc.), com bloco de rejeição (tipo de erro, motivo, campo problemático) e workflow de status (`PENDENTE`/`EM_ANALISE`/`CORRIGIDO_REPROCESSADO`/`DESCARTADO`). Testado ao vivo (lote #45, 2 registros retidos por auto-interseção). |
| **Gold** | `gold` | `V016`, `V018`-`V020` | ⚠️ **Modelada e aplicada, mas sem dado real** — só o catálogo `tipo_indicador` (via `TipoIndicadorController`) e uma carga de demonstração 100% sintética (`V023`). Ver pergunta 1. |
| **Controle** (transversal) | `controle` | `V001`-`V003`, `V005`-`V010`, `V024`-`V029`, `V031`-`V032` | ✅ `fonte_dado`, `lote_ingestao`, `mapeamento_coluna` funcionais. Novidade recente: `conjunto` (`V032`) — ver observação abaixo. |

### Observação: uma 5ª peça nova, ainda não integrada ao pipeline — `Conjunto`

O commit mais recente do repositório (PR #12/#13, `LIZARDS-8`/`LIZARDS-9`, mesclados em
2026-09-21 — **posterior à última atualização do CLAUDE.md**) introduziu uma entidade
`controle.conjunto`
([Conjunto.java](src/main/java/br/gov/sp/cps/datagis/controle/Conjunto.java)):
associa uma `FonteDado` a uma competência (período) e um `StatusCamada`
(`ATIVA`/`INATIVA`/`PROCESSANDO`/`ERRO`), com cobertura territorial opcional
(`SDO_GEOMETRY`). Tem CRUD básico (`GET/POST /conjuntos`) e tela própria
(`ConjuntosView.vue`, rota `/conjuntos`).

Vale registrar 3 pontos sobre essa peça, para quem for continuar o trabalho:
- **Ainda não está conectada ao pipeline real**: o próprio changelog do time
  (`docs/pr-lizards-8.md`) admite que "o status é informado no cadastro e ainda não é
  atualizado pelo pipeline" — ou seja, `StatusCamada.PROCESSANDO`/`ERRO` não refletem
  o que está de fato acontecendo em `lote_ingestao`/`TransicaoBronzeSilverService`.
  É um cadastro paralelo, não (ainda) uma engrenagem do ETL.
  - Não fica claro no código atual qual é a diferença de propósito entre `Conjunto`
    (fonte + competência) e `LoteIngestao` (fonte + execução) — parecem sobrepor-se
    parcialmente; vale a equipe esclarecer isso antes de expandir mais essa peça.
- A rota HTTP é `/conjuntos`, fora do prefixo `/api` usado por todo o resto da API
  (`/api/fontes`, `/api/lotes`, `/api/quarentena`) — inconsistência pequena, mas seria
  bom padronizar.

### Conclusão

**Sim, a arquitetura de 4 camadas (medalhão) está corretamente implementada no nível
estrutural** — schemas separados, migrations versionadas, entidades com linhagem
completa. Bronze, Silver e Quarentena estão **vivas** (recebendo e processando dado
real). A Gold está **estruturalmente correta, mas ainda inerte** — funciona como uma
"prateleira pronta, mas vazia", esperando o motor de cálculo que ainda não foi escrito.

---

## 5. Viabilidade de fazer o Silver → Gold via procedures PL/SQL (requisito da FATEC)

**Pergunta da equipe:** a disciplina exige pelo menos 2 procedures Oracle para qualquer
finalidade. Existe a possibilidade de usar o cálculo Silver→Gold (a lacuna descrita na
seção 1) como o caso de uso dessas procedures?

**Resposta curta: sim, e é uma escolha tecnicamente boa — não só "dá pra encaixar o
requisito da disciplina aqui", mas esse cálculo é o tipo de operação para o qual
PL/SQL é o lugar certo.** Aliás, é a peça que hoje falta em uma exigência que o próprio
desafio oficial da Visiona já cobrava (RNF: "Oracle com PL/SQL" — CLAUDE.md §1) e que o
projeto, até agora, não atende em lugar nenhum: **busquei em toda a árvore de migrations
e em todo o código Java por `CREATE PROCEDURE`, `CREATE FUNCTION`, `CREATE PACKAGE` e por
qualquer uso de função Oracle Spatial (`SDO_GEOM.*`, `SDO_ANYINTERACT`,
`SDO_INTERSECTION`) — zero ocorrências em todo o repositório.** Hoje 100% da lógica de
negócio do projeto está em Java/JPA; PL/SQL é uma lacuna completa, não só de indicador.
Isso é bom notícia para a proposta: não tem nada pra conciliar ou substituir, é
território livre.

### Por que esse cálculo, especificamente, combina com procedure

1. **É uma operação geométrica set-based, o caso de uso clássico de Oracle Spatial em
   PL/SQL.** Calcular ISAP/IAE/IDesmat é, no fundo: pegar o polígono do imóvel
   (`silver.imovel_rural.geometria`), cruzar contra os polígonos de restrição
   territorial, e medir área de sobreposição. Isso é exatamente o que
   `SDO_GEOM.SDO_INTERSECTION` (geometria da interseção) + `SDO_GEOM.SDO_AREA` (área
   geodésica em SRID 4674) + `SDO_ANYINTERACT` (filtro rápido de "toca ou não toca")
   fazem nativamente — todas funções PL/SQL/SQL, chamáveis de dentro de uma procedure
   sem sair do banco.
2. **A view que o cálculo precisa já existe, parada, esperando exatamente isso.**
   `silver.vw_restricao_territorial` ([V014__silver_view_restricao_territorial.sql](src/main/resources/db/migration/silver/V014__silver_view_restricao_territorial.sql))
   unifica `area_protegida` + `embargo_ambiental` + `desmatamento` num único conjunto
   com `geometria`, `area_ha` e `tipo_restricao` (`AREA_PROTEGIDA`/`EMBARGO_AMBIENTAL`/
   `DESMATAMENTO` — mapeiam 1:1 para ISAP/IAE/IDesmat). O comentário de cabeçalho da
   própria migration (de meses atrás) já dizia: "os calculadores de ISAP/IAE/IDesmat leem
   esta view" — ou seja, o desenho de dados **já foi pensado para ser consumido por uma
   rotina no banco**, não em Java. Uma procedure PL/SQL é o consumidor mais natural
   dessa view; reimplementar a mesma lógica em Java/JTS seria replicar trabalho que o
   Oracle já faz de fábrica.
3. **Evita puxar geometria gigante pra JVM à toa.** O próprio projeto já documentou
   (§9.27 do CLAUDE.md) que os polígonos do CAR geram WKT de 20.000 a 100.000+
   caracteres, e que isso já causou problema real de driver (`ORA-01461`) quando
   passado por JDBC. Calcular a interseção dentro do Oracle evita esse transporte
   inteiramente — só entra e sai o resultado numérico (área afetada, percentual), não a
   geometria bruta.
4. **Consistente com o padrão que o projeto já usa para tudo que é Oracle Spatial.**
   `PersistidorSilverService` já abandona JPA/Hibernate e cai para `JdbcTemplate` +
   `PreparedStatementCallback` puro sempre que a operação é espacial ou tem
   particularidade do driver Oracle (CLOB manual, MERGE com SDO_GEOMETRY — CLAUDE.md
   §9.27). Chamar uma stored procedure via `SimpleJdbcCall`/`CallableStatement` a partir
   de um service Java é só mais um passo na mesma direção já trilhada, não um paradigma
   novo para a equipe.
5. **Migration-friendly.** DDL de procedure é só mais um arquivo `.sql` em
   `db/migration/gold/`, versionado pelo Flyway do mesmo jeito que qualquer tabela —
   não quebra a disciplina de "tudo em migration" que o projeto já segue.

### Esboço de divisão em procedures (mínimo 2, sugestão de 3-4 para separação de responsabilidade)

Isto é só uma sugestão de desenho para a equipe avaliar — **nada disso foi implementado
nesta sessão**, é ponto de partida para discussão.

| # | Procedure (nome sugestivo) | Responsabilidade | Por que separada |
|---|---|---|---|
| 1 | `pkg_indicadores.calcular_sobreposicao` | Recebe `cod_imovel` + `tipo_restricao` (`AREA_PROTEGIDA`/`EMBARGO_AMBIENTAL`/`DESMATAMENTO`); cruza a geometria do imóvel contra `vw_restricao_territorial` filtrada por tipo; devolve (`OUT`) área afetada em ha e percentual de sobreposição. Cobre ISAP, IAE e IDesmat com a mesma rotina, só trocando o parâmetro de tipo. | É o núcleo geométrico reutilizável — 3 dos 4 indicadores com fonte hoje (ISAP/IAE/IDesmat) são a mesma operação (interseção de área), só muda o conjunto de restrição. |
| 2 | `pkg_indicadores.contar_focos_calor` | IFC não é sobreposição de área — é contagem/densidade de pontos (`foco_calor`) dentro do polígono do imóvel num período. Lógica de `SDO_RELATE`/`ANYINTERACT` ponto-em-polígono, diferente da nº 1. | Lógica genuinamente distinta (ponto vs. área) — forçar na mesma procedure da nº 1 misturaria responsabilidades. |
| 3 | `pkg_indicadores.publicar_indicadores_imovel` | Orquestradora: para um `cod_imovel` (ou lote de imóveis), chama as procedures 1 e 2 para cada `tipo_indicador` com `tem_fonte=1`, calcula `hash_versao` (candidato natural: `STANDARD_HASH`, função Oracle nativa, sobre a concatenação dos IDs de fonte/regra/resultado), e grava `indicador_versao` + `indicador_resultado` + `indicador_versao_fonte`. | É o "orquestrador"/"writer" — equivalente em espírito ao papel que `TransicaoBronzeSilverService` já cumpre para Bronze→Silver, só que dentro do banco. |
| 4 (opcional) | `pkg_indicadores.publicar_para_mapa` | Passo de "publicação" propriamente dito: promove um `indicador_versao` calculado para `imovel_publicado`/`feature_publicada` (o que o portal/mapa efetivamente lê). Separado do cálculo. | Espelha a distinção que já existe em Quarentena→Silver (calcular ≠ liberar) — dá um ponto de auditoria/aprovação explícito antes de algo virar público, coerente com o objetivo central do projeto (rastreabilidade). |

Isso já entrega **3 a 4 procedures** com responsabilidade bem separada — folga
confortável acima do mínimo de 2 exigido pela disciplina, sem parecer fragmentação
artificial (cada uma tem um motivo real de existir separada das outras).

O papel do Java, nesse desenho, fica pequeno e honesto: um `IndicadorGoldService` que
só dispara a procedure orquestradora (via `SimpleJdbcCall` ou `CallableStatement` cru,
no mesmo estilo de `PersistidorSilverService`) e expõe isso por um endpoint (ex.:
`POST /api/indicadores/imoveis/{codImovel}/calcular` ou, para lote,
`POST /api/gold/publicar?competencia=...`) — a lógica de negócio do cálculo mora no
banco, o Java só aciona e traz o resultado pra API/tela.

### Riscos e pontos de atenção, para a equipe entrar de olhos abertos

- **Faltam índices espaciais nas tabelas de restrição/imóvel.** O próprio CLAUDE.md
  (§9.11) já registra que "índices espaciais ficam pra fatia de cálculo (tabelas
  vazias)" — ou seja, `CREATE INDEX ... INDEXTYPE IS MDSYS.SPATIAL_INDEX` em
  `imovel_rural.geometria` e nas tabelas por trás de `vw_restricao_territorial` **ainda
  não existe**. Sem isso, `SDO_ANYINTERACT`/`SDO_INTERSECTION` fazem varredura completa
  a cada chamada — funciona para o circuito pequeno de homologação (Curitiba/Maringá/
  Ponta Grossa), mas é um pré-requisito antes de rodar contra volume maior. Vale criar
  esse índice espacial como parte da mesma migration que cria as procedures.
- **Testar PL/SQL não segue o mesmo molde dos 298 testes atuais** (JUnit + Mockito, sem
  tocar banco na maioria). Já existe 1 precedente no projeto para esse tipo de teste —
  `ConjuntoOracleIT` (integração real contra o Oracle do compose, atrás de uma flag de
  ambiente `DATAGIS_ORACLE_IT=true`, conforme `docs/testes-conjuntos.md`) — esse é o
  padrão a seguir para testar as procedures (não dá pra mockar `SDO_GEOM.SDO_AREA`).
- **`hash_versao` (apontado como ponto em aberto desde §2.6 do CLAUDE.md) precisa ser
  decidido de qualquer forma** — fazer a procedure calcular via `STANDARD_HASH` resolve
  isso de forma natural, já que a procedure é quem tem acesso a todos os valores de
  entrada (fonte, regra, resultado) no mesmo lugar.
- **Curva de aprendizado real de PL/SQL para quem for escrever** — é custo genuíno, mas
  é literalmente o que a disciplina pede para ser exercitado neste semestre, então aqui
  isso é o objetivo, não um efeito colateral incômodo.

### Conclusão da seção

Sim, existe a possibilidade — e ela se encaixa bem no ponto exato que a análise da
seção 1 identificou como a maior lacuna do projeto hoje. Em vez de fechar essa lacuna
em Java (que seria só "mais um jeito de fazer"), fechar via procedure PL/SQL resolve
**dois problemas de uma vez**: o requisito acadêmico de procedures **e** o RNF do
desafio oficial da Visiona que pede PL/SQL e que, até hoje, não é atendido em nenhum
lugar do código. Recomendo que a equipe trate essa frente como prioridade alta —
tecnicamente é a decisão mais natural para esse cálculo, não uma solução de
conveniência para bater requisito de disciplina.

---

## 6. Como estruturar tecnicamente — e o Silver → Gold precisa ser configurável?

Duas perguntas da equipe, tratadas juntas porque a resposta da segunda define a forma
da primeira.

### 6.1. Precisa ser configurável do mesmo jeito que o Bronze → Silver?

**Não — e vale entender o porquê, porque é fácil copiar o padrão errado por reflexo.**

O Bronze→Silver é data-driven (tabela `controle.mapeamento_coluna`, sem código novo por
fonte) por um motivo específico: **cada fonte cadastrada tem um schema de colunas
diferente e imprevisível** (CAR tem `cod_imovel`/`area`/`condicao`; IBAMA tem
`NUM_TAD`/`CPF_CNPJ_EMBARGADO`; o próximo órgão que a equipe cadastrar vai ter outro
conjunto de colunas ainda). Sem configuração em dado, cada fonte nova exigiria código
Java novo — foi exatamente esse problema que a arquitetura genérica por formato
(CLAUDE.md §5.8) resolveu.

**Esse motivo não existe no Silver → Gold.** Os 7 indicadores (ICV, IRL, IAPP, ISAP,
IAE, IDesmat, IFC) são um conjunto **fixo, pequeno e definido pelo desafio oficial da
Visiona** (fórmulas na pág. 6 do PDF) — não crescem conforme fontes novas são
cadastradas, e as entradas do cálculo (`silver.imovel_rural.geometria` ×
`silver.vw_restricao_territorial`) já são canônicas e estáveis, independente de quantas
fontes alimentaram a Silver por baixo. Cadastrar uma fonte nova de embargo, por exemplo,
não cria um 8º indicador nem muda a fórmula do IAE — só alimenta com mais dado a mesma
conta que já existe.

Tentar replicar o padrão Bronze→Silver aqui — por exemplo, guardar a "fórmula" como
texto livre numa tabela e interpretá-la em runtime, tipo um mini-DSL de expressão
matemática/geométrica — seria over-engineering real para um conjunto fixo de 7 contas
conhecidas, e é bem mais arriscado de construir num semestre (vira, na prática,
escrever um interpretador de expressões). É sintomático que a própria DDL já aplicada
(`gold.regra_calculo.expressao`, `V016`) descreve o campo como **"texto/pseudocódigo da
fórmula"** — ou seja, o desenho original já era só documentação/auditoria, nunca teve a
intenção de ser executado dinamicamente. Manter esse espírito é o caminho mais seguro.

### 6.2. Onde faz sentido ter alguma configurabilidade (forma mais estreita, não um DSL)

Nem tudo em Silver→Gold é 100% fixo — só a **lógica geométrica** (a fórmula em si) é
fixa. Existem números de negócio que fazem sentido ficar fora do código PL/SQL, porque
um Analista/Gestor Socioambiental pode legitimamente precisar ajustá-los sem pedir
deploy novo:

- **Parâmetros/limiares por bioma ou norma**, ex.: percentual mínimo de Reserva Legal —
  o próprio CLAUDE.md já registra que no Paraná (Mata Atlântica) é 20%, diferente da
  regra da Amazônia Legal (§5.2). Se o sistema algum dia processar outro bioma, esse
  número muda — é candidato natural a virar linha de tabela, não `NUMBER` fixo dentro
  do PL/SQL.
- **Faixas de classificação do resultado** (ex.: o que conta como "risco baixo/médio/
  alto" de sobreposição) — se o desafio ou a Visiona pedirem esse tipo de rótulo depois,
  é outro caso de "número configurável", não fórmula configurável.

Sugestão de forma **pequena e controlada**, para não reabrir o problema do DSL: uma
tabela simples de parâmetro versionado, no mesmo espírito de `regra_calculo`:

```sql
-- Esboço, não uma migration pronta:
CREATE TABLE gold.parametro_calculo (
    id                 NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tipo_indicador_id  NUMBER       NOT NULL REFERENCES gold.tipo_indicador(id),
    chave              VARCHAR2(60) NOT NULL,   -- ex.: 'PERCENTUAL_RL_MINIMO'
    valor              NUMBER       NOT NULL,
    vigente_de         DATE         NOT NULL,
    vigente_ate        DATE
);
```

A procedure lê esse valor com um `SELECT` simples (uma constante nomeada, não uma
fórmula a interpretar) — é configuração de **parâmetro**, não de **lógica**. É uma
diferença de eixo importante: Bronze→Silver precisa ser configurável em **como
interpretar a entrada** (schema variável); Silver→Gold, se for configurável em algo, é
em **quais constantes de negócio a fórmula fixa usa** — um problema bem mais restrito.

### 6.3. Configurabilidade que já existe e deve ser aproveitada: versionamento, não runtime

O ponto onde "configurável" já está certo na DDL atual é o versionamento de
`regra_calculo` (`versao`, `vigente_de`, `vigente_ate` — `V016`). Isso não é
configuração em tempo de execução, é **auditoria de mudança de regra ao longo do
tempo**: se a fórmula do ISAP mudar amanhã, insere-se `regra_calculo` versão 2 (nova
`vigente_de`), fecha-se a versão 1 (`vigente_ate`), e os `indicador_versao` antigos
continuam apontando para a regra 1 — o resultado de um imóvel calculado em março não
muda retroativamente só porque a fórmula mudou em julho. Isso é exatamente o tipo de
"configurável" que serve ao objetivo central do projeto (rastreabilidade/auditabilidade
— CLAUDE.md §1: "dar meios de comprovar que uma informação... é verdadeira"), e já está
desenhado — só falta a procedure que efetivamente lê `vigente_de/vigente_ate` para achar
a regra correta na data do cálculo.

### 6.4. Estrutura técnica proposta (camadas de responsabilidade)

Juntando a resposta de "configurável ou não" com a estrutura sugerida na seção 5:

```
┌─────────────────────────────────────────────────────────────┐
│ Frontend (Vue) — ainda não existe tela para isso             │
│   ex.: botão "Calcular indicadores" numa tela nova de Gold   │
└───────────────────────────┬─────────────────────────────────┘
                             │ HTTP
┌───────────────────────────▼─────────────────────────────────┐
│ Java — orquestração FINA, não lógica de negócio               │
│   IndicadorGoldController → IndicadorGoldService              │
│   • decide QUANDO disparar (manual hoje; Quartz no futuro)   │
│   • chama a procedure via SimpleJdbcCall/CallableStatement    │
│   • traduz exceção Oracle → resposta HTTP (padrão             │
│     TratadorDeErro já usado no projeto)                       │
└───────────────────────────┬─────────────────────────────────┘
                             │ chamada de procedure
┌───────────────────────────▼─────────────────────────────────┐
│ PL/SQL (Oracle) — TODA a lógica de cálculo mora aqui          │
│   pkg_indicadores.calcular_sobreposicao   (fixo, sem DSL)     │
│   pkg_indicadores.contar_focos_calor      (fixo, sem DSL)     │
│   pkg_indicadores.publicar_indicadores_imovel (orquestra,     │
│     lê regra_calculo vigente + parametro_calculo se houver)   │
└───────────────────────────┬─────────────────────────────────┘
                             │ lê / grava
┌───────────────────────────▼─────────────────────────────────┐
│ Dados                                                          │
│   entrada:  silver.imovel_rural + silver.vw_restricao_...     │
│   config:   gold.regra_calculo (versão) [+ gold.parametro_... │
│             se a equipe decidir criar]                        │
│   saída:    gold.indicador_versao / indicador_resultado /     │
│             indicador_versao_fonte                            │
└─────────────────────────────────────────────────────────────┘
```

**Ponto de disparo (quando rodar):** três opções, em ordem de esforço crescente —

1. **Manual, sob demanda** (`POST /api/indicadores/imoveis/{codImovel}/calcular` ou em
   lote por município/circuito) — mais simples, e coerente com o padrão atual do
   projeto (ingestão de fonte e liberação de lote da quarentena também são manuais
   hoje). Recomendado como ponto de partida.
2. **Automático ao final da promoção Bronze→Silver** (hook dentro de
   `TransicaoBronzeSilverService`) — mais "pipeline fechado", mas arriscado de
   recalcular em excesso (um imóvel pode ser afetado por lotes de várias fontes
   diferentes ao longo do tempo; recalcular a cada promoção pode ser redundante).
   Considerar só depois que o cálculo manual estiver validado.
3. **Por competência/período**, encaixando com o conceito de `Conjunto` que a equipe
   acabou de introduzir (fonte + competência) — um botão "calcular indicadores desta
   competência" seria uma extensão natural da tela `/conjuntos` mais adiante, embora
   hoje `Conjunto` ainda não esteja conectado ao pipeline real (seção 4, observação).

**Um detalhe de forma, para confirmar com o professor antes de escrever código:** o
esboço da seção 5 agrupa as procedures num `PACKAGE` (`pkg_indicadores`), que é boa
prática de organização em PL/SQL — mas cada procedure dentro de um package ainda é uma
`PROCEDURE` de verdade (`CREATE PACKAGE ... procedure calcular_sobreposicao; ...`), só
não é um objeto `CREATE PROCEDURE` standalone no topo do schema. Se a exigência da
disciplina for interpretada literalmente como "N objetos `CREATE PROCEDURE`
independentes", vale confirmar com o professor se procedures dentro de um package
contam — se não, a alternativa é simplesmente declarar as mesmas rotinas como
procedures standalone (sem o wrapper de package), o que também funciona e é mais
simples de mostrar individualmente numa apresentação.

### Conclusão da seção

**Silver→Gold não precisa (e não deveria) ser configurável no sentido de "regra
definida em dado e interpretada em runtime"** — isso resolveria um problema que não
existe aqui (o Bronze→Silver tem schema de entrada variável; o Silver→Gold tem fórmula
fixa e pequena, definida pelo desafio). O grau certo de "configurável" para esta camada
é **versionamento de regra** (já modelado em `regra_calculo`) e, no máximo,
**parametrização de constantes de negócio** (limiar por bioma, por exemplo) — não a
lógica de cálculo em si, que deve morar fixa dentro das procedures PL/SQL.

---

## 7. Cruzamento com a tabela oficial "Dados necessários, fontes e formatos"

A equipe compartilhou a tabela oficial do desafio (slide "Dados necessários, fontes e
formatos", já tratada em `docs/fontes-de-dados.md` e `docs/checklist-cobertura-fontes.md`)
— reproduzida abaixo para referência permanente neste documento, e cruzada com o que
existe **de fato** hoje na Silver e no cadastro de fontes:

| Dado necessário | Fonte pedida | Indicador(es) | Existe em `silver.*` hoje? | Fonte cadastrada funcionando? |
|---|---|---|---|---|
| Imóvel rural (perímetro) | CAR | Todos | ✅ `imovel_rural.geometria` | ✅ CAR/PR (WFS + upload Shapefile) |
| APP declarada | CAR | IAPP, ICV | ❌ nenhuma coluna/tabela | ❌ gap — API oficial (`api-sicar-tema`) exige credenciamento (Serpro) |
| Reserva Legal declarada | CAR | IRL | ❌ nenhuma coluna/tabela | ❌ mesmo gap da APP |
| Vegetação nativa | CAR | ICV, IAPP | ❌ nenhuma coluna/tabela | ⚠️ candidato (`Vegetacao_Nativa_IAT_2021`) identificado, não testado a fundo |
| Limites de biomas | IBGE | IRL | ⚠️ não modelada como tabela própria (`municipio` não carrega bioma hoje) | ✅ fonte confirmada (Shapefile IBGE) |
| Áreas protegidas e territórios especiais | ICMBio/MMA/FUNAI/INCRA/SFB | ISAP | ✅ `area_protegida` + `area_protegida_uf` | ⚠️ 3 de 5 órgãos confirmados (ICMBio, FUNAI, INCRA); MMA implícito, SFB em aberto |
| Embargos ambientais | IBAMA, ICMBio | IAE | ✅ `embargo_ambiental` | ✅ ambos os órgãos |
| Desmatamento | INPE (PRODES) | IDesmat | ✅ `desmatamento` | ✅ Mata Atlântica/PR, granularidade anual |
| Focos de calor | INPE (Queimadas) | IFC | ✅ `foco_calor` | ✅ (granularidade diária confirmada na v2) |
| Malha municipal | IBGE | Agregação | ✅ `municipio.geometria` | ✅ |

### O que isso muda no desenho das procedures (seção 5)

O cruzamento **confirma exatamente** a divisão que já estava na DDL — não é
coincidência, é o motivo de `gold.tipo_indicador.tem_fonte` existir como flag desde a
migration `V016`:

- **Calculáveis hoje, dado já existe na Silver: ISAP, IAE, IDesmat, IFC** (`tem_fonte=1`
  na seed). São exatamente os 4 que a `pkg_indicadores.calcular_sobreposicao` +
  `pkg_indicadores.contar_focos_calor` da seção 5 cobrem. **A procedure orquestradora
  (nº 3) já foi desenhada para varrer `WHERE tem_fonte = 1`** — ou seja, o desenho da
  seção 5 já nasce coerente com essa tabela, sem precisar de ajuste.
- **Bloqueados por falta de dado na origem, não por falta de procedure: IAPP, IRL, ICV**
  (`tem_fonte=0`). Não adianta escrever a procedure de cálculo para esses 3 agora — não
  existe `silver.app_declarada`/`reserva_legal_declarada`/`vegetacao_nativa` para ela
  ler. A procedure certa, para esses 3, ainda depende do desbloqueio documentado em
  `docs/checklist-cobertura-fontes.md` (credenciamento `api-sicar-tema`) e de validar o
  candidato de vegetação nativa (`Vegetacao_Nativa_IAT_2021`) — isso é trabalho de
  fonte de dado, não de PL/SQL.
- **ISAP tem uma nuance**: a Silver já tem `area_protegida` alimentada por 3 dos 5
  órgãos pedidos (ICMBio, FUNAI, INCRA). O indicador **pode ser calculado hoje** com
  cobertura parcial — vale decidir com a equipe se isso é aceitável para a massa de
  homologação (o próprio desafio autoriza recorte, CLAUDE.md §5.2) ou se vale esperar
  MMA/SFB antes de publicar o resultado como definitivo.
- **Limites de biomas (IBGE)** aparece como insumo do IRL na tabela oficial, mas hoje
  **não está modelado em nenhuma tabela Silver** — nem `municipio` carrega bioma. Isso é
  outro pré-requisito de modelagem (provavelmente uma coluna nova em `municipio` ou uma
  tabela de referência `bioma`), independente do bloqueio de CAR — vale a equipe
  decidir se cadastra essa fonte antes de tentar montar o IRL completo (que também
  depende da Reserva Legal declarada do CAR, então IRL está duplamente bloqueado hoje).

### Um detalhe da tabela oficial que vale decidir antes de escrever a procedure

O rodapé da tabela compartilhada diz: **"as camadas são reprojetadas para projeção
equivalente antes do cálculo de área."** Isso é uma orientação de boa prática de SIG
(calcular área numa projeção equivalente/métrica evita distorção), e **entra em tensão
direta com o que o CLAUDE.md registra hoje como proposta** (§2.5): calcular direto no
SRID geográfico nativo (4674), usando `SDO_GEOM.SDO_AREA`/`SDO_ANYINTERACT` (que são
geodésicas — calculam área corretamente mesmo em SRID geográfico, sem precisar
reprojetar), reprojetando pra métrico (EPSG:5880) só se aparecer inconsistência.

As duas abordagens chegam a um resultado **tecnicamente correto** dentro do Oracle
Spatial (a função `SDO_GEOM.SDO_AREA` é geodésica-aware nesse caso), então não é um
bug escolher uma ou outra — mas **a procedure de cálculo precisa fixar uma decisão**
antes de ser escrita, porque isso muda a assinatura da chamada (`SDO_GEOM.SDO_AREA`
direto no 4674, com tolerância em metros, vs. `SDO_CS.TRANSFORM(geometria, 5880)` antes
de calcular). Recomendo a equipe decidir isso como parte do desenho da procedure nº 1
(`calcular_sobreposicao`) — não é grande esforço (é 1 chamada a mais, `SDO_CS.TRANSFORM`,
se optarem por seguir a orientação literal da tabela oficial), mas é bom fechar
antes de codar para não ter que reescrever a procedure depois.

---

## 8. Como funciona o Bronze → Silver hoje (documentação técnica detalhada)

Esta seção documenta, com base na leitura linha a linha do código (não do resumo do
CLAUDE.md), exatamente como um lote sai da Bronze e chega — ou não — na Silver. É o
processo mais maduro do projeto hoje e serve de referência de qualidade para o que o
Silver→Gold (seções 5-7) ainda precisa igualar.

### 8.1. Visão geral do fluxo

```
LoteIngestaoWriter.gravar[ComHash|Streaming](...)
  → grava N registro_bruto no schema BRONZE
  → chama TransicaoBronzeSilverService.processarLote(loteId)
       │
       ├─ 1. Carrega todos os RegistroBruto do lote
       ├─ 2. resolverEntidadeSilver(fonte) + resolverChaveNatural(entidade, fonte)
       ├─ 3. OrquestradorValidacao.orquestrarLote(...)
       │      ├─ 3a. DeduplicadorRegistros.deduplicar(...)  — 1 vencedor por chave natural
       │      └─ 3b. Por registro vencedor: Sanitizador → cadeia de ValidadorCampo
       │             (fail-fast: 1º campo com erro já classifica o registro inteiro)
       │
       └─ 4. Regra All-or-Nothing:
              • qualquer rejeitado → PersistidorQuarentenaService (100% do lote retido)
              • zero rejeitados    → PersistidorSilverService (100% do lote promovido)
```

Todo o processo roda **de forma síncrona, dentro da mesma transação Spring** que grava
o lote — não há fila, worker assíncrono ou chunk de Spring Batch envolvido (consistente
com o que a seção 3 já registrou: a dependência do Batch está no `pom.xml`, mas quem
efetivamente orquestra isso é este código Java "na marra").

### 8.2. Gatilho — `LoteIngestaoWriter`

[ingestao/LoteIngestaoWriter.java](src/main/java/br/gov/sp/cps/datagis/ingestao/LoteIngestaoWriter.java)
é o ponto comum a **qualquer formato de ingestão** (GeoJSON, CSV, Shapefile) — depois de
gravar os `registro_bruto` do lote (linha 156-174, ou via streaming linha 256-280 para
fontes grandes demais pra caber em lista), ele chama
`transicaoBronzeSilverService.processarLote(lote.getId())` (linha 176-183) dentro do
mesmo método `@Transactional`. Dois detalhes técnicos relevantes:

- **A dependência é opcional** (`@Autowired(required = false)`, linha 70) — se o bean
  não existir no contexto (ex.: em teste unitário isolado), a gravação da Bronze
  continua funcionando normalmente, só sem disparar a transição. Projetado para não
  acoplar o teste do Bronze ao motor de validação.
- **Falha na transição não derruba a gravação do lote**: está num `try/catch` que só
  loga o erro (linha 176-183) — o lote já foi gravado e commitado na Bronze antes dessa
  chamada; se `processarLote` lançar exceção, o lote fica com status `CONCLUIDO`
  (marcado antes da chamada, linha 172-174) em vez de `PROMOVIDO_SILVER`/
  `RETIDO_QUARENTENA`. Isso é coerente com o princípio "Bronze nunca falha por causa de
  regra de negócio" — mas também significa que um lote pode ficar "preso" em
  `CONCLUIDO` (nem promovido, nem retido) se a transição quebrar por outro motivo (ex.:
  erro de infraestrutura), sem um mecanismo automático de retentativa.

### 8.3. Deduplicação — `DeduplicadorRegistros`

Antes de qualquer validação campo a campo, todos os registros do lote passam por
[validacao/DeduplicadorRegistros.java](src/main/java/br/gov/sp/cps/datagis/validacao/DeduplicadorRegistros.java):
agrupa por chave natural (ex.: `cod_imovel`, extraída do JSON do `payload` via
`extrairCampoDoPayload`, linhas 196-210) e, quando há mais de um registro com a mesma
chave (mesmo lote **ou** lotes anteriores — a checagem já vem com o histórico completo,
não só o lote atual), aplica um comparador de precedência com 3 critérios em cascata
(linhas 139-168):

1. **Regra de negócio do CAR**: `situacao_imovel = "AT"` vence qualquer outro status
   (`"CA"`/`"PE"`) — mesmo que o registro `"AT"` seja tecnicamente mais antigo.
2. Maior `lote_ingestao_id` (snapshot mais recente).
3. Maior `data_ingestao`, depois maior `id` (desempate final).

Os perdedores viram `DuplicataDescartada` com motivo explícito — não entram nem na
Silver nem na Quarentena, simplesmente não seguem adiante (não é erro, é redundância
esperada entre execuções). Essa deduplicação é **genérica** (`deduplicarGenerico`,
linhas 73-130) — o comparador específico do CAR é só o padrão default; qualquer
chamador pode passar outro `Comparator`.

### 8.4. Motor de validação campo a campo — `OrquestradorValidacao`

[validacao/OrquestradorValidacao.java](src/main/java/br/gov/sp/cps/datagis/validacao/OrquestradorValidacao.java)
é o coração do Bronze→Silver. Para cada registro vencedor da deduplicação:

1. Carrega os `MapeamentoColuna` cadastrados para a `fonte_dado_id` (a mesma tabela e os
   mesmos registros que a tela `ModalMapeamentoColunas.vue` edita — seção 2 já
   confirmou essa ponta a ponta).
2. **Se não houver nenhum mapeamento cadastrado para a fonte, cai num fallback "passa
   tudo direto"** (linhas 109-132): lê as chaves do JSON cru e copia pra
   `atributosTransformados` sem qualquer validação, detectando geometria só por nome de
   chave (`geometry`/`geom`/`wkt`). **Isso é um comportamento importante a documentar**:
   uma fonte cadastrada sem nenhum de-para configurado não é bloqueada — ela passa por
   inteiro para a Silver sem sanitização, sem checagem de obrigatoriedade, sem nada. Na
   prática, isso preserva o fluxo funcionando antes de alguém configurar as regras, mas
   também significa que "esquecer" de cadastrar o mapeamento de uma fonte nova não gera
   nenhum aviso — os dados simplesmente entram sem qualidade controlada.
3. **Com mapeamento cadastrado**, para cada coluna mapeada (linhas 134-196), roda em
   sequência:
   - **`SanitizadorEntrada.validar`** sempre primeiro (nunca pulado) — trata falsos
     nulos (`"NULL"`, `"N/A"`, `"-"`, string vazia/só espaço → `null` real) e gera
     `CAMPO_OBRIGATORIO_AUSENTE` se a coluna é `obrigatorio=true` e o valor sanitizado é
     nulo.
   - Depois, a **cadeia de `ValidadorCampo`** injetada pelo Spring como `List<ValidadorCampo>`
     (Strategy/Chain of Responsibility — cada implementação decide sozinha via
     `suporta(contexto)` se se aplica àquele campo, olhando as flags do
     `mapeamento_coluna`: `validarCpfCnpj`, `validarGeometria`, `validarRecortePr`,
     `tabelaReferencia`/`referenciaEntidadeSilver` preenchidos, `valorMinimo` != null,
     `impedirRegressao`).
4. **Fail-fast por registro, não por campo**: no primeiro validador que reprova um
   campo, o `break` (linha 175 e 184) interrompe a validação **do registro inteiro** —
   os demais campos daquele registro não chegam a ser checados. Consequência prática:
   se um registro tem 3 problemas, a Quarentena só grava o **primeiro** encontrado (na
   ordem em que as colunas foram mapeadas) — não a lista completa de problemas daquele
   registro. É uma limitação real de diagnóstico: o analista corrige o primeiro erro,
   reenvia, e pode descobrir um segundo problema só na tentativa seguinte.
5. Campo do tipo `GEOMETRIA` (`destino_tipo`) não vai para `atributosTransformados` —
   vira a `geometriaWktFinal` do registro (linha 188-191), separada dos atributos
   tabulares.

**Os 6 validadores especializados registrados hoje** (todos em
`br.gov.sp.cps.datagis.validacao`, cada um decidindo sua própria ativação via
`suporta()`):

| Validador | Ativa quando (`suporta`) | Verifica |
|---|---|---|
| `SanitizadorEntrada` | sempre (é chamado à parte, não faz parte do `List<ValidadorCampo>` percorrido na cadeia — ver nota abaixo) | Falso nulo + obrigatoriedade |
| `ValidadorCpfCnpj` | `validarCpfCnpj=true` ou `tipoDado` em `cpf`/`cnpj`/`cpf_cnpj` | Dígito verificador módulo 11, rejeita sequência repetida |
| `ValidadorDominio` | `tabelaReferencia` preenchida | Valor existe como registro ativo na tabela de referência da Silver (com normalização de acento/separador/sinônimo do SICAR) |
| `ValidadorIntegridadeCruzada` | `referenciaEntidadeSilver` preenchida | Chave existe numa entidade canônica da Silver (ex.: `municipio.cod_ibge`) |
| `ValidadorFaixaMinima` | `valorMinimo` != null | Valor numérico ≥ mínimo, com parsing tolerante (`R$ 1.250,50`, `15,5`, etc.) |
| `ValidadorGeometria` | `validarGeometria=true` | WKT/GeoJSON válido, topologia (`IsValidOp`), tipo primitivo esperado, opcionalmente dentro do recorte do Paraná |
| `ValidadorRegressao` | `impedirRegressao=true` (default) | Campo que já veio preenchido antes não pode voltar a `null` |

**Nota sobre o Sanitizador**: apesar de implementar `ValidadorCampo`, o
`OrquestradorValidacao` chama `sanitizador.validar(...)` **explicitamente antes** do
loop pela lista de validadores (linha 143), e depois **pula** a própria instância do
sanitizador dentro do loop (`if (validador == sanitizador) continue;`, linha 160-161) —
o Spring injeta a mesma lista `List<ValidadorCampo>` que inclui o Sanitizador (é um
`@Component` como os outros), então esse `continue` existe especificamente para não
rodá-lo duas vezes.

### 8.5. Persistência na Silver — `PersistidorSilverService`

[validacao/PersistidorSilverService.java](src/main/java/br/gov/sp/cps/datagis/validacao/PersistidorSilverService.java)
recebe a lista de `RegistroAprovadoSilver` e grava. Pontos técnicos que valem registro:

- **Abandona JPA/Hibernate deliberadamente** — usa `JdbcTemplate`/`PreparedStatement`
  cru (motivo já documentado no CLAUDE.md §9.27: `MERGE INTO` com `SDO_GEOMETRY` e CLOB
  grande batia em bug do driver Oracle JDBC 23ai quando passado pelo caminho
  `ps.getParameterMetaData()` do Spring JDBC padrão).
- **Um `PreparedStatement` só, reaproveitado pro lote inteiro** (linha 115: `con.prepareStatement(sql)`
  fora do loop) — evita recompilar a mesma query `MERGE` centenas/milhares de vezes.
- **Upsert por chave natural via `MERGE INTO ... WHEN MATCHED / WHEN NOT MATCHED`** —
  uma query por entidade (`imovel_rural`, `municipio`, `area_protegida`,
  `auto_infracao`, `embargo_ambiental`, `desmatamento`, `foco_calor`), cada uma com a
  chave de match própria (`cod_imovel` para imóvel, `cod_ibge` para município,
  `(numero_termo, orgao_id)` para embargo, etc. — linhas 445-884). **Silver é
  current-state**: um "UPDATE" sobrescreve o valor anterior, não gera histórico (ver
  ressalva na seção 8.7 sobre isso interagir com a regressão).
- **Geometria**: o WKT vira `SDO_GEOMETRY(wkt, 4674)` direto no SQL (`CASE WHEN s.wkt IS
  NOT NULL THEN SDO_GEOMETRY(...) ELSE NULL END`) — e no `UPDATE`, usa
  `COALESCE(s.geometria, t.geometria)` (ex.: linha 492) para **nunca apagar uma
  geometria existente com um valor nulo que chegou depois** — só substitui se o novo
  valor não for nulo.
- **Resolução de FK com cache em memória, não round-trip por registro**:
  - `refCache` (`ConcurrentHashMap<String, Map<String,Long>>`) — carrega uma tabela de
    referência inteira (`situacao_imovel`, `condicao_imovel`, `tipo_imovel`, `orgao`,
    `bioma` etc.) na primeira vez que é usada, e resolve o resto em memória
    (`resolverIdReferencia`, linhas 952-1001) com 5 estratégias em cascata: id numérico
    direto → busca exata → sem acento → variação de separador → só alfanumérico →
    sinônimo/sub-estado do SICAR (`ValidadorDominio.resolverSinonimosConhecidosEstatico`).
  - `municipioCache` — carrega **todo** o catálogo de municípios uma vez
    (`carregarCacheMunicipiosSeNecessario`, linhas 1048-1076), indexado por código IBGE
    **e** por nome normalizado; é a otimização registrada no CLAUDE.md §10.5 (reduziu o
    tempo de promoção de 4.595 registros de ~35-50s para ~18s).
- **CLOB manual para string > 2000 caracteres** (linhas 923-933) — necessário porque
  `atributos_origem` (o payload original inteiro) e o WKT de polígonos complexos do CAR
  regularmente passam de 20 mil caracteres; `setString` do driver Oracle tem limite de
  `VARCHAR2` (4000 bytes) mesmo em coluna `CLOB` da tabela, então precisa criar um
  `java.sql.Clob` explícito via `connection.createClob()` e liberar (`clob.free()`) no
  `finally`.
- **Linhagem em toda linha**: `fonte_dado_id`, `lote_ingestao_id`, `registro_bruto_id` e
  `hash_origem` (do `registro_bruto.hash_registro`) são gravados em toda tabela Silver —
  é o que sustenta a promessa central do projeto ("comprovar que uma informação é
  verdadeira", CLAUDE.md §1): de qualquer linha da Silver dá pra rastrear o lote e o
  registro bruto exatos que a originaram.
- **Sem entidade Silver correspondente → cai em `imovel_rural`** (`extrairParametrosMerge`/
  `obterSqlMerge`, cláusula `default`, e `persistirGenerico` com log de `warn`) — mais um
  ponto de "fail-open silencioso" a se ter em mente: uma entidade mal resolvida não
  quebra o pipeline, mas pode gravar dado no lugar errado sem erro visível.

### 8.6. Persistência na Quarentena — `PersistidorQuarentenaService`

[validacao/PersistidorQuarentenaService.java](src/main/java/br/gov/sp/cps/datagis/validacao/PersistidorQuarentenaService.java)
é bem mais simples — não faz upsert (é sempre `INSERT`, cada rejeição é um evento novo,
não um estado atual) na tabela espelho `q_<entidade>` (`resolverTabelaEspelho`, prefixo
`q_` automático). Grava `registro_bruto_id`/`lote_ingestao_id`/`fonte_dado_id` (mesma
linhagem da Silver), o `payload_original` inteiro, `tipo_erro_id` (resolvido por cache,
igual ao padrão do `PersistidorSilverService`), `campo_problema`, `valor_recebido` e
`motivo_rejeicao` (truncados em 4000/500 caracteres — `truncar`, linha 138-141) e
`status_analise_id = PENDENTE`. **Uma falha ao inserir um registro individual só loga
erro e continua o loop** (linhas 82-86) — não derruba a persistência dos demais
rejeitados do lote.

### 8.7. A regra All-or-Nothing, com precisão de código

Já discutida em outras seções deste documento, mas o texto exato está em
`TransicaoBronzeSilverService.processarLote` (linhas 118-159):
`resOrquestracao.temErros()` — **um único registro rejeitado já é suficiente** para: (a)
persistir **todos** os rejeitados na Quarentena, (b) marcar o lote inteiro como
`RETIDO_QUARENTENA`, e (c) **não chamar `persistirAprovados` nenhuma vez** — mesmo que
99% dos registros do lote tivessem passado limpo. Só no caminho contrário (`rejeitados`
vazio) é que `persistirAprovados` roda, para o lote inteiro.

### 8.8. Ações do analista — liberar e descartar lote retido

`liberarLoteParaSilver` (linhas 166-203) e `descartarLote` (linhas 209-223) são os dois
únicos jeitos de sair do estado `RETIDO_QUARENTENA`, disparados pelos endpoints
`POST /api/quarentena/lotes/{id}/liberar` e `.../descartar` (chamados pela
`LotesView.vue`, já confirmado funcional na análise anterior).

**Um detalhe de comportamento que vale documentar com precisão, porque é sutil e não
está explícito em nenhum outro lugar do CLAUDE.md**: `liberarLoteParaSilver`
**reprocessa o lote do zero** (chama `orquestradorValidacao.orquestrarLote` de novo,
contra os mesmos `registro_bruto` — não há edição de dado bruto pela tela, só correção
de cadastro de domínio/mapeamento que muda o resultado da validação) e:

- Promove pra Silver **só** os que passam dessa vez (`res.aprovados()`) — **sem
  reaplicar a regra All-or-Nothing**. Se ainda sobrar 1 registro reprovado depois da
  "liberação", ele simplesmente não é promovido (fica de fora, silenciosamente, dos que
  vão pra Silver).
- Mas a atualização de status na Quarentena (`atualizarStatusQuarentenaPorLote`, linha
  194) é um `UPDATE ... WHERE lote_ingestao_id = ?` **sem filtrar por registro
  individual** — marca **todas** as linhas de quarentena daquele lote como
  `CORRIGIDO_REPROCESSADO`, inclusive as que, na nova rodada de validação, **ainda**
  falharam e não foram promovidas. **Isso bate exatamente com o que o CLAUDE.md §9.27
  registrou como resultado real** (lote #45: "2 registros retidos por auto-interseção
  atualizados para `CORRIGIDO_REPROCESSADO`" — ou seja, aqueles 2 continuaram de fora da
  Silver, mas o rótulo de quarentena mudou pra "corrigido" mesmo assim). Não é um bug
  que quebra o sistema, mas é uma inconsistência de rótulo que vale a equipe ter
  documentada: hoje, "liberar lote" não garante que todo item marcado
  `CORRIGIDO_REPROCESSADO` de fato chegou na Silver — só o lote, como conjunto, saiu do
  estado de bloqueio.
- `descartarLote` é mais simples e consistente: não reprocessa nada, só marca a
  Quarentena inteira do lote como `DESCARTADO` (com o motivo do analista concatenado ao
  `motivo_rejeicao` existente) e o lote como `STATUS_DESCARTADO` — nenhum registro sobe
  pra Silver, coerente com a intenção.

### 8.9. Resumo das limitações técnicas já conhecidas (consolidando com as seções 1-3)

- Roda **síncrono e transacional**, sem chunk/retry nativo do Spring Batch (mesmo gap já
  registrado na seção 3).
- **Fail-fast por registro** esconde problemas além do primeiro erro de cada linha
  (8.4) — o analista pode ter que reenviar mais de uma vez pra descobrir todos os
  problemas de um mesmo registro.
- **Fonte sem mapeamento cadastrado passa sem validação nenhuma** (8.4) — silencioso,
  sem aviso.
- **`liberarLoteParaSilver` pode rotular como `CORRIGIDO_REPROCESSADO` registros que
  continuam de fora da Silver** (8.8) — desalinhamento entre o rótulo de quarentena e o
  destino real do dado.
- **Silver é current-state (upsert), sem histórico de versão** — um `UPDATE` sobrescreve
  o valor anterior; não há como consultar "qual era o valor deste campo há 2 lotes atrás"
  a partir da própria Silver (só reconstruindo pela Bronze, registro a registro).

---

## Tabela de rastreabilidade (evidência por resposta)

| Item verificado | Arquivo(s) |
|---|---|
| Ausência de Silver→Gold real | `db/migration/seed-demo/V023__seed_demo_gold.sql` (cabeçalho "FALSOS"); ausência de classes `IndicadorVersao`/`RegraCalculo`/`IndicadorResultado` em `src/main/java` |
| `TipoIndicador` é só catálogo | [gold/TipoIndicador.java](src/main/java/br/gov/sp/cps/datagis/gold/TipoIndicador.java), [gold/TipoIndicadorController.java](src/main/java/br/gov/sp/cps/datagis/gold/TipoIndicadorController.java) |
| Cadastro de fonte funcional | [controle/FonteDadoController.java](src/main/java/br/gov/sp/cps/datagis/controle/FonteDadoController.java), `datagis-frontend/src/components/CadastroFontes.vue` |
| Cadastro de regras/mapeamento funcional | [controle/MapeamentoColunaController.java](src/main/java/br/gov/sp/cps/datagis/controle/MapeamentoColunaController.java), `datagis-frontend/src/components/ModalMapeamentoColunas.vue` |
| Cron agora ligado ao Quartz (desde 2026-09-21) | [ingestao/AgendadorInicializacaoRunner.java](src/main/java/br/gov/sp/cps/datagis/ingestao/AgendadorInicializacaoRunner.java), [ingestao/IngestaoAgendadaJob.java](src/main/java/br/gov/sp/cps/datagis/ingestao/IngestaoAgendadaJob.java), [config/QuartzConfig.java](src/main/java/br/gov/sp/cps/datagis/config/QuartzConfig.java), `db/migration/quartz/V034__criar_tabelas_quartz.sql`, `db/migration/controle/V035__preencher_cron_expressao_fontes.sql` |
| Endpoint de leitura novo sobre `gold.imovel_publicado` (não é cálculo) | [gold/ImovelPublicadoController.java](src/main/java/br/gov/sp/cps/datagis/gold/ImovelPublicadoController.java), [gold/ImovelPublicadoService.java](src/main/java/br/gov/sp/cps/datagis/gold/ImovelPublicadoService.java) |
| Regra all-or-nothing por lote | [validacao/TransicaoBronzeSilverService.java](src/main/java/br/gov/sp/cps/datagis/validacao/TransicaoBronzeSilverService.java) |
| Ausência de `pipeline_execucao` | busca em `db/migration/**` e `src/main/java/**`: nenhuma ocorrência |
| Ausência de Job/Step Spring Batch | busca por `JobBuilder`/`StepBuilder`/`extends Tasklet` em `src/main/java`: nenhuma ocorrência |
| 4 camadas + controle no schema Oracle | `db/migration/bronze`, `db/migration/silver`, `db/migration/gold`, `db/migration/quarentena`, `db/migration/controle` |
| Nova peça `Conjunto`, não integrada ao pipeline | [controle/Conjunto.java](src/main/java/br/gov/sp/cps/datagis/controle/Conjunto.java), `docs/pr-lizards-8.md` |
| Zero PL/SQL (procedure/function/package) no projeto hoje | busca por `CREATE PROCEDURE`/`CREATE FUNCTION`/`CREATE PACKAGE` em `db/migration/**`: nenhuma ocorrência |
| `tem_fonte=1` já separa ISAP/IAE/IDesmat/IFC (calculáveis) de ICV/IRL/IAPP (bloqueados) | [gold/V016__gold_indicadores.sql](src/main/resources/db/migration/gold/V016__gold_indicadores.sql) (linhas 16-22, seed de `tipo_indicador`); `docs/checklist-cobertura-fontes.md` (gaps de APP/RL do CAR) |
| Colunas Silver por indicador (o que existe vs. o que falta) | [silver/V012__silver_tabelas_fato.sql](src/main/resources/db/migration/silver/V012__silver_tabelas_fato.sql) |
| Zero uso de Oracle Spatial em PL/SQL/SQL (`SDO_GEOM.*`, `SDO_ANYINTERACT`, `SDO_INTERSECTION`) | busca em `db/migration/**` e `src/main/java/**`: nenhuma ocorrência |
| View pronta para o cálculo (`vw_restricao_territorial`) | [silver/V014__silver_view_restricao_territorial.sql](src/main/resources/db/migration/silver/V014__silver_view_restricao_territorial.sql) |
| Gatilho Bronze→Silver dentro da própria gravação do lote (síncrono) | [ingestao/LoteIngestaoWriter.java](src/main/java/br/gov/sp/cps/datagis/ingestao/LoteIngestaoWriter.java) (linhas 176-183, 298-305) |
| Deduplicação por chave natural com precedência AT>CA/PE, lote mais recente, id mais recente | [validacao/DeduplicadorRegistros.java](src/main/java/br/gov/sp/cps/datagis/validacao/DeduplicadorRegistros.java) (linhas 139-168) |
| Motor de validação campo a campo, fail-fast por registro | [validacao/OrquestradorValidacao.java](src/main/java/br/gov/sp/cps/datagis/validacao/OrquestradorValidacao.java) (linhas 98-201) |
| Fallback sem validação quando a fonte não tem mapeamento cadastrado | [validacao/OrquestradorValidacao.java](src/main/java/br/gov/sp/cps/datagis/validacao/OrquestradorValidacao.java) (linhas 109-132) |
| Persistência Silver via `MERGE INTO` + JDBC cru (não JPA) | [validacao/PersistidorSilverService.java](src/main/java/br/gov/sp/cps/datagis/validacao/PersistidorSilverService.java) |
| Persistência Quarentena via `INSERT` nas tabelas `q_*` | [validacao/PersistidorQuarentenaService.java](src/main/java/br/gov/sp/cps/datagis/validacao/PersistidorQuarentenaService.java) |
| `liberarLoteParaSilver` marca `CORRIGIDO_REPROCESSADO` mesmo em registros ainda rejeitados | [validacao/TransicaoBronzeSilverService.java](src/main/java/br/gov/sp/cps/datagis/validacao/TransicaoBronzeSilverService.java) (linhas 166-203); confirmado por CLAUDE.md §9.27 (lote #45) |

---

## Sugestões de próximos passos (só para registro — não solicitado, não implementado)

Estas são observações de onde o "fio fica solto" hoje, para a equipe priorizar como
achar melhor — **nenhuma delas foi implementada nesta sessão**:

1. Motor de cálculo Silver→Gold via procedures PL/SQL (ver seção 5) — decidir o desenho
   final das procedures, criar os índices espaciais que ainda faltam
   (`imovel_rural.geometria` e as tabelas por trás de `vw_restricao_territorial`),
   implementar `pkg_indicadores` e substituir/remover o seed `V023` quando o cálculo
   real existir.
2. ~~Continuar a frente de agendamento (Quartz) já em andamento~~ — **concluído em
   2026-09-21** (`LIZARDS-10`, PR #15; texto das seções 2 e 3 já revisado em
   2026-09-22). Restam os 3 itens em aberto documentados na seção 2: sincronizar
   trigger sem exigir restart, remover trigger ao desativar/apagar cron, e preencher
   `proxima_execucao_em`.
3. Esclarecer a relação entre `Conjunto` e `LoteIngestao` antes de continuar investindo
   na tela `/conjuntos`.