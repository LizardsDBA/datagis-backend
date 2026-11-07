# Pendências pós-ingestão do Paraná inteiro

> Documento de trabalho, criado em 2026-11-01. Registra tudo que foi corrigido
> diretamente em produção (servidor) durante a ingestão das 17 fontes para o Paraná
> inteiro + primeira publicação Gold nessa escala, o que já está (ou não) refletido no
> git local, e o que ainda falta investigar/corrigir. **Vai sendo alimentado ao longo
> da sessão**, não é um retrato fechado.
>
> Contexto: até esta sessão, o código só tinha sido testado/calibrado contra o circuito
> de homologação (3 municípios, ~4.600 imóveis). A ingestão real do Paraná inteiro
> (~558 mil imóveis) expôs vários limites que nunca apareceram nessa escala menor —
> ver `CLAUDE.md` §9.54 para o primeiro achado dessa categoria (preparo de desmatamento
> sem rastreio de progresso).
>
> **2026-11-07:** repositório atualizado pra `sprint-3`. Trabalho de correção segue na
> branch `fix/paginacao-camadas-gold` (criada a partir de `origin/sprint-3`, carrega os
> 2 commits de fix que estavam em `fix/fuso-horario-containers` + este documento).

---

## 1. Já corrigido e **commitado localmente** (branch `fix/paginacao-camadas-gold`, a partir de `sprint-3`, sem push ainda)

### 1.1. Heap do backend: `-Xmx8g` → `-Xmx16g`
- **Arquivo:** `docker-compose.yml` (serviço `backend`).
- **Sintoma:** `OutOfMemoryError` ingerindo APP Total (IAT/PR, ~382 mil features de
  polígono) — a ingestão GeoJSON busca todas as páginas antes de gravar
  (`PaginadorUrlGeoJson`), e geometria densa nesse volume não cabia em 8g.
- **Aplicado no servidor** via edição direta + `docker compose restart backend`,
  **e também commitado localmente** no mesmo commit do item 1.2.
- Testado contra as 3 fontes do CAR (APP/RL/Vegetação Nativa) depois do fix, sem erro.

### 1.2. `PaginadorUrlGeoJson.MAX_PAGINAS`: 1000 → 4000
- **Arquivo:** `src/main/java/br/gov/sp/cps/datagis/ingestao/PaginadorUrlGeoJson.java`.
- **Sintoma:** trava de segurança contra loop infinito, calibrada só contra o CAR
  (~556 mil registros = ~556 páginas no tamanho cheio), estourava na Vegetação Nativa
  (IAT/PR): geometria mais densa reduzia bastante o tamanho de página via retry,
  consumindo 1000 chamadas HTTP pra só ~371 mil de ~393 mil registros.
- Testado contra as 3 fontes do CAR depois do fix, completou sem erro.
- **Commit local:** `fix<untracked>: heap 8g->16g e MAX_PAGINAS 1000->4000 na ingestao
  GeoJSON` (branch `fix/fuso-horario-containers`). **Não empurrado pro remote ainda** —
  avaliar se abre PR própria (não é sobre fuso horário, o nome da branch não combina
  com o conteúdo) antes de dar push.

---

## 2. Já corrigido **só no servidor**, ainda sem equivalente no git local

### 2.1. URLs de 3 fontes ampliadas pro Paraná inteiro (tinham ficado no circuito antigo)
- **Fontes 7 (ICMBio UCs), 8 (Terras Indígenas FUNAI) e 16 (Focos de Calor IAT/PR)**
  tinham `url_origem` ainda filtrada pelo circuito de homologação (Curitiba/Maringá/
  Ponta Grossa), mesmo depois de outras fontes (4, 5, 6, 11-15) já terem sido
  ampliadas numa sessão anterior.
- **Correção aplicada só via `UPDATE controle.fonte_dado` no banco de produção**, não
  existe migration pra isso. Fonte 7 passou a usar BBOX do Paraná inteiro (o layer é
  nacional, sem campo UF); fontes 8 e 16 passaram a `where=1=1` (serviço já é
  PR-scoped, a amarra era só do filtro extra do circuito).
- **Pendência:** não existe hoje um jeito declarativo/versionado de saber que essas 3
  URLs mudaram — só está no banco de produção. Se algum dia o banco for recriado do
  zero (`docker compose down -v`), essas URLs voltam pro circuito antigo (migrations
  V003/V053 seedam o valor original). **Avaliar se vale uma migration de `UPDATE`
  fixando o valor novo**, ou se o cadastro via tela resolve isso manualmente quando
  precisar.

### 2.2. Limpeza pós-ingestão das 3 fontes nacionais (9, 10, 17)
- Fontes 9 (MMA/CNUC), 10 (SFB/CNFP) e 17 (INPE Queimadas Brasil) são nacionais —
  sem filtro de URL possível (zip/CSV de arquivo único, sem parâmetro de recorte).
- Decisão (explícita do usuário): ingerir como estão e limpar depois via SQL direto,
  em vez de reativar o validador de recorte territorial (removido pela PR #41,
  `951fcfd feat<LIZARDS-49>: Remoção de validação territorial PR` — ver §3.4 abaixo
  sobre esse ponto, que é um tema relacionado mas **separado**, ainda em aberto).
- **Fonte 17**: autofiltrou pela FK de município (município de outro estado não
  existe em `silver.municipio`) — 152.585 brutos → 1.389 promovidos, só PR. Nenhuma
  limpeza manual necessária.
- **Fontes 9 e 10**: `DELETE` manual em `silver.area_protegida` (+ `silver.
  area_protegida_uf`) de tudo que não intersecta o envelope do Paraná
  (`SDO_RELATE(..., 'mask=ANYINTERACT')` contra um retângulo
  `(-55.00,-27.20)-(-47.50,-22.00)`, mesmo envelope que o validador morto usava).
  7.576 linhas fora do PR removidas; sobraram 321 (fonte 9) + 478 (fonte 10).
- **Pendência:** essa limpeza não é repetível automaticamente — se fonte 9/10 forem
  reingeridas no futuro (nova execução manual ou agendada), o lixo nacional volta e
  precisa ser limpo de novo manualmente. Não existe hoje nenhum mecanismo que impeça
  isso de acontecer de novo.

---

## 3. Achados novos, **nada corrigido ainda** (nem servidor, nem git)

### 3.1. `ORA-12899`: `gold.sobreposicao_publicada.nome` estreito demais (`VARCHAR2(300)`)
- **Achado:** 4 de 558.692 imóveis falharam no cálculo de indicadores
  (`gold.publicacao_indicadores_erro`, publicação id 21) com
  `ORA-12899: value too large for column "GOLD"."SOBREPOSICAO_PUBLICADA"."NOME"`,
  valores reais de até 538 caracteres (coluna aceita só 300).
- **Imóveis afetados:** `PR-4109500-FEE376157DEA4FC78F6D0CA471766327`,
  `PR-4109609-1AD8CBBB42D94E8BAF9F9112706EB48A`,
  `PR-4109401-57576DE0E7924D0892B754FEDE3A9B35`,
  `PR-4120903-8912555E66A2493FB164644B0EEC9518`.
- **Causa provável:** nome de sobreposição deve concatenar múltiplas áreas
  protegidas/embargos no mesmo imóvel — nunca atingiu 300 caracteres no circuito de
  homologação (poucas sobreposições por imóvel nesse recorte pequeno).
- **Correção proposta (não aplicada):** migration alargando a coluna (ex.
  `VARCHAR2(600)` ou mais, ou `VARCHAR2(4000)` para não precisar mexer de novo) +
  reprocessar os 4 imóveis (`POST /api/gold/imoveis/{cod}/recalcular`, endpoint já
  existe).

### 3.2. Paginação `OFFSET/FETCH` nas camadas de mapa Gold é catastroficamente lenta em volume real
- **Achado ao vivo:** publicando a camada `IMOVEL_RURAL` (558.692 registros), o
  processamento ficou em ~332 registros/min (~5,5/s) — muitíssimo mais lento que
  `DESMATAMENTO` (38.167 registros a ~930/min). Rodando há mais de 15h, só 55% feito.
  **Atualização 2026-11-02:** confirmado que o ritmo realmente **degrada conforme o
  offset cresce** (consistente com `WINDOW SORT PUSHED RANK` no plano — um Top-N
  limitado por `offset+fetch`, cujo custo cresce com o tamanho do offset, não é um
  sort completo de tudo toda vez como eu tinha hipotetizado inicialmente, mas o
  efeito prático é o mesmo: mais lento nas páginas finais). Entre a checagem de
  01/11 18h10 (306.400 registros) e 02/11 19h25 (538.000 registros), o ritmo caiu
  pra ~153 registros/min (~2,5/s) — quase a metade do ritmo médio anterior. Faltam
  só ~20.700 registros (96,3% feito) — mesmo no ritmo mais lento observado, deve
  terminar em poucas horas. **`FOCO_CALOR` (333.425 registros) é a próxima e última
  camada** — mesmo padrão de paginação, deve sofrer o mesmo problema; geometria de
  ponto é mais barata de converter que polígono, mas o custo do `WINDOW SORT`
  depende da contagem de linhas, não do tipo de geometria, então não dá pra assumir
  que vai ser rápida só por ser ponto.
- **Causa raiz confirmada via `EXPLAIN PLAN`** (consulta real de
  `GoldPublicacaoService.SELECT_POR_CAMADA.get("IMOVEL_RURAL")`, com
  `OFFSET 300000 ROWS FETCH NEXT 500 ROWS ONLY`):
  ```
  WINDOW SORT PUSHED RANK
   HASH JOIN RIGHT OUTER
    ...
    TABLE ACCESS FULL  IMOVEL_RURAL
  ```
  **Toda página faz um `TABLE ACCESS FULL` na tabela inteira + um `WINDOW SORT` de
  TODAS as linhas que passam no `WHERE`, não só das 500 da página.** O `OFFSET` não
  usa nenhum índice pra "pular" direto pro ponto certo — Oracle materializa e ordena
  o conjunto inteiro toda vez, e só depois corta a janela pedida. Resultado: o custo
  por página é aproximadamente constante e **igual ao de escanear a tabela inteira**,
  não cai conforme a tabela "já visitada" deveria estar mais barata. Pra 558k
  registros em páginas de ~500, isso significa algo perto de 1.100 "varreduras
  completas" sucessivas — o mesmo padrão que afeta toda query em
  `SELECT_POR_CAMADA` (usam o mesmo molde `ORDER BY id OFFSET ? ROWS FETCH NEXT ?
  ROWS ONLY`), só que só fica visível em tabelas grandes (por isso MUNICIPIO/
  LIMITE_PR/AREA_PROTEGIDA/EMBARGO, todos pequenos, não mostraram o problema; e
  DESMATAMENTO com 38k já é claramente mais lento proporcionalmente que os outros
  pequenos, mas ainda tolerável; IMOVEL_RURAL com 558k é o primeiro caso realmente
  grave; **FOCO_CALOR (333.425 registros) provavelmente vai ter o mesmo problema**
  quando chegar a vez dela, embora geometria de ponto seja mais barata de converter
  que polígono).
- **Correção proposta (não aplicada, requer cuidado - não mexer com a publicação em
  andamento):** trocar paginação `OFFSET/FETCH` por paginação por chave
  (`WHERE id > :ultimoIdVisto ORDER BY id FETCH FIRST ? ROWS ONLY`) nas 7 entradas de
  `SELECT_POR_CAMADA` em `GoldPublicacaoService`. Com índice em `id` (que já existe,
  é a PK), isso vira um `INDEX RANGE SCAN` direto no ponto certo — custo por página
  deixa de crescer com o offset. Precisa ajustar `publicarCamadaEmParalelo` pra
  carregar "o próximo lote depois do maior id já visto" em vez de "offset da
  iteração N" (hoje o laço usa um contador de offset simples, não guarda o último id).
- **Resultado final (2026-11-02, corrige a hipótese acima sobre o FOCO_CALOR):**
  `IMOVEL_RURAL` terminou em **42h59min** (558.692 registros, polígono).
  `FOCO_CALOR` terminou em **41 segundos** (334.814 registros, ponto) — ordem de
  grandeza menor apesar de ser 60% do volume de linhas de `IMOVEL_RURAL`. Isso
  **refuta** a ideia de que o tempo escala só com a contagem de linhas: o custo real
  do `TABLE ACCESS FULL` + `WINDOW SORT` por página é dominado pelo **custo de
  converter a geometria pra WKT por linha** (`SDO_UTIL.TO_WKTGEOMETRY`), caríssimo
  pra polígono grande e trivial pra ponto. **Conclusão prática pra priorizar o
  fix:** o problema é grave especificamente pra camadas com **muitas linhas E
  geometria complexa** (`IMOVEL_RURAL` é o pior caso do catálogo hoje;
  `AREA_PROTEGIDA` pode chegar nesse patamar se mais fontes nacionais forem
  ingeridas/limpas); camadas de ponto não são urgência, mesmo em volume alto.
- **Não investigado ainda:** se o mesmo padrão de paginação `OFFSET/FETCH` existe em
  algum outro lugar do código fora da publicação Gold (ex.: listagens paginadas de
  tela) — vale um grep geral por `OFFSET ? ROWS FETCH NEXT` depois que a publicação
  Gold atual terminar.

### 3.3. `paralelismo` (6 threads) parece sub-utilizado também na publicação de camadas
- Amostrado `v$session` durante a publicação de `IMOVEL_RURAL`: só 1 sessão
  `CONTROLE` ativa por vez, nas poucas amostras tiradas (mesmo padrão que o achado já
  registrado em `CLAUDE.md` §9.50 sobre `prepararDesmatamentoOtimizado`, que é
  sequencial *de propósito*).
- **Diferente do `prepararDesmatamentoOtimizado`**, a publicação de camada
  (`publicarCamadaEmParalelo`) **é desenhada pra ser paralela de verdade** (usa
  `Executors.newFixedThreadPool(paralelismo, ...)`, mesmo padrão do cálculo de
  indicadores) — então, se a amostragem realmente reflete baixa concorrência, pode
  ser um problema genuíno, não comportamento esperado.
- **Hipótese mais provável agora, depois do achado do item 3.2:** o gargalo real é o
  `TABLE ACCESS FULL` + `WINDOW SORT` do próprio `SELECT` da página (executado **uma
  vez por página, sequencialmente**, antes de distribuir as linhas da página entre as
  threads) — ou seja, mesmo com 6 threads prontas pra trabalhar em paralelo no que
  cada uma recebe, a maior parte do tempo pode estar sendo gasta no SELECT sequencial
  da página em si, que around o pool de threads nem chega a ser o gargalo.
  **Não confirmado** — precisaria medir separadamente o tempo do SELECT da página vs.
  o tempo do processamento paralelo por linha pra saber a proporção real. Like o item
  3.2, resolver a paginação pode já destravar isso sozinho (não dá pra saber até
  medir de novo depois do fix).

### 3.4. Território: validador de recorte PR morto (não é tarefa desta sessão, só registro)
- Já investigado e com plano desenhado antes desta sessão (ver
  `/home/fagner/.claude/plans/gentle-sprouting-crescent.md`, numa branch de teste
  `test-pr-41`, não relacionada à produção). Não mexido nesta sessão porque as
  fontes nacionais (9, 10, 17) foram tratadas por limpeza pós-ingestão manual em vez
  de restaurar o validador (ver item 2.2). Mantido aqui só como referência cruzada —
  **decidir em algum momento se vale restaurar o validador de verdade** (mais
  arquitetural/permanente) em vez de depender de limpeza manual toda vez que essas
  fontes rodarem de novo.

### 3.5. Espaço "fantasma" em `QUARENTENA` (baixa prioridade)
- `dba_segments` mostra ~2,1 GB alocados pro schema `QUARENTENA`, mas há hoje só 2
  linhas de dado real (`DELETE` não devolve extent, só `TRUNCATE`/`shrink` devolve).
  Sobrou do `DELETE` em massa feito antes da reingestão desta sessão.
- **Não urgente** (131 GB livres em disco na época, folga de sobra) — só registrar
  pra não esquecer, caso o espaço vire assunto outra hora. Correção seria
  `ALTER TABLE ... SHRINK SPACE` nas 7 tabelas `q_*`, ou um `TRUNCATE` + reinserção
  (mais arriscado, evitar).

---

## 4. Reconciliação git pendente (não é bug, é higiene)

- **`docker-compose.yml` do servidor diverge do commit local em 1 ponto não
  relacionado aos itens 1.1/1.2 acima:** o fix de fuso horário (`TZ: America/
  Sao_Paulo` nos serviços `oracle` e `backend`) foi aplicado no servidor via `sed`
  direto, **por instrução explícita do usuário de não subir pro git** ("não precisa
  subir pro git, não é produção ainda, estamos validando"). O commit local
  `24f2779 fix<untracked>: fuso America/Sao_Paulo nos containers oracle e backend`
  existe na branch mas não foi dado push, e o arquivo no servidor tem o mesmo
  conteúdo final por coincidência de edição paralela, não por `git pull`.
  **Não mexer nisso sem perguntar** — é uma decisão consciente já tomada, só
  registrando o estado pra não confundir sessões futuras.
- Itens 1.1 e 1.2 (heap + `MAX_PAGINAS`) **estão committed localmente, não
  empurrados**. Esses sim são candidatos reais a virar PR formal quando a publicação
  Gold atual terminar — são fixes de bug de verdade, não preferência de ambiente.

---

## 6. Correção da paginação `OFFSET/FETCH` (item 3.2) — linha de base local

Metodologia (combinada com o usuário, 2026-11-07): publicar a Gold localmente (circuito
de 3 municípios) **antes** da correção, registrar o máximo de detalhe possível, aplicar
a correção, apagar a Gold local e publicar de novo, comparar. Ambiente: máquina de
dev (15 GB RAM, 1 TB disco), branch `fix/paginacao-camadas-gold`. **Ressalva já
registrada antes de medir:** em volume pequeno o ganho absoluto deve ser mínimo (o
problema só fica dramático com muita linha **e** geometria complexa — item 3.2); isso
testa **correção** (mesmo resultado antes/depois) e serve de teste de regressão, não
prova de performance — a prova de performance já está nos números reais de produção
(§3.2) e no `EXPLAIN PLAN`.

### 6.1. Estado da Silver local antes de publicar (2026-11-07 11:22:27 -03:00)

| Tabela | Linhas |
|---|---|
| `silver.municipio` | 399 |
| `silver.imovel_rural` | 4.613 |
| `silver.area_protegida` | 8.377 (inclui dado nacional das fontes 9/10, nunca limpado localmente — ver item 2.2, a limpeza só foi feita em produção) |
| `silver.embargo_ambiental` | 124 |
| `silver.auto_infracao` | 39 |
| `silver.desmatamento` | 1.365 |
| `silver.foco_calor` | 1.389 |

Recursos do host antes de disparar: 15 GB RAM total, ~5,1 GB disponível; Oracle já
usando 5,56 GB, backend 1,13 GB.

### 6.2. Resultado — ANTES da correção (`POST /api/gold/publicar`, 2026-11-07)

Log bruto completo (cada transição de status, com timestamp do host e os campos
`iniciadoEm`/`concluidoEm`/`totalProcessado`/`totalPublicado`/`totalPulado` exatos da
API): `docs/gold_local_baseline_antes.log`.

**Duração total (indicadores + 7 camadas): 16min49s** (11:22:43,037 → 11:39:32,271).

| Fase | Registros | Início | Fim | Duração |
|---|---|---|---|---|
| Indicadores | 4.613/4.613, **0 falha** | 11:22:43,037 | 11:32:40,646 | 9min57,6s |
| MUNICIPIO | 399/399 | 11:32:40,700 | 11:32:45,311 | 4,6s |
| LIMITE_PR | 1/1 | 11:32:45,323 | 11:33:14,810 | 29,5s |
| AREA_PROTEGIDA | 8.377/8.377 (2 pulados) | 11:33:14,894 | 11:38:29,363 | 5min14,5s |
| DESMATAMENTO | 1.365/1.365 | 11:38:29,386 | 11:39:01,207 | 31,8s |
| EMBARGO | 124/124 | 11:39:01,215 | 11:39:01,918 | 0,7s |
| **IMOVEL_RURAL** | 4.613/4.613, 0 pulados | 11:39:01,937 | 11:39:32,066 | **30,1s** |
| FOCO_CALOR | 1.389/1.389 | 11:39:32,086 | 11:39:32,271 | 0,19s |

Recursos do host ao final: RAM disponível caiu pra 4,5 GB (de 5,1 GB), Oracle subiu
pra 5,72 GB, backend pra 1,64 GB — nada alarmante, segue folgado.

**Observações:**
- **Zero falha `ORA-12899`** nos indicadores (item 3.1) — o circuito pequeno não gera
  nome de sobreposição >300 caracteres (poucas sobreposições por imóvel nesse recorte).
  Confirma a hipótese do item 3.1: é um problema de escala, não reproduz localmente.
- **`IMOVEL_RURAL` (a camada mais lenta em produção) levou só 30,1s aqui** contra
  **42h59min em produção** — 4.613 linhas aqui vs 558.692 lá (121x mais linhas em
  produção), mas o tempo cresceu ~5.160x, não 121x. Confirma o crescimento
  super-linear já apontado pelo `EXPLAIN PLAN` (item 3.2) — bate razoavelmente com o
  ajuste de curva feito antes (~N^1,73: 121^1,73 ≈ 4.015x, mesma ordem de grandeza do
  5.160x observado).
- **`AREA_PROTEGIDA` foi a camada mais lenta localmente** (5min14s, 8.377 linhas) —
  mais linhas que `IMOVEL_RURAL` aqui (porque nunca limpamos o dado nacional das
  fontes 9/10 localmente, item 2.2), então é o melhor candidato local pra visualizar
  o ganho da correção, já que tem volume suficiente pra o efeito aparecer.
- **`LIMITE_PR` (29,5s pra só 1 linha publicada) não é afetado pela paginação** — é a
  simplificação do contorno do Paraná inteiro (união dos 399 municípios), sempre o
  mesmo custo independente do circuito de ingestão. Não é alvo da correção.

### 6.3. Resultado — DEPOIS da correção

*(a preencher depois de implementar a paginação por chave e repetir o mesmo
procedimento: apagar a Gold local e publicar de novo)*

## 7. Como usar este documento

Atualizar a cada novo achado, na seção 3 (se ainda não corrigido) ou movendo pra
seção 1/2 assim que algo for de fato corrigido (local ou servidor, anotando onde).
Quando a publicação Gold atual terminar e os itens daqui virarem trabalho de verdade,
este documento vira a base pra abrir a(s) branch(es)/PR(s) correspondentes, seguindo
o fluxo normal do projeto (`CLAUDE.md` §10).
