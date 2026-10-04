# Silver → Gold via PL/SQL: cálculo dos indicadores e publicação

> Documento de **design, anterior ao desenvolvimento** — nada aqui ainda foi
> implementado. Objetivo: decidir a forma antes de escrever a primeira procedure.
> Relacionado: `CLAUDE.md` §2.6 (Gold), `docs/modelagem-silver-gold-quarentena.md` §5
> (schema da Gold já aplicado — `V016`/`V018`/`V019`/`V020`). Tasks de origem:
> `LIZARDS-31` (Conservação) + "Criação de Procedures Territorial" + "Criação de
> Procedures Ambiental".

**Status:** [PROPOSTO — a validar com a equipe antes de codar]

---

## 1. O que está sendo decidido

A transição Bronze→Silver já existe e é **Java** (`PersistidorSilverService`,
`TransicaoBronzeSilverService`, motor de validação em `validacao/` — CLAUDE.md §9.23-25).
A pergunta agora é sobre a **próxima** transição, Silver→Gold (cálculo dos 7 indicadores
+ publicação): fazer em **PL/SQL** (procedures no banco), não em Java.

**Isso é viável — e eu recomendo seguir com essa direção —, mas com os olhos abertos pro
trade-off.** Não é só "dá pra fazer", é genuinely uma boa escolha técnica aqui,
especificamente:

**A favor do PL/SQL:**
- O cálculo é fundamentalmente **geoespacial e set-based**: "pra cada imóvel, interseccione
  com N polígonos de restrição, some área, calcule percentual". Os operadores
  `SDO_GEOM.SDO_INTERSECTION`, `SDO_GEOM.SDO_AREA`, `SDO_ANYINTERACT` são nativos de
  SQL/PL-SQL. Em Java, cada geometria precisaria ser serializada (JDBC `STRUCT`/driver
  espacial), trafegada e desserializada — overhead real em cima de polígonos com
  dezenas de milhares de vértices (o CAR já mostrou isso: WKT de 20k-100k caracteres,
  CLAUDE.md §9.27).
- **Atende literalmente o requisito do desafio** ("rotinas PL/SQL" — slide de RNFs,
  registrado como item em aberto desde a primeira análise do PDF). Hoje o projeto não
  tem nenhuma procedure; isso fecha essa lacuna de forma genuína, não cosmética.
- Mesmo princípio do resto do projeto: ficar perto do dado quando a operação é
  fundamentalmente sobre o dado (mesma lógica que já levou a preferir `SDO_CS.TRANSFORM`
  a GeoTools pra reprojeção, CLAUDE.md §2.5).

**Contra (pra entrar de olhos abertos, não pra desistir):**
- **Curva de aprendizado real.** Ninguém no time escreveu PL/SQL neste projeto ainda —
  todo o resto (motor de validação, 349 testes, ingestão) é Java. Depurar PL/SQL é mais
  arido (sem stack trace, `DBMS_OUTPUT` em vez de breakpoint).
- **Teste automatizado é mais trabalhoso.** O padrão do projeto é teste unitário rápido
  (mock) + um punhado de `*OracleIT` contra Oracle real quando é inevitável (§9.28). Uma
  procedure só dá pra testar contra Oracle real — **não existe mock pra PL/SQL**. A
  cobertura vai ficar mais parecida com os `*OracleIT` (poucos, mas contra o banco de
  verdade) do que com o padrão Mockito de 300+ testes do resto do backend.
- **Migration de procedure precisa do mesmo cuidado que qualquer outra** (CLAUDE.md
  §9.6: nunca editar uma migration já aplicada). `CREATE OR REPLACE PROCEDURE` ajuda —
  rodar de novo não quebra —, mas ainda assim: ajuste depois de aplicada = migration
  nova, não editar a antiga.

**Proposta de divisão do trabalho**, que já bate com as 3 tasks que você mostrou:
Bronze→Silver continua Java (já funciona, não mexer); **Silver→Gold (cálculo +
publicação) passa a ser PL/SQL**, dividido em pacotes por bloco de indicador.

---

## 2. Cobertura real — antes de desenhar qualquer procedure

As 3 tasks cobrem os 7 indicadores, mas **nem todos têm dado pra calcular hoje**. Isso
já está registrado no schema (`gold.tipo_indicador.tem_fonte = 0`) e no doc de
modelagem (§5.1) — reproduzindo aqui porque muda o escopo real de cada task:

| Task (board) | Indicador | Fonte Silver | Calculável hoje? |
|---|---|---|---|
| **Conservação** (`LIZARDS-31`) | ICV — cobertura vegetal nativa | — | ❌ **sem fonte** |
| **Territorial** | IRL — Reserva Legal | — | ❌ **sem fonte** |
| **Territorial** | IAPP — APP conservada | — | ❌ **sem fonte** |
| **Territorial** | ISAP — sobreposição c/ áreas protegidas | `area_protegida` × `imovel_rural` | ✅ |
| **Territorial** | IAE — área embargada | `embargo_ambiental` × `imovel_rural` | ✅ |
| **Ambiental** | IDesmat — desmatamento | `desmatamento` × `imovel_rural` | ✅ |
| **Ambiental** | IFC — focos de calor | `foco_calor` × `imovel_rural` | ✅ |

**O motivo do bloqueio** (já documentado em `docs/checklist-cobertura-fontes.md` e
`docs/fontes-de-dados-v2.md`): ICV, IRL e IAPP dependem de APP/Reserva Legal/vegetação
nativa **declaradas no CAR**, cuja única fonte oficial (`api-sicar-tema`) exige
credenciamento OAuth2 + allowlist de IP no Serpro — fora do alcance da equipe sem apoio
externo da Visiona.

**Consequência pra esse trabalho:**
- **`LIZARDS-31` (Conservação) não tem o que calcular ainda.** Dá pra escrever a
  procedure **vazia/stub** (estrutura pronta, só o corpo do cálculo faltando) pra não
  travar quando a fonte aparecer, mas não há como testar contra dado real hoje.
- **Territorial fica pela metade**: ISAP e IAE têm fonte e dá pra implementar de
  verdade; IRL e IAPP ficam na mesma situação do ICV (stub).
- **Ambiental é a única task 100% executável hoje** (IDesmat e IFC têm fonte completa).

Se isso for novidade pra quem está pegando essas tasks, vale alinhar com o time/board
antes de estimar — "2d 4h" faz sentido pra Ambiental, mas Conservação e metade de
Territorial não têm dado pra validar contra, então "feito" ali significa
"estrutura pronta, aguardando fonte", não "calculando de verdade".

---

## 3. Convenções de procedure para este projeto

Antes de qualquer procedure de verdade, fixar o padrão — pra não cada task inventar o
seu:

### 3.1. Onde moram e como são versionadas

- Migration Flyway, mesmo padrão de tabela: `db/migration/gold/V0NN__<nome>.sql`,
  numeração global zero-padded (CLAUDE.md §2.1.1).
- `CREATE OR REPLACE PROCEDURE` (ou `PACKAGE`/`PACKAGE BODY`) — **idempotente por
  natureza**, então reaplicar a mesma migration não quebra. Mas ajuste **depois** que já
  rodou em algum ambiente = migration nova (`V0NN+1`), nunca editar a `V0NN` antiga
  (mesma regra de sempre, CLAUDE.md §9.6).
- Schema qualificado via placeholder, igual todo o resto do projeto:
  `CREATE OR REPLACE PROCEDURE ${goldSchema}.calcular_isap ...`.

### 3.2. Organização: PACKAGE por bloco de indicador, não procedures soltas

Proposta: em vez de 7+ procedures soltas no schema `gold`, agrupar em **packages**
(unidade de organização do PL/SQL — um "módulo" com cabeçalho público e corpo privado),
um por task do board. Além de ser a prática padrão em Oracle para isso, bate 1:1 com a
divisão de trabalho que vocês já fizeram:

| Package | Task | Procedures públicas |
|---|---|---|
| `pkg_calculo_conservacao` | `LIZARDS-31` | `calcular_icv` (stub até ter fonte) |
| `pkg_calculo_territorial` | Territorial | `calcular_irl`, `calcular_iapp` (stub) · `calcular_isap`, `calcular_iae` (reais) |
| `pkg_calculo_ambiental` | Ambiental | `calcular_idesmat`, `calcular_ifc` |
| `pkg_gold_publicacao` | compartilhado pelas 3 | `publicar_imovel`, `publicar_camada_mapa` — ver §5 |

Cada package ganha sua própria migration (`PACKAGE` + `PACKAGE BODY`, pode ser o mesmo
arquivo ou dois). Isso também facilita 3 pessoas trabalharem em paralelo sem pisar na
mesma migration.

### 3.3. Assinatura padrão de uma procedure de cálculo

Proposta de contrato comum (a confirmar quando a primeira for escrita de verdade):

```sql
PROCEDURE calcular_isap(
    p_cod_imovel        IN  VARCHAR2,   -- NULL = recalcula TODOS os imóveis (uso em lote)
    p_regra_calculo_id  IN  NUMBER,
    p_indicador_versao_id OUT NUMBER    -- ou 0/NULL se o indicador nao for calculavel p/ esse imovel
);
```

- Uma chamada por imóvel OU um laço interno quando `p_cod_imovel IS NULL` (cálculo em
  lote, usado pela primeira publicação / republicação geral).
- Não aborta a execução inteira por causa de 1 imóvel com problema (mesmo princípio da
  ingestão — geometria malformada não derruba o lote, CLAUDE.md §5.8 item 2): captura
  exceção por imóvel, loga, segue para o próximo.
- Erros reais (não "sem dado pra calcular", que é um resultado `NULL` válido) vão para
  uma tabela de log de execução — ver §6.

### 3.4. Teste

Sem mock pra PL/SQL. Caminho: reaproveitar o padrão `*OracleIT` já estabelecido
(CLAUDE.md §9.28) — teste JUnit que sobe contra o Oracle real do compose
(`DATAGIS_ORACLE_IT=true`), popula um imóvel + uma área protegida de teste na Silver,
chama a procedure via `CallableStatement`, confere o resultado na Gold, limpa no final.
Mesma disciplina do `IngestaoAgendadaOracleIT`: só roda com a flag, não quebra o
`mvn test` de quem não tem Oracle de pé.

---

## 4. `hash_versao` — fechando o ponto em aberto do `CLAUDE.md` §2.6

As procedures de cálculo **não têm como gravar `indicador_versao` sem isso** — a coluna
é `NOT NULL UNIQUE`. Proposta concreta (até agora só "precisa amarrar dado-fonte +
regra + resultado", sem algoritmo):

```sql
-- String canonica, ordem deterministica:
v_base := p_cod_imovel || '|' ||
          p_regra_calculo_id || '|' ||
          -- lotes de ingestao que entraram no calculo, ordenados
          (SELECT LISTAGG(lote_ingestao_id, ',') WITHIN GROUP (ORDER BY lote_ingestao_id)
             FROM gold.indicador_versao_fonte WHERE indicador_versao_id = v_id) || '|' ||
          -- cada resultado, ordenado por tipo_indicador_id
          (SELECT LISTAGG(tipo_indicador_id || ':' || valor || ':' || area_afetada_ha, ';')
                  WITHIN GROUP (ORDER BY tipo_indicador_id)
             FROM gold.indicador_resultado WHERE indicador_versao_id = v_id);

v_hash := RAWTOHEX(DBMS_CRYPTO.HASH(UTL_I18N.STRING_TO_RAW(v_base, 'AL32UTF8'), DBMS_CRYPTO.HASH_SH256));
```

- `DBMS_CRYPTO.HASH_SH256` → SHA-256, mesmo algoritmo já usado em Java pro
  `hash_conteudo`/`hash_registro` (consistência entre as duas transições).
- `RAWTOHEX` devolve hex de 64 caracteres — bate com `VARCHAR2(64)` já na DDL.
- **[PROPOSTO, a confirmar]**: isso só é calculável **depois** de gravar os resultados
  (precisa do `indicador_versao_id` pra montar o `LISTAGG`). Ou grava em 2 passos
  (insere `indicador_versao` com hash provisório, grava resultados, faz `UPDATE` do
  hash de verdade), ou monta a string **antes** de persistir, a partir das variáveis em
  memória da procedure (mais simples, evita o `UPDATE`). Recomendo a segunda opção.

---

## 5. Publicação — não é só os 7 números

Você pediu pra cobrir isso explicitamente: calcular o indicador é só o Bloco 1
(`indicador_versao`/`indicador_resultado`). A publicação de verdade (o que as telas
leem — `docs/modelagem-silver-gold-quarentena.md` §5-bis) envolve **3 blocos**, e os 3
também viram responsabilidade das procedures nessa decisão:

| Bloco | Tabelas | O que falta decidir |
|---|---|---|
| 1 — Indicadores | `indicador_versao`, `indicador_resultado`, `indicador_versao_fonte` | Coberto pelas procedures de cálculo (§3.3) |
| 2 — Camadas de mapa | `mapa_camada` (já semeada), `feature_publicada` | `publicar_camada_mapa`: copia geometria da Silver **generalizada** (Douglas-Peucker — `SDO_UTIL.SIMPLIFY`) pra cada fenômeno. Roda independente do cálculo de indicador (município/UC/embargo aparecem no mapa mesmo sem imóvel cruzando) |
| 3 — Detalhe congelado | `imovel_publicado`, `sobreposicao_publicada` | `publicar_imovel`: roda **depois** do cálculo (§3.3), congela geometria do imóvel + os 7 valores denormalizados + cada interseção com `geometria_intersecao` em precisão total |

**Ordem de execução proposta** (pacote `pkg_gold_publicacao`, orquestrador comum):
1. `pkg_calculo_*.calcular_*` (cada indicador, na ordem que existir dado)
2. `pkg_gold_publicacao.publicar_imovel` — usa os resultados do passo 1, grava Bloco 3
3. `pkg_gold_publicacao.publicar_camada_mapa` — independente, roda sempre que a Silver
   daquele fenômeno mudar (não precisa esperar cálculo de indicador)

**[EM ABERTO]** `SDO_UTIL.SIMPLIFY` tem uma tolerância (equivalente ao
`maxAllowableOffset` já usado na ingestão do IAT, CLAUDE.md §9.31) — qual valor usar
pra generalização do mapa? Mesma ordem de grandeza da ingestão (~5m) é o ponto de
partida razoável, mas precisa validar visualmente no Leaflet antes de fixar.

---

## 6. Como é disparado

**[EM ABERTO — decisão de arquitetura, não só de PL/SQL]** Duas opções, mesmo
princípio do agendamento de ingestão (CLAUDE.md §3.2):

- **A — Quartz chama a procedure** (mesmo padrão do `IngestaoAgendadaJob`, §9.28): um
  `Job` novo (`PublicacaoGoldJob`) que abre um `CallableStatement` e chama
  `pkg_gold_publicacao.publicar_tudo`. Mantém a consistência arquitetural — Quartz
  continua sendo o único "quando" do sistema.
- **B — `DBMS_SCHEDULER` direto no Oracle.** Mais nativo a PL/SQL, mas introduz um
  **segundo** mecanismo de agendamento no projeto (o primeiro é o Quartz), quebrando a
  decisão de centralizar "quando" num lugar só.

**Recomendo A** — consistência com o que já existe pesa mais que a "pureza" de manter
agendamento dentro do banco. Mas registrando a alternativa porque é uma decisão de
arquitetura de verdade, não só estilo.

Disparo manual (sem esperar o cron) também precisa existir, mesmo princípio do
`POST /api/fontes/{id}/executar`: um endpoint tipo `POST /api/gold/publicar` ou
`POST /api/gold/imoveis/{codImovel}/recalcular`.

---

## 7. Dono de cada frente (preencher)

| Task | Indicadores | Procedures | Responsável |
|---|---|---|---|
| `LIZARDS-31` — Conservação | ICV (stub, sem fonte) | `pkg_calculo_conservacao` | — |
| Territorial | IRL/IAPP (stub) + ISAP/IAE (reais) | `pkg_calculo_territorial` | — |
| Ambiental | IDesmat + IFC (reais, as 2 completas) | `pkg_calculo_ambiental` | — |
| Publicação (compartilhado) | Blocos 2 e 3 | `pkg_gold_publicacao` | — |

---

## 8. Itens em aberto, resumo

1. **Confirmar com o time**: Silver→Gold vira mesmo PL/SQL (este doc assume que sim, a
   partir da sua mensagem — formalizar).
2. `hash_versao`: validar a proposta do §4 (string canônica + SHA-256 via
   `DBMS_CRYPTO`) ou ajustar.
3. Disparo: Quartz+Job (recomendado, §6) vs. `DBMS_SCHEDULER`.
4. Tolerância de generalização de geometria pro mapa (§5) — validar visualmente.
5. **Conservação (`LIZARDS-31`) e metade de Territorial (IRL/IAPP) não têm fonte** — a
   task vira "estrutura pronta, stub", não "calculando de verdade", até a API SICAR
   Temas (ou alternativa) destravar.
6. Teste: confirmar que o padrão `*OracleIT` (§3.4) é aceitável pra cobertura de
   procedure, já que não tem equivalente a mock aqui.
