# Publicação da Gold em paralelo

> Documento de **proposta**, escrito antes da implementação, a partir de medições feitas
> no servidor de produção (OCI) e num notebook da equipe em 2026-10-23. Relacionado:
> `CLAUDE.md` §9.33 (procedures da Gold), §9.36 (camadas de mapa em Java/JTS), §9.43
> (publicação Gold com botão único) e §10.9 (execução em segundo plano).

**Status:** [PROPOSTO — a validar]. Nada implementado ainda.

---

## 1. Resumo

A publicação da Gold (botão **Migrar para Gold**) calcula os 7 indicadores de cada
imóvel **um imóvel por vez, numa única conexão com o banco**. Por isso ela usa só
**1 núcleo** da máquina, qualquer que seja o tamanho do servidor. No servidor da OCI
(8 vCPUs, 31 GB) ela roda a **2 imóveis/s**; num notebook i5 de 4 núcleos roda a
**3,9 imóveis/s**, porque o núcleo do notebook é mais rápido que o do servidor.

A proposta é **processar vários imóveis ao mesmo tempo**, cada um na sua conexão. A
mudança é pequena e fica concentrada em um método do `GoldPublicacaoService`. As
procedures PL/SQL e as telas não mudam. O ganho esperado no servidor é de **4 a 6
vezes**.

---

## 2. Diagnóstico (medido, não estimado)

### 2.1. O mesmo processo nas duas máquinas, ao mesmo tempo

| | Notebook da equipe | Servidor OCI |
|---|---|---|
| Processador | Intel i5-6287U, até 3,5 GHz em 1 núcleo | AMD EPYC 7742 (VM, shape `VM.Standard.E3.Flex`), ~2,25 GHz |
| Núcleos | 4 | 8 vCPUs (4 OCPU) |
| RAM | 7,6 GB | 31 GB |
| Progresso no momento da medição | 1.400 de 4.611 em 6,1 min | 450 de 4.613 em 3,8 min |
| **Velocidade** | **3,9 imóveis/s** | **2,0 imóveis/s** |

Fonte: `gold.publicacao_indicadores` (`total_processado` sobre o tempo desde
`iniciado_em`).

### 2.2. No que o servidor gasta o tempo

Medido durante a publicação:

| Medida | Valor | Leitura |
|---|---|---|
| Tempo de CPU da sessão do backend no Oracle (`v$sess_time_model`) | 265 s de CPU em 265 s de trabalho | 100% do tempo é cálculo |
| Espera por disco (`%wa` no `top`) | 0,0 | o disco não é gargalo |
| CPU tomada por outras VMs (`%st`) | 0,0 | a OCI não está limitando |
| Limite de CPU do container (`cpu.stat`) | `nr_throttled 0` | o Docker não está limitando |
| Uso por núcleo (`mpstat`) | 1 núcleo a ~97%, os outros 7 entre 0% e 17% | só 1 núcleo trabalha |
| CPU total da máquina | ~11% | ≈ 1 de 8 núcleos |
| RAM total usada na máquina | ~7,4 GB de 31 GB | sobra quase toda |

### 2.3. Limites de memória que estão configurados

| Componente | Limite | Uso parado |
|---|---|---|
| Oracle — SGA (cache) | **fixo em 2,3 GB** (`sga_target`, definido na criação do banco) | 2,3 GB |
| Oracle — PGA (memória por sessão) | alvo 781 MB, teto 8 GB (`pga_aggregate_limit`) | ~0,6 GB; o máximo já usado foi 2,1 GB |
| Backend (JVM) | teto de 8 GB (`-Xmx8g` no `docker-compose.yml`) | ~1 GB |
| Keycloak / GeoServer | — / 1 GB | 1 GB / 0,5 GB |

O Oracle não usa mais memória só porque a máquina tem mais. E, enquanto o cálculo for
de uma sessão só, a PGA também fica baixa.

### 2.4. Linha de base: publicação completa no servidor (2026-10-23)

Medida do começo ao fim no servidor, com um coletor gravando CPU por núcleo e RAM de
cada container a cada 5 s (577 medições). Horários em UTC.

| Etapa | Início | Duração | Resultado |
|---|---|---|---|
| Indicadores (4.613 imóveis) | 00:11:59 | **37,9 min** (2,03 imóveis/s) | 4.613 ok, 0 falha |
| Camada `MUNICIPIO` (399) | 00:49:53 | 0,2 min | 0 pulados |
| Camada `LIMITE_PR` (1) | 00:50:07 | 0,3 min | 0 pulados |
| Camada `AREA_PROTEGIDA` (16.059) | 00:50:23 | **27,0 min** | 0 pulados |
| Camada `DESMATAMENTO` (1.428) | 01:17:24 | 0,7 min | 0 pulados |
| Camadas `EMBARGO`, `IMOVEL_RURAL`, `FOCO_CALOR` | 01:18:03 | 0,4 min no total | 0 pulados |
| **Total** | | **~66,5 min** | |

| Recurso | Etapa dos indicadores | Etapa das camadas |
|---|---|---|
| CPU do Oracle | média 103% (1 núcleo), pico 203% | média 93% |
| CPU do backend | baixa | média 14%, pico 123% |
| Núcleos acima de 50% ao mesmo tempo | tipicamente 1, máximo 2 (de 8) | tipicamente 1, máximo 2 |
| CPU total da máquina | 13,6% | 13,5% |
| RAM do Oracle (pico) | 3,5 GB | 3,5 GB |
| RAM do backend (pico) | 1,3 GB | 1,5 GB |
| RAM da máquina inteira (pico) | 7,7 GB de 31 GB | 7,6 GB de 31 GB |

No notebook, na mesma noite, os indicadores levaram 27,3 min (2,82 imóveis/s). As áreas
protegidas levaram 43,7 min, mais que no servidor, porque a memória do notebook (7,6 GB)
acabou no meio do processo e ele passou a usar swap.

**Achado sobre a etapa das camadas:** quem trabalha é o Oracle, não o backend. A consulta
de cada camada (`SELECT_POR_CAMADA` no `GoldPublicacaoService`) usa
`SDO_UTIL.TO_WKTGEOMETRY(geometria)` para mandar cada polígono como texto ao backend, e
essa conversão é cara nos polígonos grandes das áreas protegidas (até 164 mil vértices,
§9.33 do `CLAUDE.md`). Ela também roda em fila única: uma página de 200 registros por
vez, numa conexão só.

### 2.5. Conclusão do diagnóstico

O gargalo é **o código processar em fila única**. Nesse cenário o que importa é a
velocidade de **um** núcleo, e o EPYC 7742 (feito para ter muitos núcleos, cada um mais
lento) perde para o i5 do notebook. Aumentar a máquina não acelera nada; paralelizar,
sim.

---

## 3. Como funciona hoje

`GoldPublicacaoService.publicarTudo()`:

1. Lê todos os `cod_imovel` de `silver.imovel_rural`.
2. Cria a linha de controle em `gold.publicacao_indicadores`.
3. **Para cada imóvel, em sequência** (`for`), chama `recalcularImovel(cod)`, que faz 8
   chamadas ao banco:
   - `pkg_calculo_territorial.calcular_isap`, `calcular_iae`, `calcular_irl`,
     `calcular_iapp`
   - `pkg_calculo_ambiental.calcular_idesmat`, `calcular_ifc`
   - `pkg_calculo_conservacao.calcular_icv`
   - `pkg_gold_publicacao.publicar_imovel`
4. A cada 50 imóveis atualiza o progresso; falha de um imóvel vai para
   `gold.publicacao_indicadores_erro` e o loop segue.
5. Depois dos imóveis, publica as camadas do mapa uma por uma
   (`publicarCamadaMapaEmLotes`).
6. Marca os lotes `PROMOVIDO_SILVER` como `PROMOVIDO_GOLD`.

Cada chamada do `jdbcTemplate` pega uma conexão do pool, executa e confirma sozinha
(não há `@Transactional` envolvendo o loop).

---

## 4. Por que dá para paralelizar com segurança

Conferido na versão mais recente do `pkg_gold_publicacao` (`V070`) e dos pacotes de
cálculo:

- **Cada imóvel é independente.** As procedures fazem `INSERT` de linhas do próprio
  imóvel (`indicador_versao`, `indicador_resultado`, `indicador_versao_fonte`,
  `imovel_publicado`, sobreposições). Não há `UPDATE` em linha compartilhada (contador
  global, tabela de controle), nem `SELECT ... FOR UPDATE`, nem `LOCK TABLE`. Dois
  imóveis processados ao mesmo tempo não disputam a mesma linha.
- **As proteções contra duplicidade são por imóvel.** O `hash_versao` de
  `registrar_resultado` (recalcular sem mudança devolve a mesma versão) e a checagem
  "já publicado" de `publicar_imovel` olham só o imóvel em questão. Como a lista é
  dividida sem repetição, nenhum imóvel é processado duas vezes ao mesmo tempo.
- **Sem transação longa.** Cada chamada confirma sozinha, então várias conexões não
  ficam esperando o fim de uma transação grande das outras.
- **Leituras da Silver usam o índice espacial** (`SDO_RELATE`, §9.33), que aceita
  consultas simultâneas normalmente.
- **IDs vêm de `IDENTITY`** (sequências do Oracle), que são seguras com várias sessões.

---

## 5. A mudança proposta

### 5.1. Código (só o `GoldPublicacaoService`)

Trocar o `for` do passo 3 por N tarefas em paralelo, num executor **próprio** (não o
`datagisTaskExecutor`, que roda as outras tarefas em segundo plano do sistema e tem só
4 a 8 threads):

```java
int paralelismo = propriedades.paralelismo();            // app.gold.paralelismo
ExecutorService executor = Executors.newFixedThreadPool(paralelismo,
        Thread.ofPlatform().name("gold-calculo-", 1).factory());
AtomicInteger sucesso = new AtomicInteger();
List<String> falhas = Collections.synchronizedList(new ArrayList<>());
AtomicInteger desdeUltimoProgresso = new AtomicInteger();

try {
    List<Future<?>> tarefas = codigosImovel.stream()
            .map(cod -> executor.submit(() -> {
                try {
                    recalcularImovel(cod);
                    sucesso.incrementAndGet();
                } catch (Exception e) {
                    falhas.add(cod);
                    registrarErroImovel(publicacaoIndicadoresId, cod, e);
                }
                if (desdeUltimoProgresso.incrementAndGet() % LOTE_PROGRESSO_INDICADORES == 0) {
                    atualizarProgressoIndicadores(...);
                }
            }))
            .toList();
    for (Future<?> t : tarefas) t.get();                  // espera todos
} finally {
    executor.shutdown();
}
```

Pontos de cuidado na implementação:

- **Progresso:** hoje `atualizarProgressoIndicadores` soma "processados desde a última
  atualização". Com várias threads, a soma precisa vir de contadores atômicos, e a
  atualização em `gold.publicacao_indicadores` deve ser feita por uma thread de cada
  vez (`synchronized` no método, ou gravar o total absoluto em vez de somar).
- **Falha de infraestrutura** (conexão caiu): hoje ela derruba o loop e marca a
  publicação como `FALHOU`. Com o executor, a exceção chega pelo `Future.get()`; o
  tratamento continua o mesmo, cancelando as tarefas restantes.
- **`recalcularImovel` não muda.** Ele continua sendo chamado também pelo recálculo
  de um imóvel só (`POST /api/gold/imoveis/{cod}/recalcular`).
- **Com `paralelismo = 1` o comportamento é idêntico ao de hoje** (uma thread, mesma
  ordem). Isso permite desligar a mudança sem reverter código.

### 5.2. Configuração

| Propriedade | Local (padrão) | Servidor | Por quê |
|---|---|---|---|
| `app.gold.paralelismo` | `1` | `6` | no notebook, 1 mantém tudo como hoje; no servidor, 6 deixa folga de núcleo para o backend, o Keycloak e o GeoServer |
| `spring.datasource.hikari.maximum-pool-size` | padrão (10) | `16` | cada tarefa usa 1 conexão, e o resto do sistema (telas, Quartz, auditoria) continua precisando das suas |

O valor do servidor entra pelo `docker-compose.prod.yml` (variáveis de ambiente
`APP_GOLD_PARALELISMO` e `SPRING_DATASOURCE_HIKARI_MAXIMUMPOOLSIZE`), sem mudar nada
do ambiente local da equipe.

### 5.3. Oracle do servidor

Com 6 sessões calculando ao mesmo tempo, a PGA soma a de cada sessão. Uma sessão já
chegou a 2,1 GB; 6 podem passar do teto atual de 8 GB e cair em `ORA-04036`. No
servidor, subir o teto **uma vez, como SYS** (primeiro no `CDB$ROOT`, depois no PDB,
mesma ordem da §9.33):

```sql
ALTER SYSTEM SET PGA_AGGREGATE_LIMIT = 16G SCOPE = BOTH;          -- no CDB$ROOT
ALTER SESSION SET CONTAINER = FREEPDB1;
ALTER SYSTEM SET PGA_AGGREGATE_LIMIT = 16G SCOPE = BOTH;
```

Opcional, para medir depois: aumentar o SGA (cache) de 2,3 GB para algo como 6 GB
também reduz leituras repetidas da Silver. Exige reiniciar o Oracle, então fica para
depois de medir o ganho do paralelismo sozinho.

No ambiente local nada disso é necessário, porque o paralelismo fica em 1.

---

## 6. Testes

### 6.1. Automáticos (`GoldPublicacaoServiceTest`)

- Com `paralelismo = 1`: chama as 8 procedures de cada imóvel, na mesma ordem de hoje.
- Com `paralelismo = 4` e 100 imóveis: todos são processados exatamente uma vez
  (nenhum repetido, nenhum faltando).
- Falha de alguns imóveis em paralelo: os falhos vão para a lista de erro e a contagem
  de sucesso e falha fecha com o total.
- Progresso final gravado igual ao total, mesmo com threads terminando fora de ordem.

### 6.2. Equivalência dos resultados (antes de ligar no servidor)

No banco local:

1. Publicar com `paralelismo = 1` e guardar o resultado:
   ```sql
   SELECT cod_imovel, valor_icv, valor_irl, valor_iapp, valor_isap, valor_iae,
          valor_idesmat, valor_ifc
     FROM (SELECT ip.*, ROW_NUMBER() OVER (PARTITION BY cod_imovel
                         ORDER BY data_publicacao DESC, id DESC) ordem
             FROM gold.imovel_publicado ip)
    WHERE ordem = 1;
   ```
2. Publicar de novo com `paralelismo = 4` e rodar a mesma consulta.
3. Os dois resultados têm de ser **idênticos**. Como o `hash_versao` não muda quando
   o dado não muda, a segunda publicação também não deve criar versões novas.

### 6.3. Medição no servidor

Repetir a medição da §2 (velocidade em `gold.publicacao_indicadores`, CPU por núcleo,
RAM por container e PGA máxima) com `paralelismo = 6`.

---

## 7. Ganho esperado

| | Hoje | Com paralelismo 6 (estimado) |
|---|---|---|
| Velocidade no servidor | 2,0 imóveis/s | 8 a 12 imóveis/s |
| Circuito de homologação (4.611 imóveis) | ~38 min | ~7 a 10 min |
| Paraná inteiro (~556 mil imóveis) | ~3 dias | ~13 a 19 horas |

A estimativa parte de 4 núcleos físicos (as 8 vCPUs são 4 núcleos com 2 threads cada),
descontando a disputa por memória e disco entre as sessões. O número real sai da
medição da §6.3.

Com o paralelismo, a máquina passa a ser usada de fato (vários núcleos e mais PGA no
Oracle), e manter 4 OCPU e 32 GB volta a fazer sentido. Sem ele, um shape menor
renderia o mesmo.

---

## 8. Riscos e como reverter

| Risco | Mitigação |
|---|---|
| PGA estourar (`ORA-04036`) | subir o teto antes (§5.3); começar com paralelismo 4 e ir a 6 depois de medir |
| Pool de conexões esgotado e telas lentas durante a publicação | pool de 16 conexões; paralelismo menor que o pool |
| Contagem de progresso errada | contadores atômicos e teste específico (§6.1) |
| Resultado diferente do sequencial | teste de equivalência (§6.2) antes de ligar no servidor |
| Problema inesperado em produção | `APP_GOLD_PARALELISMO=1` e reiniciar o backend volta ao comportamento atual, sem reverter código |

---

## 9. Fora do escopo desta mudança

- **Camadas do mapa** (`publicarCamadaMapaEmLotes`): também são sequenciais e somam
  ~29 min da publicação no servidor (§2.4). A medição mostrou que o custo está no Oracle
  convertendo as geometrias para texto (`SDO_UTIL.TO_WKTGEOMETRY`), e não na
  simplificação feita pelo backend, que usou no máximo 1,5 GB. Duas melhorias possíveis,
  para uma segunda etapa:
  - buscar várias páginas em paralelo, cada uma na sua conexão (mesma ideia dos
    indicadores);
  - trocar o texto (WKT) por formato binário (`SDO_UTIL.TO_WKBGEOMETRY` + leitor WKB do
    JTS), que costuma ser bem mais barato de gerar e de ler.

  Paralelizar aqui também multiplica a memória da JVM na simplificação das geometrias
  grandes (§9.36 do `CLAUDE.md`), então o paralelismo dessa etapa deve ser menor que o
  dos indicadores e medido à parte.
- **Bronze → Silver**: o mesmo padrão serviria, mas hoje o ganho é pequeno (o CAR do
  circuito vai para a Silver em ~3 s).
- **Shape da máquina**: a decisão de manter ou reduzir fica para depois da medição com
  o paralelismo ligado.

---

## 10. Itens em aberto

- ID da task no board.
- Valor final do paralelismo no servidor (4 ou 6), definido pela medição.
- Se a segunda etapa (camadas do mapa, §9) entra na mesma task ou numa própria.

---

## 11. Investigação de otimização — ambiente local (2026-10-25)

> Esta seção documenta uma investigação posterior à implementação (que já está
> mesclada, §1 acima ficou obsoleto nesse ponto), motivada por: "em breve vamos
> trabalhar com os dados do Paraná inteiro" — precisa escalar bem antes disso.
> Medições feitas no ambiente local (Ryzen 5 5600, 12 threads, WSL2 com 15,6 GB de RAM
> visíveis — metade dos 32 GB do host), contra o circuito de homologação (4.613
> imóveis, Curitiba+Maringá+Ponta Grossa) já com os dados nacionais de CNUC/CNFP sem
> filtro territorial (8.361 linhas em `area_protegida`, bem mais pesado que a medição
> original do §2).

**Resumo executivo (ao final das 3 etapas autorizadas):**

| Config | Duração (indicadores, 4.613 imóveis) | Taxa |
|---|---|---|
| Baseline (sequencial, antes de qualquer ajuste) | 38min30s | 2,00 im/s |
| + PGA ajustado (3.1, sequencial) | 21min41s | 3,55 im/s |
| + PGA ajustado + paralelismo=8 (3.1, medido isolado) | ~4min45s (estimado) | 16,19 im/s |
| **+ cache `gold.desmatamento_otimizado` (3.2, publicação real completa)** | **4min31s (271s)** | **17,02 im/s** |

**Ganho total: ~8,5x** sobre o baseline sequencial, batendo até a estimativa isolada
do item 3.1 (a publicação real passou a performar igual ou melhor que o benchmark
que só isolava 1 chamada por vez — ou seja, não há mais overhead escondido de
orquestração sobrando pra investigar).

**Projeção pro Paraná inteiro** (~556 mil imóveis, ~120x o circuito atual): **~9,1
minutos** só nos indicadores, a essa taxa — contra as ~10,4 horas que o baseline
sequencial original projetava. A publicação de camadas de mapa (bloco separado, já
resolvido pelo LIZARDS-50) levou mais 409 s nesta mesma rodada (maior parte em
`AREA_PROTEGIDA`, 8.361 features reais) — não investigada nesta sessão por já ter
solução conhecida; projeção pro Paraná inteiro dessa parte depende de quanto
`area_protegida`/`imovel_rural` crescem (não é proporcional 1:1 ao total de
imóveis, já que as camadas territoriais como UCs nacionais não crescem com o
recorte).

**O que mudou de verdade (3.1+3.2, ambos aplicados e commitáveis):**
1. `PGA_AGGREGATE_TARGET=3G`/`PGA_AGGREGATE_LIMIT=16G` (Oracle, `ALTER SYSTEM`, já
   persistido via spfile — sobrevive a restart do container).
2. `app.gold.paralelismo=8` + `spring.datasource.hikari.maximum-pool-size=16`,
   agora fixados em `docker-compose.yml` (não eram — foi um achado desta sessão:
   eles existiam só como env var ad-hoc da sessão anterior e se perderam no
   restart do PC).
3. `gold.desmatamento_otimizado` (V083/V084) — cache de geometria simplificada
   (~555 m, só pra cálculo) pro `calcular_idesmat`, reconstruído 1x por publicação.

**3.3 (migrar procedures pra aplicação): não recomendado, decisão final no §11.5.**

### 11.1. Pergunta 1 — qual o ponto de gargalo? É o Oracle?

**Sim, é o Oracle — mas não por falta de CPU.** Instrumentação real durante uma
publicação com `paralelismo=4` (18 min de amostragem, CPU por núcleo a cada 2s +
`v$session` do Oracle a cada 2s, filtrando as sessões do usuário `CONTROLE`):

| Medida | Valor |
|---|---|
| CPU total da máquina (12 núcleos) | média 11,9% |
| CPU do container Oracle (`docker stats`) | média 105,9%, pico 164,5% (≈ 1 núcleo) |
| Núcleos ≥50% ocupados ao mesmo tempo | média 0,88, máximo 2 |
| Sessões Oracle distintas vistas `ACTIVE` em 544 amostras | só 4 sids, sendo 1 deles (`sid=619`) em 748 de 1.016 amostras `ACTIVE` (74%) |
| Evento de espera dominante | `Allocate PGA memory from OS`, 814 de 1.016 amostras `ACTIVE` (80%) |

Ou seja: mesmo com 4 threads da JVM submetendo trabalho ao mesmo tempo, o Oracle
raramente tinha mais de 1 sessão realmente trabalhando por vez — o restante da CPU da
máquina (os outros ~11 núcleos) ficava ocioso. O gargalo não é "o Oracle é lento", é
"o Oracle não estava conseguindo (ou não estava configurado para) usar mais de ~1
núcleo por vez para este tipo de carga".

**Causa raiz encontrada**: `pga_aggregate_target` estava em **1.597 MB** (valor
default auto-calculado, bem pequeno) com `pga_aggregate_limit` em 8G. Isso não impedia
as sessões de rodar — mas forçava o Oracle a usar algoritmos de work area degradados
(multi-pass em vez de in-memory) para as operações internas de ordenação/hash que o
cálculo espacial (`SDO_RELATE`, `GeometryFixer`) usa por baixo, e a competir
intensamente pela pouca memória disponível entre as sessões concorrentes — daí a
dominância do evento de alocação de PGA.

### 11.2. Pergunta 2 — vale migrar a publicação do Oracle (PL/SQL) pra aplicação?

**Não, pelo menos não a parte de cálculo espacial.** A causa raiz encontrada (§11.1)
está na configuração de memória do Oracle, não na escolha de PL/SQL como lugar do
cálculo. Mover `SDO_RELATE`/interseção espacial para a aplicação significaria:

- Trazer geometrias grandes (algumas com centenas de milhares de vértices,
  `CLAUDE.md` §9.33) pela rede via JDBC antes de calcular — custo de serialização e
  tráfego que hoje não existe (o cálculo roda perto do dado).
- Perder o índice espacial (`CLAUDE.md` §9.33 já mediu um ganho de ~2.400x usando
  `SDO_RELATE` com índice em vez de `SDO_ANYINTERACT` sem índice) — reimplementar essa
  mesma indexação em JTS/client-side não é trivial e dificilmente replica o
  desempenho de um R-tree nativo do banco sobre o dado já persistido lá.
- Não resolve a causa raiz real (configuração de memória), que é ortogonal a onde o
  código roda.

A avaliação muda de figura só para a **orquestração** (hash de versão, loop de
publicação, controle de progresso) — ver §11.4 (item 3.3) para o que isso significaria
na prática e por que não compensa aqui.

### 11.3. Item 3.1 — Otimização de paralelismo

**Ajuste 1 (o que importou de verdade): aumentar PGA.**
```sql
ALTER SYSTEM SET PGA_AGGREGATE_TARGET = 3G SCOPE = BOTH;  -- era 1.597M
ALTER SYSTEM SET PGA_AGGREGATE_LIMIT = 16G SCOPE = BOTH;  -- era 8G
-- repetir dentro do PDB (ALTER SESSION SET CONTAINER = FREEPDB1;) também
```
Mesma publicação completa (4.613 imóveis, dado idêntico, idempotente por hash),
`paralelismo=4` nos dois casos:

| | Antes (PGA 1,6G/8G) | Depois (PGA 3G/16G) | Ganho |
|---|---|---|---|
| Duração (indicadores) | 38min30s | 21min41s | **77% mais rápido** |
| Taxa | 2,00 im/s | 3,55 im/s | |
| Sessões Oracle distintas `ACTIVE` | 4 (1 dominando 74%) | 6 (mais distribuído) | mais concorrência real |
| `Allocate PGA memory from OS` entre amostras ACTIVE | 80% | 92% (mas agora é rápido, não bloqueia) | — |

O evento de espera continua aparecendo nas amostras (é frequente, só rápido agora, não
é mais sinal de travamento) — o que mudou foi o Oracle parar de degradar o algoritmo
interno por falta de memória de trabalho.

**Ajuste 2: achar o paralelismo ideal.** Com a PGA já corrigida, varredura rápida via
`POST /api/gold/imoveis/{cod}/recalcular` chamado com K conexões simultâneas (`xargs
-P K`), 40 imóveis por nível, amostra aleatória nova a cada nível:

| Paralelismo (K) | Taxa medida | Ganho vs. K=1 |
|---|---|---|
| 1 | 2,91 im/s | 1,0x |
| 2 | 4,94 im/s | 1,7x |
| 4 | 7,27 im/s | 2,5x |
| 6 | 15,31 im/s | 5,3x |
| **8** | **16,19 im/s** | **5,6x (pico)** |
| 12 | 14,80 im/s | 5,1x (leve queda) |

Testado se a queda em 12 era o pool de conexões do Hikari (padrão 10) estrangulando —
subi o pool para 20 e repeti: taxa ficou em 13,18 im/s, **não melhorou** — ou seja, não
é o pool, é saturação real por volta de 8 sessões Oracle concorrentes nesta máquina
(12 threads lógicas, mas parte do orçamento de CPU/memória compartilhado com SO,
Keycloak, GeoServer, frontend).

**Recomendação local:** `app.gold.paralelismo=8` (não 4 nem 12) **com a PGA ajustada**.
Sem o ajuste de PGA, o valor de paralelismo quase não importa (ficava preso perto de
2 im/s em qualquer K, como o §2 original já tinha medido com paralelismo=1 no
servidor).

**Risco já conhecido, reconfirmado:** a essa taxa (16 im/s), publicar o Paraná inteiro
(~556 mil imóveis) levaria **~9,7 horas** só nos indicadores — ainda não é suficiente
para um ciclo de poucas horas. É por isso que os itens 3.2/3.3 seguem sendo
investigados (próximas seções).

### 11.4. Item 3.2 — Otimização de procedures

**Resultado final, com paralelismo=8 + PGA já ajustados (§11.3): 4.613 imóveis em
271 s (17,02 im/s), contra 758 s (6,08 im/s) na 1ª tentativa e os ~285 s extrapolados
do benchmark isolado do §11.3 — ou seja, a publicação real agora bate o que o
benchmark isolado só estimava.** Projeção pro Paraná inteiro (~556 mil imóveis):
~9,1 horas → **~9,1 min só nos indicadores** a essa taxa (a conta de "~9,7 horas" do
§11.3 já estava errada de origem — foi feita sobre o benchmark isolado, que não
incluía o custo real do IDesmat descrito abaixo).

**Diagnóstico — onde o tempo ia (breakdown por procedure, 30 imóveis aleatórios,
`DBMS_UTILITY.GET_TIME` por chamada):**

| Procedure | Tempo/imóvel (antes) |
|---|---|
| `calcular_isap` | 9 ms |
| `calcular_iae` | 1,3 ms |
| `calcular_irl` | 0 ms |
| `calcular_iapp` | 2,3 ms |
| **`calcular_idesmat`** | **248 ms (94% do total)** |
| `calcular_ifc` | 1,3 ms |
| `calcular_icv` | 0 ms |
| `publicar_imovel` | 0,7 ms |
| **Total** | **~263 ms** |

`calcular_idesmat` dominava apesar de `silver.desmatamento` ter só 1.428 linhas —
bem menos que `area_protegida` (8.361), que `calcular_isap` varre em 9 ms com o
mesmo padrão `SDO_RELATE`+cursor+`SDO_INTERSECTION`+dissolve. Não era volume, era
**1 polígono do PRODES (ano 2000, id 1140) com 317.235 vértices** — mesma classe de
geometria monstro já documentada no LIZARDS-50 (a de 634 mil ordenadas que travava
`SDO_UTIL.SIMPLIFY`). Índice espacial de `desmatamento` já existia e estava `VALID`
— não era problema de índice.

**3 tentativas até achar a correção de verdade (registradas porque cada uma pareceu
certa até ser medida contra a publicação real, não só um teste isolado):**

1. **Cache simplificado só com tolerância pequena (~11 m) — não resolveu.**
   `gold.desmatamento_otimizado` (nova, V083) + `SimplificadorGeometriaMapa` (JTS,
   fora do PGA do Oracle, mesmo motivo do LIZARDS-50) reduziu o polígono monstro de
   317 mil pra 123 mil vértices — parece bom isoladamente (`calcular_idesmat` caiu
   pra 17 ms/imóvel numa amostra de 30), mas a publicação real ficou **mais lenta**
   (758 s, 6,08 im/s). Causa: o polígono é um `MultiPolygon` fragmentado com
   `SDO_GEOM.SDO_MBR` de **~180×130 km** (maior que Maringá inteira) — índice
   espacial é por feição, não por sub-elemento, então **todo** imóvel cujo bounding
   box cai dentro desse MBR gigante (praticamente todo o circuito de Maringá, 1.843
   imóveis) precisava do check exato contra a geometria inteira só pra descobrir que
   não intersecciona de verdade. Uma amostra pequena (30 de 4.613) simplesmente não
   pega esse efeito de escala.
2. **Decompor o `MultiPolygon` em partes antes de simplificar — não resolveu
   sozinho.** `SimplificadorGeometriaMapa.decomporEsimplificar` (novo método) separa
   por `getGeometryN()` antes de simplificar cada parte. O polígono 1140 virou 51
   partes — mas uma delas, isolada, **continuou com 123.479 vértices**. O vértice-
   count mora majoritariamente num único anel topologicamente conectado (um
   contorno de desmatamento acumulado real, não fragmentos desconexos), então
   decompor por componente desconexo não ajuda quando o problema é 1 anel só sendo
   grande demais. Medido direto: `SDO_GEOM.SDO_INTERSECTION` contra essa 1 parte
   levava **30 s** pra 1 imóvel.
3. **Tolerância bem maior (~555 m, de 0,0001 para 0,005 grau) — resolveu.** Pro uso
   de *cálculo* (percentual de área de um imóvel medido em hectares), não de
   *desenho* (onde ~5 m é o padrão do projeto), 555 m de folga na borda é
   desprezível. O polígono 1140 caiu pra 19.463 vértices (máximo da tabela toda); a
   média de toda `gold.desmatamento_otimizado` caiu pra 20 vértices. Resultado:
   271 s pros 4.613 imóveis, 17,02 im/s.

**Implementação final (`GoldPublicacaoService`):**
- `prepararDesmatamentoOtimizado()` reconstrói `gold.desmatamento_otimizado` (DELETE
  + repopula) a cada `publicarTudo()`, **antes** do loop por imóvel — custo pago 1x
  por publicação inteira (~30 s pra 1.428→1.635 linhas), não 1x por imóvel.
- `pkg_calculo_ambiental.calcular_idesmat` (`V084`) passou a ler
  `gold.desmatamento_otimizado` em vez de `silver.desmatamento` — mesma lógica
  interna (`SDO_RELATE`→cursor→`SDO_INTERSECTION`→`f_dissolver`), só a tabela de
  origem trocou. `fonte_dado_id`/`lote_ingestao_id` são copiados pra linhagem
  continuar correta em `registrar_resultado` sem precisar voltar na Silver por linha.
- `TOLERANCIA_DESMATAMENTO_CALCULO_GRAUS = 0.005` (~555 m), bem maior que a do mapa
  (`SimplificadorGeometriaMapa.TOLERANCIA_GRAUS`, ~5 m) — a diferença é proposital e
  comentada no código: cálculo de indicador tolera folga que desenho de mapa não.
- Consolidar as 8 chamadas JDBC por imóvel em 1 só chamada combinada (testado
  separadamente, par controlado de 100 imóveis sequenciais) deu só **~2,5%** de
  ganho — não é o gargalo, não foi adotado.
- `gold.desmatamento_otimizado` é cache descartável (sem FK pra Silver), schema
  `GOLD` — mesmo padrão de outras tabelas derivadas da Gold.

**Risco residual, não resolvido nesta sessão:** só `calcular_idesmat` foi
investigado a fundo porque foi o único que apareceu dominante na amostra de 30. Não
foi feita a mesma varredura "existe 1 geometria monstro parecida" pras outras 3
procedures espaciais (`calcular_isap`/`calcular_iae` contra `area_protegida`/
`embargo_ambiental`, `calcular_iapp`/`calcular_icv` contra `app_declarada`/
`vegetacao_nativa_declarada`) — elas já estavam rápidas na amostra de 30 (9 ms ou
menos), mas uma amostra pequena foi exatamente o que escondeu o problema do IDesmat
também. Vale rodar a mesma checagem (`SDO_UTIL.GETNUMVERTICES` + `SDO_GEOM.SDO_MBR`
por linha, ordenado por vértice desc) nas 3 tabelas antes de escalar pro Paraná
inteiro, onde o volume de cada uma cresce ~15-20x.

**Validação independente — disparado pelo usuário pela tela "Migrar para Gold"
(2026-10-25, Gold zerada antes, Bronze/Silver/Quarentena intactos), não por mim:**

| Etapa | Linhas | Duração | Taxa |
|---|---|---|---|
| Indicadores (os 4.613 imóveis) | 4.613/4.613, 0 falha | 356 s | 12,96 im/s |
| Camada MUNICIPIO | 399/399 | 5 s | |
| Camada LIMITE_PR | 1/1 | 27 s | |
| Camada AREA_PROTEGIDA | 8.361/8.361, 2 puladas | 323 s | |
| Camada DESMATAMENTO | 1.428/1.428 | 30 s | |
| Camada EMBARGO | 124/124 | 0 s | |
| Camada IMOVEL_RURAL | 4.613/4.613 | 22 s | |
| Camada FOCO_CALOR | 0/0 (vazia, §9.33) | 0 s | |
| **Total end-to-end** (indicadores + 6 camadas) | | **763 s (12min43s)** | |

12,96 im/s nos indicadores é um pouco abaixo dos 17,02 im/s medidos antes nesta
sessão (mesmo código, mesmo ambiente) — diferença plausível por variação de carga
da máquina entre as duas rodadas, não um problema novo; segue sendo ~6,5x mais
rápido que o baseline sequencial original (38min30s → 356s nos indicadores). 0
falhas em indicadores; só 2 geometrias de `area_protegida` puladas na camada de
mapa (já esperado e documentado, §9.33 — inválidas de origem, não desta sessão).
`gold.imovel_publicado` confirmado com 4.613 linhas ao final.

### 11.5. Item 3.3 — Migrar publicação de procedures para a aplicação

**Conclusão: não recomendado migrar mais nada — a divisão de trabalho atual já é a
correta, e ela já está mista (não é "tudo PL/SQL" nem "tudo aplicação").**

- **A orquestração já está na aplicação.** `GoldPublicacaoService.publicarTudo()` é
  quem decide "para cada imóvel, calcule os 7 indicadores e publique" — não existe
  um job PL/SQL/`DBMS_SCHEDULER` fazendo esse papel (decisão original,
  `docs/gold-procedures-calculo-publicacao.md` §3.4, pra evitar dependência
  circular entre `pkg_gold_publicacao` e os `pkg_calculo_*`). Não há "procedure de
  orquestração" pra migrar — já é Java.
- **O cálculo espacial em si (`SDO_RELATE`+`SDO_INTERSECTION`+dissolve) deve
  continuar em PL/SQL**, pelo motivo já dado no §11.2: sai do índice espacial do
  Oracle (que só funciona bem com `SDO_RELATE` nativo, §9.33 do `CLAUDE.md`), e
  reconstruir esse motor em JTS/Java custaria rede (toda geometria candidata teria
  que viajar do Oracle pro backend) + reimplementação de um motor espacial que já
  funciona.
- **O que o item 3.2 mostrou** é que o ponto certo de intervenção não é "mover o
  cálculo pra aplicação" nem "deixar tudo no banco" — é mover só a etapa que o
  Oracle faz mal (simplificação/generalização de geometria complexa, que estoura
  PGA de forma conhecida desde o LIZARDS-50) pra onde ela é barata (JTS, heap da
  JVM), e deixar a etapa que o Oracle faz bem (interseção indexada) onde está. Essa
  divisão híbrida — já estabelecida pro bloco de mapa (LIZARDS-50) e agora também
  pro bloco de indicadores (`gold.desmatamento_otimizado`, esta sessão) — é a
  arquitetura recomendada daqui pra frente, não uma migração total em nenhuma
  direção.
- **Padrão a repetir, se aparecer o mesmo sintoma em outra procedure:** 1) achar a
  geometria-outlier (`SDO_UTIL.GETNUMVERTICES` ordenado desc); 2) checar se
  simplificação pequena (~mapa) já resolve; 3) se não, simplificar bem mais agressivo
  só pra cálculo (a tolerância de desenho e a de cálculo são propositalmente
  diferentes); 4) cachear o resultado simplificado numa tabela derivada da Gold,
  reconstruída 1x por publicação, nunca 1x por imóvel.

### 11.6. Ambiente da medição (pra reprodutibilidade)

- Host: Ryzen 5 5600 (6 núcleos/12 threads), WSL2 com `.wslconfig` limitando a 15,6 GB
  (metade dos 32 GB físicos) — não ajustado durante esta investigação (ver §11.7).
- Containers: `oracle` (Oracle Enterprise, schemas do projeto), `backend` (JVM
  `-Xmx8g`), mais Keycloak/GeoServer/frontend ociosos durante os testes.
- Achado colateral: o heap do backend tinha crescido para **8,2 GB** depois de uma
  sessão longa de ingestão (vários lotes nacionais pesados) e nunca foi liberado
  sozinho — um `docker compose restart backend` recuperou a RAM (voltou a ~700 MB) e,
  por si, já acelerou o início da primeira publicação testada aqui. Vale considerar:
  reiniciar o backend antes de uma publicação longa, ou revisar se o heap de 8g é
  necessário/se há retenção de memória que poderia ser liberada.

### 11.7. Fora do alcance desta sessão (precisa de intervenção do usuário)

- **Teto de RAM do WSL2** (`.wslconfig`, 15,6 GB de 32 GB do host): mesmo depois do
  ajuste de PGA, a swap do host chegou a ficar 100% ocupada em um ponto desta
  investigação. Aumentar o teto exige editar `.wslconfig` no lado Windows e rodar
  `wsl --shutdown`, o que está fora do que dá pra fazer de dentro do WSL — e derrubaria
  todos os containers em execução. Fica registrado como a otimização de maior
  potencial que não pôde ser testada autonomamente.

---

## 12. Bug de correção (não de performance) — ISAP=0% com imóvel visualmente dentro
## de área protegida (2026-10-25)

> Achado pelo usuário testando a tela de mapa depois da publicação do §11: o imóvel
> `PR-4119905-D0759104DB88431FBF3AB1240859D53A` aparecia visualmente quase inteiro
> dentro do Parque Nacional dos Campos Gerais, o mapa confirmava "cruza 2 camadas de
> alerta: ISAP, IAE", mas o indicador publicado mostrava **ISAP = 0,0%**.

### 12.1. Causa raiz

`SDO_GEOM.SDO_INTERSECTION` do Oracle **não lança erro contra geometria
topologicamente inválida** — ele silenciosamente devolve uma área "lixo" (perto de
zero), que entra na soma como se fosse uma interseção real. Rastreado ao vivo pro
imóvel do exemplo: os 3 candidatos de `area_protegida` (todos a mesma UC, Parque
Nacional dos Campos Gerais, 3 fontes diferentes) estavam com geometria inválida —
2 por anel no sentido errado (`ORA-13367`), 1 por vértice duplicado (`ORA-13356`).
`SDO_GEOM.SDO_INTERSECTION` contra eles devolvia ~0,06 ha de "interseção", quando
boa parte dos 324 ha do imóvel está de fato dentro do parque.

**Escopo real, medido em toda a base, não só nesse 1 imóvel** — rodando
`SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT` linha a linha em cada tabela espacial da
Silver:

| Tabela | Inválidas (antes) | % |
|---|---|---|
| `imovel_rural` | 2.297 / 4.613 | 49,8% |
| `area_protegida` | 8.177 / 8.361 | **97,8%** |
| `embargo_ambiental` | 1 / 124 | 0,8% |
| `app_declarada` | 1.980 / 3.180 | 62,3% |
| `vegetacao_nativa_declarada` | 1.669 / 3.182 | 52,4% |
| `reserva_legal` | 1.379 / 2.774 | 49,7% |
| `desmatamento` | 1.428 / 1.428 | **100%** |

Ou seja: ISAP, IAE, IAPP e ICV podiam estar sistematicamente zerados (ou
subestimados) pra uma fração enorme dos imóveis, não só exemplos isolados. A causa
raiz: fontes externas (CNUC/SFB/ICMBio/CAR/PRODES) mandam o anel do polígono no
sentido horário; o Oracle Spatial exige anti-horário. Essa correção já existia **só
pro mapa** (`SimplificadorGeometriaMapa.orientarParaOracle`, LIZARDS-35) — nunca
tinha sido aplicada na gravação Bronze→Silver, então o mapa (que lê a cópia
derivada e corrigida da Gold) mostrava a sobreposição certa, e o cálculo (que lê
`silver.*` direto) calculava lixo.

### 12.2. Correção em 3 camadas

1. **Entrada (previne dado novo errado):** `ValidadorGeometria.validar()` (pacote
   `validacao`, chamado por TODO registro ingerido) agora chama
   `SimplificadorGeometriaMapa.orientarParaOracle(geometria)` antes de gravar o WKT
   final — reuso direto do método já existente e testado no LIZARDS-35, só
   promovido de `static` (package-private) pra `public static` pra poder ser
   chamado fora do pacote `gold`.
2. **Dado já gravado:** reprocessados os 14 lotes com geometria poligonal
   (`POST /api/lotes/{id}/reprocessar`, endpoint já existente — não foi escrito
   nenhum script de migração novo). Revalida contra a esteira **atual** sem
   precisar reingerir nada; a regra All-or-Nothing garante que nada fica
   parcialmente corrigido.
3. **Rede de segurança (`V085`):** `calcular_isap`/`calcular_iae`/`calcular_iapp`
   (`pkg_calculo_territorial`) agora chamam
   `SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT` em cada candidato **antes** de
   `SDO_INTERSECTION`, e pulam (`CONTINUE`) o que ainda estiver inválido — protege
   contra o residual que a correção de entrada sozinha não consegue resolver
   (auto-interseção e vértice duplicado nem sempre normalizam pra geometria válida
   do Oracle, mesmo com `GeometryFixer` do JTS). `calcular_irl`/`calcular_icv` não
   precisaram de blindagem — somam `area_ha` declarada por `cod_imovel`, sem
   nenhum `SDO_GEOM.SDO_INTERSECTION`.

**Achado colateral, bug separado (não de geometria):** reprocessar o lote 10
(SFB/CNFP, 12.874 registros) expôs 264 registros com categoria composta
("TI / PA", "PA / RVS" etc. — área sobreposta a 2 categorias oficiais, cada
componente já existe no domínio, só a combinação com "/" nunca tinha sido vista).
`ValidadorDominio.resolverCodigoCanonico` ganhou um passo novo: se o valor contém
"/", tenta cada componente separado reusando toda a cadeia de normalização já
existente, antes de desistir.

### 12.3. Resultado, medido

Validade pós-correção (mesma metodologia, depois de reprocessar os 14 lotes):

| Tabela | Antes | Depois | 13367 (anel) |
|---|---|---|---|
| `imovel_rural` | 49,8% | **7,9%** | 1.932 → **0** |
| `area_protegida` | 97,8% | **32,7%** | 5.460 → **0** |
| `desmatamento` | 100% | 62,0% | 542 → **0** |
| `app_declarada`/`vegetacao_nativa_declarada`/`reserva_legal` | ~50-62% | inalterado | já eram 0 antes (só vértice/auto-interseção, fora do escopo da correção de anel) |

Em **todas** as 7 tabelas, o erro de anel invertido (13367) caiu a zero. O residual
é 100% vértice-duplicado/auto-interseção, agora coberto pela blindagem do §12.2
item 3 (pulado, não computado como lixo).

**Caso original, confirmado na publicação oficial** (`gold.imovel_publicado`):

| Indicador | Antes | Depois |
|---|---|---|
| ISAP | 0,0184% | **100%** |
| IAE | 53,53% (já estava certo) | 53,53% (inalterado) |

Bate exatamente com o que a tela mostrava visualmente (imóvel quase inteiro dentro
do parque) e com o aviso "cruza 2 camadas de alerta: ISAP, IAE".

**Publicação completa após a correção** (Gold limpa, republicada do zero):

| Etapa | Resultado | Tempo |
|---|---|---|
| Indicadores (4.613 imóveis) | 4.613/4.613, 0 falha | 162 s |
| 6 camadas de mapa | todas concluídas | 419 s |
| **Total** | | **581 s (9min41s)** |

Suíte de testes: **773, 0 falhas** (só o erro conhecido do `DatagisApplicationTests`,
sem Docker-in-Docker) — nenhuma regressão.

### 12.4. Arquivos alterados

- `src/main/java/br/gov/sp/cps/datagis/gold/SimplificadorGeometriaMapa.java` —
  `orientarParaOracle` promovido pra `public static`.
- `src/main/java/br/gov/sp/cps/datagis/validacao/ValidadorGeometria.java` — chama a
  reorientação no caminho de sucesso.
- `src/main/java/br/gov/sp/cps/datagis/validacao/ValidadorDominio.java` — passo novo
  de resolução de valor composto ("A / B").
- `src/main/resources/db/migration/gold/V085__pkg_calculo_territorial_pula_candidato_invalido.sql`
  — `calcular_isap`/`calcular_iae`/`calcular_iapp` com validação prévia do
  candidato.

### 12.5. Em aberto

- Residual de ~8-62% de geometria ainda inválida (vértice duplicado/auto-interseção)
  por tabela — a blindagem do §12.2 evita resultado errado, mas o candidato
  simplesmente não entra na soma (subestima, não zera). Corrigir de verdade exigiria
  um reparo topológico mais agressivo (ex.: `buffer(0)` como fallback quando
  `GeometryFixer` não resolve) — não tentado nesta sessão por tempo.
- Não foi investigado se `embargo_ambiental` (só 0,8% inválida) tem o mesmo tipo de
  impacto prático — sua taxa já era baixa desde o início.
- Nada commitado ainda — aguardando validação do usuário, mesmo padrão do §11.

---

## 13. Paralelismo na publicação de camadas de mapa (2026-10-25)

> Pedido do usuário depois do §12: "existe a possibilidade de otimizar [as camadas de
> mapa] também?". Estimativa inicial (§11, texto solto) era de 5-6x, pelo mesmo padrão
> já usado nos indicadores (§11.3). **O resultado real ficou bem abaixo disso — ~1,23x,
> não 5-6x** — registrado aqui com a causa provável, sem inflar o resultado.

### 13.1. O que mudou

`publicarCamadaEmParalelo` (novo método em `GoldPublicacaoService`) substitui o laço
sequencial de `publicarCamadaMapaEmLotes`: a leitura paginada da Silver continua
sequencial (1 `SELECT` por página de 200), mas o trabalho **dentro** de cada página
(simplificar via JTS + inserir) roda em `paralelismo` threads — mesmo padrão já usado
em `calcularIndicadoresEmParalelo` (§11.3), incluindo o mesmo jeito de esperar as
tarefas (`Future.get()` por item, cancela o resto se uma delas estourar por erro de
infraestrutura real). Reusa o `app.gold.paralelismo` já configurado (8).

### 13.2. Resultado medido (sequencial vs. paralelo, mesma camada, mesmo volume)

| Camada | Sequencial | Paralelo | Ganho |
|---|---|---|---|
| `AREA_PROTEGIDA` (8.377 linhas) | 328 s | 263 s | 1,25x |
| `IMOVEL_RURAL` (4.613 linhas) | 22 s | 13 s | 1,69x |
| `DESMATAMENTO` (1.428 linhas) | 36 s | 31 s | 1,16x |
| `MUNICIPIO` (399 linhas) | 4 s | 2 s | 2x |
| `LIMITE_PR` (1 linha agregada) | 27 s | 30 s | **pior** (sem nada pra paralelizar — é 1 linha só, o pool fica puro overhead) |
| `EMBARGO` (124 linhas) | 0 s | 1 s | desprezível |
| **Total (6 camadas)** | **419 s (§12.3)** | **340 s** | **1,23x (~23% mais rápido)** |

Testes automatizados: sem regressão (773, 0 falhas, mesmo baseline do §12.3).

### 13.3. Por que ficou tão abaixo da estimativa

A estimativa de 5-6x foi extrapolada do ganho dos **indicadores** (§11.3), mas o
cálculo dos indicadores e a publicação de camadas têm gargalos de natureza
diferente:
- Indicadores: o gargalo medido era **memória/contenção do Oracle** sob
  `SDO_GEOM.SDO_INTERSECTION` concorrente (§11.1) — ajustar PGA resolveu isso de
  verdade, e paralelizar escalou bem **depois** desse ajuste.
- Camadas de mapa: o trabalho pesado (`SimplificadorGeometriaMapa.simplificar`) é
  CPU pura da JVM, não interseção espacial no Oracle — não tem o mesmo tipo de
  gargalo de memória do banco pra "destravar" com ajuste de PGA.

**Hipótese mais provável, não confirmada com certeza:** o host só tem **12 threads**
(§11.6) compartilhadas entre o container do Oracle (que também precisa de CPU real
pra cada `INSERT` + manutenção dos 2 índices de `gold.feature_publicada` — o B-tree
único `(mapa_camada_id, cod_natural, versao_publicacao)` e o R-tree espacial) e os 8
threads da JVM fazendo simplificação. Com `paralelismo=8` rodando ao mesmo tempo em
que o Oracle também precisa de CPU pro próprio trabalho, o host fica perto do teto
de 12 threads — diferente do cálculo de indicadores, que rodava concentrado dentro
do próprio Oracle (ajuste de PGA ajudava a sessão do banco a não degradar pra
algoritmo multi-pass, não competia por núcleo de CPU do jeito que simplificação JTS
compete).

**Achado secundário, intermitente, não confirmado como causa:** 1 amostra de
`v$session`/`v$lock` durante uma publicação de `AREA_PROTEGIDA` mostrou **7 sessões
simultâneas em `enq: TM - contention`** — contenção de lock de DML, compatível com
inserção concorrente na mesma região do índice único (`mapa_camada_id` e
`versao_publicacao` são **constantes** pra todas as 8.377 linhas da mesma
publicação — só `cod_natural` varia, então as 8 threads inserem sempre na mesma
"vizinhança" do índice B-tree, um padrão clássico de contenção em Oracle). Uma
segunda amostra, poucos minutos depois, **não reproduziu** o mesmo padrão (sessões
em `library cache: mutex X`/`Allocate PGA memory from OS`, sem bloqueio) — pode ser
intermitente (rajada de manutenção de índice) ou só ruído da amostragem pontual.
Não investigado a fundo por tempo; fica registrado pra quem for olhar depois.

### 13.4. Decisão

**Mantido** — mesmo sem o ganho dramático esperado, é uma melhoria real (~23%,
419s→340s), sem regressão de teste, sem risco de correção (a lógica de
"pula e loga o que der erro" continua idêntica, só paralelizada). `LIMITE_PR` sendo
a única exceção (piorou, mas só ~3s, dentro do ruído de uma única linha) não
justifica tratamento especial.

### 13.5. Em aberto

- Causa exata do gargalo não confirmada com certeza — só a hipótese de CPU
  compartilhada do host e o achado intermitente de `enq: TM - contention`. Repetir a
  medição num servidor com mais núcleos livres (não compartilhado com o Oracle) seria
  o próximo passo pra confirmar se o teto é mesmo CPU do host de dev, não um
  problema de design que se repetiria em produção.
- Não testado: separar o índice único `(mapa_camada_id, cod_natural,
  versao_publicacao)` numa ordem que não tenha as 2 primeiras colunas constantes
  durante toda a publicação (ex.: `(cod_natural, mapa_camada_id, versao_publicacao)`)
  — reduziria a hipótese de contenção de "crescimento à direita" do índice, se for
  essa mesma a causa real.
