# Silver → Gold via PL/SQL: cálculo dos indicadores e publicação

> Documento de **design** — escrito antes do desenvolvimento, **atualizado em
> 2026-10-04/05 após a implementação real** pra registrar o que foi confirmado, o que
> mudou no caminho e o que ainda segue em aberto. As seções abaixo mantêm o raciocínio
> original (útil pra quem chega depois entender o *porquê*); onde a realidade divergiu
> da proposta, há uma nota `[CONFIRMADO]`/`[MUDOU]`/`[AINDA EM ABERTO]` ao lado.
> Relacionado: `CLAUDE.md` §2.6 (Gold) e §9.33 (implementação, achado crítico de índice
> espacial, reparo de geometria), `docs/modelagem-silver-gold-quarentena.md` §5 (schema
> da Gold já aplicado — `V016`/`V018`/`V019`/`V020`). Tasks de origem: `LIZARDS-31`
> (Conservação), `LIZARDS-32` (Territorial), `LIZARDS-33` (Ambiental).

**Status:** [IMPLEMENTADO — `V043`-`V047` (packages), `V049` (índices espaciais),
`V050`/`V051` (correções de quarentena achadas testando contra dado real). 4 dos 7
indicadores calculam de verdade (ISAP, IAE, IDesmat, IFC); ICV/IRL/IAPP seguem stub,
ver §2.]

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

**[CONFIRMADO — bateu com a proposta, com 1 ajuste]**

```sql
PROCEDURE calcular_isap(
    p_cod_imovel          IN  VARCHAR2,  -- 1 imovel por chamada (ver nota abaixo)
    p_regra_calculo_id    IN  NUMBER,
    p_indicador_versao_id OUT NUMBER     -- NULL se o indicador nao for calculavel p/ esse imovel (stub)
);
```

- **Mudou em relação à proposta:** `p_cod_imovel IS NULL` pra "recalcula todos" **não
  foi implementado** — o laço "pra cada imóvel, chama os 4 `calcular_*` reais, publica"
  ficou no **Java** (`GoldPublicacaoService.publicarTudo()`), não dentro da procedure.
  Motivo prático, achado ao escrever: `pkg_gold_publicacao` não pode chamar
  `pkg_calculo_territorial`/`pkg_calculo_ambiental` (que chamam de volta
  `registrar_resultado`) sem criar dependência circular entre packages — o Java já tem
  esse papel de orquestrador no resto do projeto (Quartz/`AgendadorInicializacaoRunner`),
  então foi mais simples manter o padrão do que resolver a circularidade em PL/SQL puro.
- Resiliência por imóvel confirmada, mas também no nível certo: `publicarTudo()` captura
  exceção por imóvel no **Java** (não dentro da procedure) e segue pro próximo — mesmo
  resultado prático do §3.3 original.
- Erros reais acabam no log da aplicação (`GoldPublicacaoService`), não numa tabela
  própria de log de execução — não foi necessário criar uma; o volume (1 chamada HTTP
  manual por vez) não justificou.

### 3.4. Teste

**[CONFIRMADO]** Sem mock pra PL/SQL, exatamente como proposto. `GoldCalculoOracleIT`
(`DATAGIS_ORACLE_IT=true`) popula 1 imóvel + 1 área protegida + 1 embargo + 1
desmatamento + 2 focos de calor sintéticos (geometrias conhecidas, ex.: UC cobrindo
exatamente metade do imóvel → ISAP esperado ≈50%), chama `GoldPublicacaoService.
recalcularImovel(...)` (mesmo caminho do endpoint HTTP) e confere os 4 indicadores reais
+ a publicação do Bloco 3. 2/2 passando, validado contra dado sintético e, à parte,
contra os 4.608 imóveis reais do CAR/PR via `POST /api/gold/publicar` — ver CLAUDE.md
§9.33 pros números finais.

---

## 4. `hash_versao` — fechando o ponto em aberto do `CLAUDE.md` §2.6

**[CONFIRMADO — implementado exatamente como proposto abaixo, incluindo a recomendação
do último item]** `pkg_gold_publicacao.registrar_resultado` monta a string canônica
**em memória**, a partir dos parâmetros recebidos (não faz o `UPDATE` em 2 passos — era
a opção recomendada no fim desta seção). Bônus descoberto na prática: como o hash
depende só de `cod_imovel`+`regra`+`lotes`+`valor`, recalcular um imóvel sem nenhuma
mudança de dado-fonte devolve o **mesmo** `indicador_versao_id` em vez de duplicar linha
— isso virou a base da idempotência de `publicar_imovel` (achado ao vivo rodando a
publicação completa 2x sobre o mesmo imóvel, ver CLAUDE.md §9.33).

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

**[PARCIALMENTE RESOLVIDO]** Tolerância fixada em `0.00005` (~5m, mesma ordem de
grandeza do `maxAllowableOffset` da ingestão do IAT, CLAUDE.md §9.31) —
`c_tolerancia_mapa` em `pkg_gold_publicacao`. **Validação visual no Leaflet ainda não
foi feita** (não há tela de mapa consumindo `feature_publicada` ainda) — segue como
item em aberto real, só o valor numérico deixou de ser incógnita.

**Achado não previsto nesta seção, descoberto publicando contra dado real:**
`publicar_camada_mapa` nasceu como um único `INSERT...SELECT` por camada — e uma única
geometria ruim (`ORA-13199`, ou simplesmente uma geometria *lenta* de simplificar, não
necessariamente inválida) derrubava a camada inteira (`AREA_PROTEGIDA` publicou 0 de 16
mil features na primeira tentativa real). Reescrita linha a linha com captura de
exceção por feature — mesmo princípio do item 1 acima, aplicado onde a proposta
original não tinha previsto que fosse necessário. Detalhe completo: CLAUDE.md §9.33.

---

## 6. Como é disparado

**[PARCIALMENTE RESOLVIDO]** Disparo manual implementado e é o único que existe hoje:
`POST /api/gold/publicar` (recalcula + publica todos os imóveis + todas as camadas) e
`POST /api/gold/imoveis/{codImovel}/recalcular` (1 imóvel), ambos em
`GoldPublicacaoController`/`GoldPublicacaoService` — exatamente como descrito no fim
desta seção. **O agendamento automático (Job do Quartz, opção A abaixo) não foi
implementado** — decisão consciente de escopo (mesmo espírito da fatia 1 de ingestão,
que também nasceu sem agendamento, CLAUDE.md §5.2), não um esquecimento. Segue em
aberto pra quando publicação periódica virar necessidade real.

Duas opções, mesmo princípio do agendamento de ingestão (CLAUDE.md §3.2):

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

## 7. Dono de cada frente

Implementado e commitado por Fagner (branch `feat/procedures-indicadores`), com os 3
commits divididos pelo conteúdo real de cada task do board — `pkg_gold_publicacao`
(infraestrutura compartilhada) entrou junto do commit de `LIZARDS-33` por ter sido
construída primeiro, junto dos indicadores ambientais:

| Task | Indicadores | Procedures | Commit |
|---|---|---|---|
| `LIZARDS-33` — Ambiental | IDesmat + IFC (reais) | `pkg_calculo_ambiental` + `pkg_gold_publicacao` | `40db286` |
| `LIZARDS-32` — Territorial | IRL/IAPP (stub) + ISAP/IAE (reais) | `pkg_calculo_territorial` | `7c91102` |
| `LIZARDS-31` — Conservação | ICV (stub, sem fonte) | `pkg_calculo_conservacao` | `f7ac6b5` |
| Índice espacial + resiliência de camada (achados testando contra dado real) | — | `pkg_gold_publicacao` (ajustes) + `V049` | `7c91102` (junto do Territorial, onde o problema de performance apareceu primeiro) |

---

## 8. Itens em aberto, resumo

1. ~~Confirmar com o time: Silver→Gold vira mesmo PL/SQL~~ — **feito**, implementado.
2. ~~`hash_versao`: validar a proposta do §4~~ — **confirmado**, implementado como
   proposto (string em memória, sem `UPDATE` em 2 passos).
3. **Disparo: segue só manual** (`POST /api/gold/publicar`). Quartz+Job (recomendado no
   §6) vs. `DBMS_SCHEDULER` continua em aberto pra quando publicação periódica virar
   necessidade — hoje ninguém pediu isso ainda.
4. **Tolerância de generalização de geometria pro mapa**: valor fixado (`0.00005`), mas
   **validação visual no Leaflet ainda não aconteceu** (não há tela de mapa consumindo
   `feature_publicada` hoje).
5. **Conservação (`LIZARDS-31`) e metade de Territorial (IRL/IAPP) seguem sem fonte** —
   stub implementado e commitado, vira cálculo real quando a API SICAR Temas (ou
   alternativa) destravar. Nenhuma mudança desde a análise original.
6. ~~Teste: confirmar que o padrão `*OracleIT` é aceitável~~ — **confirmado**,
   `GoldCalculoOracleIT` segue exatamente esse padrão, 2/2 passando.
7. **Novo, não previsto neste documento original**: `SDO_ANYINTERACT` nunca aciona
   índice espacial no Oracle desta instância (confirmado via `EXPLAIN PLAN` — sempre
   `TABLE ACCESS FULL`). Trocado por `SDO_RELATE(...,'mask=ANYINTERACT')` em toda
   consulta espacial da Gold + índice espacial real criado nas 4 tabelas de restrição
   da Silver (`V049`). Sem isso, calcular ISAP/IAE pra todos os imóveis seria
   inviável em produção (~42min pra 200 imóveis → ~1s). Detalhe completo e as 3
   pegadinhas de privilégio pra criar o índice: CLAUDE.md §9.33.
8. **Novo, não previsto**: `publicar_camada_mapa` precisou ser reescrita linha a linha
   (não mais `INSERT...SELECT` em massa) porque uma única geometria ruim (ou só lenta)
   derrubava a camada inteira. Ver §5 e CLAUDE.md §9.33.
