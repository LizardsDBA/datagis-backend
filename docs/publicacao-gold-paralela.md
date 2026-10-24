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
