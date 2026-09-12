# Upload de Shapefile + validação CAR (WFS × bulk) — `LIZARDS-3` (2026-09-11)

> Tudo que foi feito na branch `feat/upload-shp` (backend e frontend), a partir da
> `sprint-1` já com a modelagem Silver/Gold/Quarentena (`LIZARDS-2`) mesclada.
> Escopo: ingestão via **upload manual** de arquivo (não só API/URL), primeira fonte
> real implementada com isso: CAR (SICAR) **Imóveis (PR) via Shapefile**.

---

## 1. Por que isso existe

O CAR (SICAR) tem uma segunda forma de distribuir os mesmos dados de imóvel, além do
WFS já usado desde a fatia 1 (`docs/fontes-de-dados.md`/`-v2.md`): download em lote de
Shapefile por estado em `https://consultapublica.car.gov.br/publico/estados/downloads`.

**Essa página não dá pra automatizar por HTTP simples.** É um app Play Framework
(sessão via cookie `PLAY_SESSION`) servido como SPA — testado com `curl` (com e sem
`-L`, com User-Agent de navegador) e sempre cai num loop de redirect
(`/publico/imoveis/index` → `/publico/imoveis/index` → … até `curl: (47) Maximum (50)
redirects followed`). Já tinha sido documentado como bloqueado em
`docs/fontes-de-dados-v2.md` §2.2; confirmado de novo nesta rodada.

**Decisão:** em vez de reverse-engenheirar a sessão JS (ou automatizar com
Selenium/Playwright, desproporcional pro projeto), a pessoa baixa o `.zip` no
navegador dela (onde funciona) e sobe pro DataGis por upload manual. O sistema já
tinha o `tipo_aquisicao = UPLOAD_MANUAL` previsto desde o início do cadastro de fontes
— só faltava a execução de verdade pra esse tipo, que era um item conhecido em aberto.

**Vantagem inesperada dessa fonte**: o bulk download traz **campos que o WFS não
tem** — ver §5.

---

## 2. Arquitetura — o que foi construído

Segue o mesmo princípio de toda a ingestão (`IngestaoExecutor` + dispatch por
`formatoEsperado`/`tipoAquisicao`, CLAUDE.md §5.8): peça nova e isolada, nada do que já
existia (`IngestaoGeoJsonExecutor`, `IngestaoCsvExecutor`, cadastro de fontes) foi
alterado em comportamento. **Exceção, 2026-09-11 (§3.5):** `LoteIngestaoWriter` ganhou
um método novo (`gravarStreaming`) pro caso do Shapefile — o método antigo (`gravar`,
baseado em `List`) continua existindo do mesmo jeito pros outros dois executores.

| Peça | Arquivo | Papel |
|---|---|---|
| `controle.fonte_dado.caminho_arquivo_upload` | `V024` | Endereço do último arquivo enviado (não é URL — fontes `UPLOAD_MANUAL` não têm `url_origem`) |
| Fonte semeada | `V025` | `CAR - Imóveis (PR) via Shapefile (upload)` — `UPLOAD_MANUAL` + `SHAPEFILE`, pra comparar com a fonte 1 (via WFS) |
| `ArmazenamentoArquivoBruto` (interface) | `armazenamento/ArmazenamentoArquivoBruto.java` | Interface fina já prevista desde o início (CLAUDE.md §5.4), nunca implementada até agora |
| `ArmazenamentoArquivoBrutoLocalFs` | `armazenamento/ArmazenamentoArquivoBrutoLocalFs.java` | Única implementação hoje — disco local do container (`/tmp/datagis-uploads` por padrão, configurável via `app.armazenamento.diretorio`). Nome salvo com `UUID` (não só timestamp — dois uploads no mesmo milissegundo colidiam, achado escrevendo o teste) |
| `IngestaoShapefileExecutor` | `ingestao/IngestaoShapefileExecutor.java` | `formatoEsperado=SHAPEFILE`. Descompacta o `.zip` (achata subpastas), abre cada `.shp` com GeoTools, converte geometria (JTS nativo) pra WKT com o mesmo `WKTWriter` do `GeoJsonWktMapper`, atributos viram `payload` JSON. Mesma resiliência dos outros executores: geometria ausente/malformada não derruba o registro. **Lê em streaming, não junta feições numa lista (§3.5)** |
| `FornecedorRegistros` (interface) | `ingestao/FornecedorRegistros.java` | Adicionada em §3.5 — quem lê a fonte entrega um `RegistroPreparado` por vez pro consumidor, em vez de devolver uma `List` pronta |
| `LoteIngestaoWriter.gravarStreaming(...)` | `ingestao/LoteIngestaoWriter.java` | Adicionado em §3.5 — mesmo ciclo de vida do `gravar` original, mas consumindo um `FornecedorRegistros` em vez de uma `List` |
| `IngestaoExecucaoService.executarComUpload(...)` | `ingestao/IngestaoExecucaoService.java` | Salva o arquivo via `ArmazenamentoArquivoBruto`, grava o endereço na fonte, e dispara o **mesmo** `executar()` genérico de sempre — o upload não duplica lógica de disparo |
| `POST /api/fontes/{id}/upload` | `ingestao/IngestaoController.java` | `multipart/form-data`, campo `arquivo` |
| Botão de upload na tela | `datagis-frontend/src/components/CadastroFontes.vue` | Fontes `UPLOAD_MANUAL` ganham um ícone de upload no lugar do play (que ficava sempre desabilitado); reaproveita o mesmo painel de resultado (executando/sucesso/erro) que o play já usava |

**Item do spike de compatibilidade finalmente exercitado** (CLAUDE.md §5.5, item 3):
GeoTools (`gt-shapefile`, no `pom.xml` desde o início, nunca usado) agora lê um `.shp`
de verdade, inclusive um arquivo de produção de 218 MB / ~560 mil feições, sem erro.

---

## 3. Descobertas técnicas (guardar pra não perder tempo de novo)

### 3.1. Pacotes do GeoTools 35.1 — `org.geotools.api.*`, não `org.opengis.*`

GeoTools 30+ renomeou o pacote de interfaces `org.opengis.*` pra `org.geotools.api.*`.
Confirmado inspecionando os `.jar` de verdade (`jar tf ... | grep`) em vez de assumir —
mesma lição do Jackson 3 (CLAUDE.md §5.5 item 6). Mapa completo:

| Classe | Pacote real (35.1) |
|---|---|
| `DataStore`, `DataStoreFinder`, `SimpleFeatureSource`, `Transaction` | `org.geotools.api.data.*` |
| `SimpleFeature`, `Property` | `org.geotools.api.feature.*` / `org.geotools.api.feature.simple.*` |
| `SimpleFeatureIterator` | `org.geotools.data.simple.*` — **não** é `.api`, pegadinha |
| `ShapefileDataStore`, `ShapefileDataStoreFactory` | `org.geotools.data.shapefile.*` (concreto, não `.api`) |
| `SimpleFeatureTypeBuilder`, `SimpleFeatureBuilder`, `DefaultFeatureCollection` | `org.geotools.feature.*` / `org.geotools.feature.simple.*` (concreto) |
| `DefaultTransaction` | `org.geotools.data.DefaultTransaction` (concreto; a interface `Transaction` é `.api`) |

Geometria de cada feição já vem como `org.locationtech.jts.geom.Geometry` (GeoTools usa
JTS por baixo) — não precisa de nenhum parser próprio, só `WKTWriter.write(geometria)`.

### 3.2. `unzip` não existe na imagem do backend

A imagem do backend é JDK puro (`eclipse-temurin:21-jdk-jammy`), sem `unzip`. Pra
inspecionar `.jar` de dentro do container, usar `jar tf <arquivo>` (vem com o JDK) em
vez de `unzip -l`.

### 3.3. Limite de upload — 100 MB não bastava, foi pra 2 GB, e depois 2 GB também não bastou

Achado ao vivo: o arquivo de teste (Ponta Grossa+Maringá+Curitiba não, na verdade o
Shapefile é **estadual inteiro**, sem filtro por município como o WFS tem) tem
**218 MB, e é um dos mais leves — tem casos de 1 GB+**. O default do Spring
(`spring.servlet.multipart.max-file-size`) é 1 MB; configuramos 100 MB numa primeira
tentativa, que também não bastou.

**Sintoma (arquivo ainda dentro do teto configurado):** não aparece erro nenhum no
backend — nem exceção, nem `ORA-`. A requisição chega (`Initializing Spring
DispatcherServlet`), e a conexão simplesmente é resetada pelo Tomcat no meio do
recebimento do corpo. No navegador isso vira `Failed to fetch` (erro de rede, sem
resposta HTTP nenhuma) — não confundir com falha de ingestão de verdade (essas aparecem
com mensagem real, via `TratadorDeErro`).

**1ª correção (não durou):** subiu pra 2 GB em quatro propriedades (uma pode não bastar
dependendo da versão do Tomcat/Spring Boot):
```properties
spring.servlet.multipart.max-file-size=2GB
spring.servlet.multipart.max-request-size=2GB
server.tomcat.max-http-form-post-size=2GB
server.tomcat.max-swallow-size=2GB
```

**Estourou nesse teto também, 2026-09-11:** um `.zip` real de **3,24 GB**
(`3477706048` bytes) bateu em cheio no limite de 2 GB (`2147483648` bytes — o número
que aparece na mensagem de erro é literalmente `2GB` em bytes). Dessa vez o erro veio
com mensagem clara (`SizeLimitExceededException`), diferente do sintoma silencioso de
antes — sinal de que o teto em si é checado antes do corpo ser todo recebido, não é o
Tomcat resetando a conexão sem avisar.

**2ª correção — parar de perseguir um teto fixo:** em vez de subir pra 4 GB, 8 GB etc.
a cada arquivo maior do CAR (o portal não dá nenhuma garantia de tamanho máximo por
estado), as 4 propriedades foram trocadas pra **sem limite** (`-1`):
```properties
spring.servlet.multipart.max-file-size=-1
spring.servlet.multipart.max-request-size=-1
server.tomcat.max-http-form-post-size=-1
server.tomcat.max-swallow-size=-1
```
Exige restart do container `backend` pra valer (sem `spring-boot-devtools` no projeto,
o Maven não recarrega `application.properties` sozinho). **Isso não resolve §3.4** — só
tira o teto de tamanho de upload; o processamento síncrono em memória continua sem
mudar, e é o próximo limite real a aparecer se um arquivo muito maior que os já
testados travar ou estourar memória do container.

### 3.4. Processamento é síncrono — pode levar minutos

Hoje todo o ciclo (upload → descompacta → lê → grava no Bronze) acontece dentro da
mesma requisição HTTP, sem chunk/lote/paralelismo — **isso não mudou** com a correção
do §3.5 abaixo, só a forma de ler/gravar mudou. **Testado ao vivo: 218 MB / 559.899
feições levou ~3 minutos**, sem erro. Não foi redesenhado pra processamento assíncrono
porque funcionou dentro do que foi testado; fica documentado como ponto de atenção se
um arquivo bem maior começar a demorar demais ou estourar timeout de algum componente
no meio do caminho (proxy do Vite) — o risco de **memória**, que era o mais óbvio aqui,
foi endereçado no §3.5.

### 3.5. `OutOfMemoryError` real com um arquivo de 3,24 GB — corrigido com leitura/gravação em streaming

Aconteceu exatamente o que o parágrafo acima (na versão anterior deste documento) já
apontava como risco: um upload de **3,24 GB** (bem maior que o de 218 MB já testado)
derrubou a ingestão com:
```
java.lang.OutOfMemoryError: Java heap space
  at IngestaoExecucaoService.executarComUpload(...)
```

**Causa raiz:** `IngestaoShapefileExecutor` lia **todas** as feições do Shapefile pra
uma `List<RegistroPreparado>` (JSON + WKT de cada uma) antes de gravar qualquer linha —
por desenho, já que `LoteIngestaoWriter.gravar` precisa do hash do conteúdo inteiro pra
decidir "mudou desde a última execução" (RN018/§2.8) antes de começar a escrever. Pra
560 mil feições (218 MB) isso cabia no heap padrão do container (~2 GB, ergonomia da
JVM); pra um arquivo bem maior, não cabia mais.

**Correção — ler e gravar em streaming, nunca materializar tudo:**
- Nova interface `FornecedorRegistros` (`void fornecer(Consumer<RegistroPreparado>)`) —
  quem lê a fonte (o executor) chama o consumidor um registro por vez, em vez de
  devolver uma lista pronta.
- `IngestaoShapefileExecutor` não monta mais `List<RegistroPreparado>`: itera cada
  `.shp` com `SimpleFeatureIterator` e entrega cada feição direto pro consumidor.
- `LoteIngestaoWriter.gravarStreaming` (novo método; o `gravar(List)` original continua
  existindo, ainda usado pelos executores GeoJSON/CSV, que não tinham esse problema)
  grava cada `RegistroBruto` assim que chega e calcula o hash **incrementalmente**
  (`MessageDigest.update` por registro, em vez de `String.join` de tudo antes de
  hashear).
- **Só se sabe se "mudou" depois de gravar tudo** — inversão consciente: se o hash final
  bater com o do lote anterior, os registros recém-gravados são apagados **em bloco**
  (`DELETE ... WHERE lote_ingestao_id = ?`, não um por linha) e o lote vira
  `SEM_MUDANCA`. Mais I/O que o caminho antigo no caso "sem mudança", mas não exige mais
  caber a fonte inteira na memória pra chegar nessa resposta.
- **Achado ao implementar, não só teórico:** ler e gravar um registro por vez resolve a
  lista da aplicação, mas o Hibernate mantém toda entidade `save()`-ada como "managed"
  no *persistence context* da sessão até a transação terminar — pra milhões de linhas
  isso vira o **mesmo estouro de heap, só que dentro do JPA**. Corrigido com
  `EntityManager.flush()` + `clear()` a cada 500 registros dentro do loop de gravação.
- Testes atualizados/criados: `LoteIngestaoWriterTest` ganhou 2 casos pro
  `gravarStreaming` (conclui gravando tudo; descobre "sem mudança" só no fim e apaga em
  bloco); `IngestaoShapefileExecutorTest` passou a capturar o `FornecedorRegistros`
  entregue ao writer em vez de uma lista. 40 testes de `ingestao`+`controle`, todos
  passando.
- **Não testado ainda com o próprio arquivo de 3,24 GB** que causou o OOM (só validado
  com os testes unitários acima + um upload pequeno de verdade) — é o próximo passo
  óbvio antes de considerar isso definitivamente resolvido pra esse tamanho.

---

## 4. Teste ao vivo — resultado real

Upload de `.zip` do Shapefile estadual completo (perímetro de imóvel, tema
`AREA_IMOVEL`) pela fonte `CAR - Imóveis (PR) via Shapefile (upload)`:

```
Concluído — lote #1, 559.899 registros gravados.
```

218 MB, ~3 minutos, sem erro. Bate na faixa do que já era conhecido pro estado inteiro
(`docs/fontes-de-dados.md`: `numberMatched = 556.563` via WFS em 2026-09-04) — a
diferença é esperada, são snapshots de datas diferentes (o CAR muda o tempo todo).

---

## 5. WFS × Shapefile — mapeamento de campos e achados

### 5.1. Nomes de campo diferentes (DBF trunca em 10 caracteres)

| WFS/API (`sicar:sicar_imoveis_pr`) | Shapefile bulk (DBF) | Observação |
|---|---|---|
| `cod_imovel` | `cod_imovel` | **igual nos dois** — é a chave de comparação entre as fontes |
| `tipo_imovel` | `ind_tipo` | `"IRU"` nos dois |
| `dat_criacao` | `dat_criaca` | formato diferente: ISO (`2016-01-27T13:10:50Z`) vs `DD/MM/AAAA` |
| `status_imovel` | `ind_status` | ver §5.3 — valores `AT`/`CA`/`PE` |
| `uf` | `cod_estado` | `"PR"` nos dois |
| `condicao` | `des_condic` | texto pode variar ligeiramente (ex. "Aguardando análise" vs "Em analise") |
| `area` | `num_area` | hectares |
| `municipio` | `municipio` | igual, mas só o nome (sem código IBGE — só dá pra extrair o código de dentro do `cod_imovel`, formato `PR-<cod_ibge>-<hash>`) |
| `m_fiscal` | `mod_fiscal` | módulos fiscais |
| — | `cod_tema` / `nom_tema` | **só existe no Shapefile.** Valor visto: `"AREA_IMOVEL"` / `"Area do Imovel"`. Ver §5.4 — pista pra APP/RL/Vegetação Nativa |
| — | `dat_atuali` | data da última atualização do registro (não existe equivalente no WFS testado) |

### 5.2. Contagem por município (circuito de teste) — WFS × Shapefile

Comparado via `cod_imovel` (chave idêntica nos dois formatos):

| Município | Qtd Shapefile | Qtd WFS/API | Em comum (join por `cod_imovel`) |
|---|---|---|---|
| Curitiba | 120 | 111 | 120 * |
| Maringá | 1.841 | 1.830 | 1.838 * |
| Ponta Grossa | 2.668 | 2.649 | 2.658 * |

`*` **"Em comum" maior que "Qtd WFS/API" não é bug de query — é sintoma da duplicata
real dentro do Shapefile, ver §5.3.** Uma primeira versão da comparação (JOIN simples
por `cod_imovel`) multiplicou os matches por causa disso; documentado aqui pra não
confundir alguém revisitando essa análise.

### 5.3. Achado real: o Shapefile bulk traz histórico, o WFS só traz o vigente

Investigando por que `qtd_shapefile > qtd_api` e por que o JOIN dava número maior que
qualquer um dos dois lados: existem `cod_imovel` duplicados **dentro do próprio
Shapefile** (achado com `GROUP BY ... HAVING COUNT(*) > 1` — ex.: um `cod_imovel` em
Ponta Grossa aparecia 4 vezes).

Inspecionando as 4 cópias: **geometria e área idênticas**, só muda `ind_status`/
`des_condic`:
- 1 cópia: `ind_status="AT"`, `des_condic="Aguardando analise"` — o registro vigente
- 3 cópias: `ind_status="CA"`, `des_condic="Cancelado por duplicidade"` — **o próprio
  CAR já identificou como duplicata e cancelou**, mas manteve no arquivo pra
  rastreabilidade

Distribuição completa de `ind_status` pro circuito de Ponta Grossa no Shapefile:

| Status | Qtd | Significado |
|---|---|---|
| `AT` | 2.598 | Ativo |
| `CA` | 41 | Cancelado (inclui as duplicatas) |
| `PE` | 29 | Pendente |

`AT` sozinho (2.598) já fica próximo do `qtd_api` (2.649); a diferença residual é
esperada por causa da diferença de data entre os dois snapshots — **não é algo pra
"consertar"**.

**Conclusão:** as duas fontes descrevem o mesmo cadastro; a diferença de comportamento
é que **o bulk download expõe o histórico completo do CAR** (ativo + cancelado +
pendente) e **o WFS aparentemente só devolve o vigente**. Isso é uma característica
real de cada canal, não uma divergência de dado.

**Impacto na transformação Bronze→Silver (ainda não implementada, mas já registrado
em `docs/modelagem-silver-gold-quarentena.md` §3.5):** a regra de dedup desenhada lá
("pega o lote mais recente") cobre duplicata **entre lotes diferentes**. Este caso é
diferente — é duplicata **dentro do mesmo lote**, por desenho da própria fonte. A
transformação vai precisar de uma regra explícita pra isso: manter só
`situacao_imovel = 'AT'` (e possivelmente `PE`) como o imóvel "oficial" pra
`silver.imovel_rural`; os `CA` não são erro de dado (não vão pra quarentena) — são
histórico legítimo, e podem simplesmente não entrar na Silver, ou entrar numa tabela de
histórico separada se algum dia for necessário auditar cancelamentos.

### 5.4. Pista não perseguida ainda: `cod_tema`/`nom_tema`

O único tema visto neste teste foi `AREA_IMOVEL` (perímetro do imóvel) — é a mesma
informação que a fonte 1 (WFS) já ingere. **Não foi testado** se o mesmo Shapefile (ou
outro arquivo do mesmo portal) traz `cod_tema` diferente (`APP`, `RESERVA_LEGAL`,
`VEGETACAO_NATIVA`, etc.) — se sim, pode ser que a distinção entre APP/RL/Vegetação
não precise de um `.shp` por tema (como estava assumido em
`docs/modelagem-silver-gold-quarentena.md` §6, "quando a API SICAR Temas destravar"),
e sim de um único arquivo com `cod_tema` discriminando as linhas. **Vale investigar
antes de desenhar `silver.area_declarada_car`** — pode simplificar o executor (nenhum
código novo, só ler o `cod_tema` de cada feição) em vez de precisar de lógica por
arquivo.

---

## 6. Itens em aberto / próximos passos

- **Download automático do CAR bulk continua bloqueado** (SPA com sessão, `curl` cai em
  loop de redirect) — upload manual é o caminho por enquanto, funcionando bem.
- **`silver.area_declarada_car`** (ou nome equivalente) ainda não foi criada — depende
  de investigar o achado do §5.4 primeiro (pode mudar o desenho).
- **Regra de `situacao_imovel` na transformação Bronze→Silver** — anotado aqui e em
  `docs/modelagem-silver-gold-quarentena.md` §3.5, ainda não implementado (a
  transformação em si não existe ainda).
- **Limite de tamanho de upload removido (2026-09-11)** — as 4 propriedades de
  multipart/Tomcat (§3.3) viraram `-1` (sem limite) depois que um `.zip` de 3,24 GB
  estourou o teto de 2 GB. Sem contenção nenhuma de tamanho hoje — se isso for um
  problema (disco/memória de quem sobe o ambiente), considerar validar tamanho antes de
  aceitar o upload, não só confiar no bom senso de quem envia.
- **Processamento continua síncrono** (§3.4) — a memória (§3.5) foi resolvida com
  streaming, mas a requisição HTTP ainda fica presa até o fim do processamento. Um
  arquivo grande o suficiente ainda pode estourar timeout de algum componente no meio
  do caminho (proxy do Vite). Se isso aparecer, o próximo passo é processar em
  background (`202 Accepted` na hora do upload + thread/Job assíncrono, ou finalmente
  ligar o Spring Batch que está no `pom.xml` desde o início e nunca foi usado —
  CLAUDE.md §3.3).
- **`OutOfMemoryError` com arquivo de 3,24 GB — corrigido com streaming (§3.5)**, mas
  **ainda não re-testado com esse mesmo arquivo** depois da correção — validado só por
  teste unitário + upload pequeno de verdade. É o próximo passo antes de considerar o
  caso resolvido de ponta a ponta.
- **Gravação ainda é 1 `INSERT` por linha** (`LoteIngestaoWriter`, sem batch de verdade
  — o streaming do §3.5 só evita acumular tudo em memória, não agrupa os `INSERT`s) —
  funcionou para 559.899 linhas em ~3 min; se um arquivo bem maior ficar lento demais,
  é candidato a `saveAll`/batch insert do Spring Data ou o Spring Batch de verdade.
