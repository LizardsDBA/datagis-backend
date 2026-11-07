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
- **Corrigido em `GoldPublicacaoService`** (2026-11-07, branch
  `fix/paginacao-camadas-gold`, `LIZARDS-63`): as 5 queries de `SELECT_POR_CAMADA`
  que sofriam do problema (`MUNICIPIO`, `AREA_PROTEGIDA`, `DESMATAMENTO`, `EMBARGO`,
  `IMOVEL_RURAL`) trocadas de `ORDER BY id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY` pra
  `WHERE ... AND id > ? ORDER BY id FETCH FIRST ? ROWS ONLY` (paginação por chave,
  usa `INDEX RANGE SCAN` na PK). `CAMADA_LIMITE_PR` mantida em `OFFSET/FETCH` de
  propósito (agregado de 1 linha, sem `id` pra comparar, custo irrelevante). Os 2
  laços Java que consomem essas queries foram ajustados pra rastrear o maior `id` já
  visto em vez de um offset: `publicarCamadaEmParalelo` (as 6 camadas de mapa) e
  `prepararDesmatamentoOtimizado` (§9.54 do CLAUDE.md - usa a mesma query de
  `DESMATAMENTO`, ganha o fix de graça mesmo sem a tarefa de rastreio de progresso
  dela ter sido feita). Resultado da medição: ver seção 6.
- **Mesmo padrão achado em outros 3 lugares do código, fora da publicação Gold** —
  `QuarentenaService.java:157` (listagem da Quarentena, `UNION ALL` de várias
  tabelas por `data_quarentena`), `ConjuntosDadosService.java:95` (dados de um lote)
  e `LogAuditoriaRepositorio.java:95` (log de auditoria). **Decisão: não mexer
  agora** — são paginação de **tela interativa** (usuário navega por "página N",
  raramente vai muito fundo, e o `UNION ALL` do primeiro caso não tem uma chave
  natural única pra paginação por chave funcionar direito), bem diferente do caso da
  Gold (consumo sequencial de TODA a tabela, sempre bate no pior caso). Registrado
  aqui só pra não esquecer, caso algum desses vire gargalo real (log de auditoria
  crescendo sem limite é o mais provável de doer no futuro).

### 3.3. `paralelismo` (6 threads) parece sub-utilizado também na publicação de camadas — **[RESOLVIDO, não era bug separado, 2026-11-07]**
- Amostrado `v$session` durante a publicação de `IMOVEL_RURAL`: só 1 sessão
  `CONTROLE` ativa por vez, nas poucas amostras tiradas (mesmo padrão que o achado já
  registrado em `CLAUDE.md` §9.54 sobre `prepararDesmatamentoOtimizado`, que é
  sequencial *de propósito*).
- **Diferente do `prepararDesmatamentoOtimizado`**, a publicação de camada
  (`publicarCamadaEmParalelo`) **é desenhada pra ser paralela de verdade** (usa
  `Executors.newFixedThreadPool(paralelismo, ...)`, mesmo padrão do cálculo de
  indicadores) — então, se a amostragem realmente reflete baixa concorrência, pode
  ser um problema genuíno, não comportamento esperado.
- **Hipótese levantada então:** o gargalo real é o `TABLE ACCESS FULL` + `WINDOW SORT`
  do próprio `SELECT` da página (executado uma vez por página, sequencialmente, antes
  de distribuir as linhas entre as threads) - mesmo com threads prontas, a maior
  parte do tempo seria gasta no SELECT sequencial, não no processamento paralelo.
- **Confirmado ao vivo (2026-11-07), depois da correção de paginação do item 3.2:**
  amostrado `v$session` (consulta corrigida - a primeira tentativa quebrou com
  `ORA-00911` por escaping de `v\$session` num heredoc aninhado, 90 amostras
  inválidas descartadas) durante as 2 fases, localmente com `paralelismo=4`
  (temporário, só pro teste - valor padrão de dev é 2):
  - **Indicadores** (`calcularIndicadoresEmParalelo`): **58 de 60 amostras (97%) com
    as 4 sessões ativas ao mesmo tempo** - paralelismo pleno, nunca foi o problema
    nessa fase.
  - **`AREA_PROTEGIDA`** (camada de mapa, já com a correção de paginação aplicada):
    **24/45 (53%) com 4 ativas, 34/45 (76%) com 3 ou mais** - concorrência real,
    bem diferente do "só 1 sessão" observado em produção antes da correção.
  - **Conclusão: a hipótese estava certa.** O "só 1 sessão ativa" original não era um
    bug de paralelismo separado - era sintoma do próprio item 3.2 (o `SELECT`
    sequencial de cada página, antes da correção, dominava tanto o tempo de parede
    que qualquer amostra pegava quase sempre só essa 1 sessão rodando a busca lenta,
    escondendo o paralelismo real do processamento por linha). Corrigir a paginação
    já destravou isso de graça - não é preciso nenhuma mudança adicional de código
    pra esse item.

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

### 3.6. Publicação Gold travada "em andamento" pra sempre se o backend reiniciar no meio
- **Achado ao vivo** (2026-11-07), não é causado pelas correções de hoje — reiniciei o
  backend local no meio de uma publicação de teste (pra voltar o `paralelismo` ao
  padrão depois de um diagnóstico) e a publicação seguinte foi recusada:
  `{"erro":"Já existe uma publicação para a camada Gold em andamento."}`.
- **Causa:** `GoldPublicacaoController.publicar()` decide se "já tem publicação em
  andamento" só olhando o status gravado no banco
  (`service.obterStatusConsolidado().algumEmAndamento()`) — não existe nenhum
  heartbeat/liveness checando se o processo que gravou aquele "EM_ANDAMENTO" ainda
  está rodando de verdade. Se o backend cai/reinicia no meio (deploy, OOM, container
  recriado - tudo coisa que já aconteceu nesta sessão, inclusive em produção), a
  linha fica "EM_ANDAMENTO" pra sempre em `gold.publicacao_indicadores` ou
  `gold.publicacao_camada_mapa`, e **nenhuma publicação nova consegue começar** até
  alguém corrigir a mão no banco (`UPDATE ... SET status = 'FALHOU' WHERE status =
  'EM_ANDAMENTO'`).
- **Mesma classe de problema já resolvida em outro lugar do projeto:**
  `IngestaoExecucaoService` tinha exatamente esse bug (fonte ficava "em andamento"
  pra sempre até reiniciar o backend) - corrigido na `LIZARDS-54`/§9.47 do
  `CLAUDE.md`. A publicação Gold nunca recebeu o mesmo tratamento.
- **Não corrigido agora** (fora do escopo de hoje, achado de passagem) - desbloqueei
  a mão pra continuar trabalhando. Correção futura: mesmo princípio do fix da
  `LIZARDS-54` (registrar liveness, ou pelo menos limpar/marcar como falha qualquer
  publicação "em andamento" há tempo demais na subida do backend).

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

### 6.3. Resultado — DEPOIS da correção (`POST /api/gold/publicar`, 2026-11-07)

Correção aplicada: `GoldPublicacaoService.SELECT_POR_CAMADA` (as 5 entradas que
sofriam do problema) trocadas de `OFFSET/FETCH` pra paginação por chave (`WHERE id >
? ORDER BY id FETCH FIRST ? ROWS ONLY`); os 2 laços que consomem essas queries
(`publicarCamadaEmParalelo` e `prepararDesmatamentoOtimizado`) ajustados pra rastrear
o maior `id` já visto em vez de um offset. `CAMADA_LIMITE_PR` mantida como estava
(agregado de 1 linha, sem `id`). Suíte completa rodada antes de publicar: **803
testes, 0 falhas** (só o `DatagisApplicationTests` de sempre).

Antes de publicar, a Gold local inteira foi apagada (catálogo preservado:
`mapa_camada`/`regra_calculo`/`tipo_indicador`) e os lotes voltaram de
`PROMOVIDO_GOLD` pra `PROMOVIDO_SILVER`.

Log bruto completo: `docs/gold_local_depois.log`.

**Duração total: 10min38,7s** (11:58:36,656 → 12:09:15,360) — **contra 16min49s
antes, uma redução de 37%** no circuito pequeno.

| Fase | Antes | Depois | Variação |
|---|---|---|---|
| Indicadores | 9min57,6s | 7min54,2s | -20,6% |
| MUNICIPIO | 4,6s | 2,3s | -50% |
| LIMITE_PR | 29,5s | 28,4s | ~igual (não é alvo da correção, por design) |
| **AREA_PROTEGIDA** | 5min14,5s | **1min18,2s** | **-75% (4x mais rápido)** |
| DESMATAMENTO | 31,8s | 32,5s | ~igual (tabela pequena, 1.365 linhas) |
| EMBARGO | 0,7s | 9,8s | +9,1s (ruído — volume irrisório, 124 linhas) |
| **IMOVEL_RURAL** | 30,1s | **22,7s** | **-25%** |
| FOCO_CALOR | 0,19s | 0,18s | ~igual (não paginada por chave, caminho próprio) |

**Verificação de integridade (mesmo resultado antes/depois) — confirmada por
contagem exata, nas 3 publicações já feitas nesta sessão (original antes de hoje,
"antes" de hoje, "depois" de hoje):**

| Tabela Gold | Linhas (idêntico nas 3 publicações) |
|---|---|
| `feature_publicada` | 16.266 |
| `feature_publicada` (`AREA_PROTEGIDA`) | 8.375 publicadas + **2 puladas** (mesmo número de puladas antes/depois) |
| `feature_publicada` (`IMOVEL_RURAL`) | 4.613, 0 puladas |
| `feature_publicada` (`DESMATAMENTO`) | 1.365, 0 puladas |
| `feature_publicada` (`EMBARGO`) | 124, 0 puladas |
| `feature_publicada` (`MUNICIPIO`) | 399, 0 puladas |
| `feature_publicada` (`LIMITE_PR`) | 1 |
| `indicador_resultado` | 32.291 |
| `sobreposicao_publicada` | 1.022 |
| `imovel_publicado` | 4.613 |
| `publicacao_indicadores_erro` | 0 |
| `publicacao_camada_mapa_erro` | 2 (mesmas 2 geometrias inválidas de `AREA_PROTEGIDA`, consistente antes/depois) |

Zero `WARN`/`ERROR` nos logs do backend durante toda a publicação "depois".

**Conclusão: correção íntegra (mesmo resultado, zero erro novo) e otimizada
(ganho real confirmado, maior exatamente na camada com mais volume e geometria mais
pesada localmente). Fluxo de validação (apagar → corrigir → publicar → comparar)
encerrado para este item — não são necessárias mais rodadas.**

**Observação sobre a camada `AREA_PROTEGIDA` ter sido o maior ganho local:** é a
camada mais parecida localmente com o cenário de produção que motivou a correção
(muita linha, nunca filtrada pro circuito, inclui geometria nacional pesada das
fontes 9/10 — item 2.2). Reforça a leitura de que o ganho real em produção
(IMOVEL_RURAL, 558 mil linhas) deve ser proporcionalmente muito maior do que os 25%
vistos aqui pra 4.613 linhas — mas isso só será confirmado quando/se a correção for
aplicada em produção e a Gold de lá for republicada (fora do escopo de hoje).

### 6.4. Segunda otimização: 1 commit por imóvel em vez de 8 (resultado inconclusivo localmente)

**Achado:** `recalcularImovel` faz **8 chamadas PL/SQL sequenciais por imóvel**
(`calcular_isap`, `calcular_iae`, `calcular_irl`, `calcular_iapp`, `calcular_idesmat`,
`calcular_ifc`, `calcular_icv`, `publicar_imovel`), cada uma via `jdbcTemplate.
execute(...)` **sem transação nenhuma** (`GoldPublicacaoService` não tinha nenhum
`@Transactional`) — ou seja, 8 commits e 8 ciclos de pegar/devolver conexão do pool
por imóvel, não 1. Em produção (558.692 imóveis), isso são ~4,47 milhões de chamadas/
commits, contra os 558.692 que seriam necessários com 1 só por imóvel.

**Correção aplicada** (mesmo commit `LIZARDS-63`): `recalcularImovel` ganhou
`@Transactional`. Como o método é chamado por auto-invocação de dentro de
`calcularIndicadoresEmParalelo` (mesma classe, roda em cada thread do pool), a
chamada direta via `this` não passa pelo proxy AOP e a anotação não teria efeito —
mesmo problema já resolvido antes em `TransicaoBronzeSilverService` (`CLAUDE.md`
§10.11), mesma solução aqui: campo `self` (`@Autowired @Lazy`) injetado tardiamente,
chamado como `self.recalcularImovel(...)` só nesse ponto de auto-invocação (a
chamada externa, pelo controller, já passava pelo proxy normalmente).

**Dois motivos pra essa correção, um de performance (hipótese) e um de
integridade (confirmado):**
- **Performance (hipótese, não confirmada aqui):** menos round-trip e menos commit
  deveria custar menos em volume grande.
- **Integridade (bug real, confirmado por leitura de código):** sem transação, se a
  4ª chamada (por exemplo) falhasse, as 3 anteriores já tinham commitado - o imóvel
  ficava com resultado **parcial órfão** em `gold.indicador_resultado` (ex.: só ISAP/
  IAE/IRL calculados) e ainda assim marcado como "falha" em `gold.
  publicacao_indicadores_erro`, sem nenhuma limpeza. Com `@Transactional`, falha no
  meio desfaz o imóvel inteiro - nunca fica estado parcial pra trás, mesmo princípio
  All-or-Nothing já usado no resto do projeto (Bronze→Silver).

**Testes:** os 5 pontos onde `GoldPublicacaoServiceTest` constrói o serviço
manualmente (fora de contexto Spring, `@Mock`/`spy`) precisaram de
`ReflectionTestUtils.setField(service, "self", service)` - sem Spring, o campo
`@Autowired` fica `null` e `self.recalcularImovel(...)` lançava `NullPointerException`
(capturado pelo try/catch de "falha no imóvel", 4 testes quebraram com contagem de
sucesso zerada até o fix). Suíte completa depois do fix: **803 testes, 0 falhas**
(só o `DatagisApplicationTests` de sempre).

**Resultado da medição local (mesmo fluxo: apagar Gold → publicar → comparar),
3ª publicação da sessão:**

| Fase | Só paginação (§6.3) | + fix transacional | Variação |
|---|---|---|---|
| Indicadores | 7min54,2s | 7min45,5s | -1,8% |
| MUNICIPIO | 2,3s | 3,3s | ruído |
| LIMITE_PR | 28,4s | 27,9s | ruído |
| AREA_PROTEGIDA | 1min18,2s | 1min36,8s | **+23,8% (mais lento)** |
| DESMATAMENTO | 32,5s | 26,8s | ruído |
| EMBARGO | 9,8s | 0,76s | ruído (o 9,8s de antes já era ruído) |
| IMOVEL_RURAL | 22,7s | 22,7s | igual (não é alvo deste fix) |
| **TOTAL** | **10min38,7s** | **10min44,1s** | **+0,85%** |

Log bruto: `docs/gold_local_v3_transacional.log`.

**Integridade confirmada de novo** (4ª publicação idêntica em contagem às 3
anteriores): `feature_publicada`=16.266, `indicador_resultado`=32.291,
`sobreposicao_publicada`=1.022, `imovel_publicado`=4.613,
`publicacao_indicadores_erro`=0, `publicacao_camada_mapa_erro`=2. Zero
`WARN`/`ERROR` nos logs.

**Conclusão honesta: sem ganho de performance mensurável localmente** - a variação
entre fases (`AREA_PROTEGIDA` mais lenta, `indicadores` um pouco mais rápido) é
ruído de máquina de dev (processos concorrentes, sem isolamento), não sinal real; o
total ficou dentro de 1% do anterior, essencialmente empatado. Faz sentido em
retrospecto: Oracle e backend rodam na mesma máquina via Docker (rede loopback,
disco SSD local) - round-trip e commit já são baratíssimos nesse cenário, o efeito
cumulativo de eliminar ~3,9 milhões de chamadas só deve aparecer em volume real
(produção), o que está fora do escopo de hoje pra confirmar.

**Decisão: manter a correção mesmo sem ganho de performance comprovado aqui**, pelo
motivo de integridade (bug real e confirmado, independente de qualquer medição de
tempo) - reverter a devolveria.

## 7. Como usar este documento

Atualizar a cada novo achado, na seção 3 (se ainda não corrigido) ou movendo pra
seção 1/2 assim que algo for de fato corrigido (local ou servidor, anotando onde).
Quando a publicação Gold atual terminar e os itens daqui virarem trabalho de verdade,
este documento vira a base pra abrir a(s) branch(es)/PR(s) correspondentes, seguindo
o fluxo normal do projeto (`CLAUDE.md` §10).
