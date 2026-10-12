# Tutorial — fontes do slide oficial: o que está cadastrado e o que falta

> Testado ao vivo em 2026-10-03, cruzando as 10 linhas da tabela "O Desafio (6/9) |
> Dados Necessários, Fontes e Formatos" contra o `controle.fonte_dado` real do banco.
> Consolida e **atualiza** `docs/checklist-cobertura-fontes.md` (§"Gaps reais") e
> `docs/fontes-de-dados-v2.md` §2 com descobertas novas de hoje — algumas mudam o
> status do que estava escrito lá. Foco original (APP/RL/Vegetação) nas §1-2; visão
> completa de todas as 10 linhas do slide na §0.
>
> **Atualização 2026-10-04:** a linha 5 (Limites de biomas) deixou de ser "só falta
> cadastrar" — foi cadastrada, ingerida e **virou seed fixo** (ver nota na tabela
> abaixo e `CLAUDE.md` §2.5/§9). `IngestaoShapefileExecutor` ganhou suporte a
> `DOWNLOAD_URL` nesse processo (antes só aceitava `UPLOAD_MANUAL`).
>
> **Segunda atualização, mesmo dia:** com `DOWNLOAD_URL` funcionando, cadastramos e
> testamos ao vivo também **MMA (CNUC nacional)** e **SFB (CNFP)** pra linha 6 — as
> duas promoveram 100% dos registros (16.059 no total). Achado grande: **67% do CNFP
> (8.671 de 12.874) resolve como `orgao=INCRA`** — o SFB sozinho já cobre boa parte
> do que as 2 fontes INCRA separadas (quilombolas/assentamentos) trariam. Com isso, a
> linha 6 do slide está **efetivamente coberta** (ICMBio + FUNAI + MMA + SFB
> cadastrados e funcionando) — INCRA como fonte própria vira opcional, não bloqueio.
>
> **🎉 Atualização 2026-10-07 — os 3 gaps reais (linhas 2/3/4, indicadores IAPP/IRL/ICV)
> foram RESOLVIDOS, sem precisar do credenciamento SICAR Temas.** Achado um serviço
> ArcGIS público no próprio geoportal IAT/PR (`00_PUBLICACOES/Base_Geo_Cadastro_
> Ambiental_rural`) com 9 camadas do CAR, incluindo **Reserva Legal, APP Total e
> Vegetação Nativa** — exatamente os 3 temas que faltavam. Verificado ao vivo via
> `curl` direto no ArcGIS REST (não é suposição): geometria real, `outSR=4674`
> funcionando, `cod_imovel` no mesmo formato da nossa Silver, `des_condic` com o
> mesmo texto que o `ValidadorDominio` já resolve. Ver **§0-bis** (nova seção, logo
> abaixo) para o achado completo — **as seções §1 e §2 abaixo ficam como registro
> histórico da investigação anterior, já não bloqueiam nada.**

## 0. Slide oficial × o que está cadastrado hoje × o que falta

Cruzamento linha a linha da tabela "O Desafio (6/9) | Dados Necessários, Fontes e
Formatos" contra `controle.fonte_dado` **consultado direto no banco em 2026-10-03**
(não é documentação antiga — são os registros reais daquele dia, 12 fontes
cadastradas). **Linha 5 atualizada em 2026-10-04** — biomas foi cadastrada entre a
versão original deste cruzamento e hoje, ver nota na própria linha.

| # | Dado necessário (slide) | Fonte pedida (slide) | Indicador(es) | Cadastrado no sistema hoje? |
|---|---|---|---|---|
| 1 | Imóvel rural (perímetro) | CAR | Todos | ✅ **3 fontes** — `#1` API/WFS (recorte por município) + `#12` Shapefile/upload (histórico completo) + `Área Imóvel (CAR)` via o serviço achado em 2026-10-07 (§0-bis), ainda não cadastrada por ser redundante |
| 2 | APP declarada | CAR | IAPP, ICV | ✅ **resolvido em 2026-10-07** — `APP Total (CAR)`, 381.962 polígonos, ver §0-bis (ainda não cadastrada no `fonte_dado`, serviço confirmado) |
| 3 | Reserva Legal declarada | CAR | IRL | ✅ **resolvido em 2026-10-07** — `Reserva Legal (CAR)`, 347.867 polígonos, ver §0-bis |
| 4 | Vegetação nativa | CAR | ICV, IAPP | ✅ **resolvido em 2026-10-07** — `Vegetação Nativa (CAR)`, 393.153 polígonos, ver §0-bis (o Plano B antigo do §2, de 2026-10-03, era um serviço diferente e sem geometria — esse achado é outro item do portal) |
| 5 | Limites de biomas | IBGE | IRL | ✅ **cadastrada e ingerida em 2026-10-04** — `DOWNLOAD_URL`+`SHAPEFILE` (precisou implementar suporte a `DOWNLOAD_URL` no `IngestaoShapefileExecutor`, que só aceitava `UPLOAD_MANUAL` até então). Geometria dos 6 biomas **virou seed fixo** (`V040`, mesmo padrão do município/`V037`) — ambiente novo já sobe com tudo geolocalizado, sem precisar disparar ingestão manual |
| 6 | Áreas protegidas e territórios especiais | ICMBio, MMA, FUNAI, **INCRA**, SFB | ISAP | ✅ **4 de 5 nominalmente cadastrados e testados em 2026-10-04** — ICMBio (`#2`), FUNAI (`#8`), MMA/CNUC nacional e SFB/CNFP (16.059 registros promovidos, 100%). INCRA não tem fonte própria cadastrada, mas **67% do SFB já resolve como `orgao=INCRA`** (assentamentos/glebas) — cobertura efetiva, não é mais gap real |
| 7 | Embargos ambientais | IBAMA, ICMBio | IAE | ✅ **4 fontes cadastradas** — `#5`/`#9` embargos (IBAMA/ICMBio) + `#10`/`#11` autos de infração (dado extra, não pedido literalmente pelo slide, mas do mesmo tema) |
| 8 | Desmatamento | INPE (PRODES) | IDesmat | ✅ `#3` |
| 9 | Focos de calor | INPE (Queimadas) | IFC | ✅ **2 fontes** — `#4` IAT/PR (granular, preferida) + `#6` CSV nacional mensal (fallback) |
| 10 | Malha municipal | IBGE | Agregação | ✅ `#7` |

**Resumindo em 3 baldes (atualizado 2026-10-07):**
- ✅ **10 das 10 linhas** do slide com fonte confirmada** — todas cobertas, incluindo
  as 3 do CAR (2, 3, 4) que até ontem eram gap real. Ver §0-bis pro achado de hoje.
- 🟡 **INCRA como fonte própria é opcional, não gap** — as 2 URLs abaixo continuam
  válidas e testáveis (`DOWNLOAD_URL`/`SHAPEFILE` já funciona de ponta a ponta, mesmo
  caminho usado pra biomas/MMA/SFB) se algum dia quiserem o dado "oficial" do INCRA
  separado, mas **não bloqueia nada** — 67% do SFB já vem com `orgao=INCRA`:

  | Fonte | `tipo_aquisicao` | `url_origem` | `formato_esperado` |
  |---|---|---|---|
  | INCRA — Territórios quilombolas | `DOWNLOAD_URL` | `https://www.gov.br/insa/pt-br/centrais-de-conteudo/mapas/mapas-em-shapefile/quilombos-incra.zip` | `SHAPEFILE` |
  | INCRA — Assentamentos | `DOWNLOAD_URL` | `https://www.gov.br/insa/pt-br/centrais-de-conteudo/mapas/mapas-em-shapefile/assentamentos-sab-incra.zip` | `SHAPEFILE` |

- ⚪ **Nenhuma linha é gap real hoje.** As 3 que dependiam do CAR (2, 3, 4) foram
  resolvidas em 2026-10-07 por um serviço público do IAT/PR, sem precisar do
  credenciamento SICAR Temas — ver §0-bis. **Falta só cadastrar as fontes e
  implementar o cálculo** (trabalho de engenharia normal, não mais bloqueio
  institucional).

---

## 0-bis. RESOLVIDO em 2026-10-07 — CAR completo via geoportal IAT/PR, sem credenciamento

Achado a partir de uma URL do portal ArcGIS do IAT/PR compartilhada diretamente:
`https://geopr.iat.pr.gov.br/portal/home/item.html?id=7cbabc15fd334a149e158ef2b4a46fa7`
— item **"Cadastro Ambiental Rural - CAR (Base Geo)"**, público (`access: public`),
mantido pelo próprio IAT/PR (mesmo geoportal que já usamos pra focos de calor,
embargos, autos de infração e terras indígenas — `CLAUDE.md` §5.7/§9.4).

### O serviço

```
https://geopr.iat.pr.gov.br/server/rest/services/00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_rural/FeatureServer
```

**9 camadas, todas `esriGeometryPolygon`**, verificadas ao vivo via `curl` direto no
ArcGIS REST (não é suposição nem resumo de IA — a primeira tentativa via `WebFetch`
não conseguiu ler a página do portal, que é renderizada via JavaScript; a confirmação
real veio consultando a API REST do ArcGIS diretamente):

| ID | Camada | Registros (PR inteiro) | Resolve |
|---|---|---:|---|
| 0 | Hidrografia (CAR) | — | fora do escopo dos 7 indicadores |
| 1 | **APP Total (CAR)** | **381.962** | **IAPP** (denominador — área total de APP) |
| 2 | **Reserva Legal (CAR)** | **347.867** | **IRL** |
| 3 | **Vegetação Nativa (CAR)** | **393.153** | **ICV** + numerador do IAPP (interseção APP ∩ Vegetação Nativa = "APP com vegetação nativa") |
| 4 | Área Consolidada (CAR) | — | fora do escopo dos 7 indicadores |
| 5 | Área Pousio (CAR) | — | fora do escopo |
| 6 | Servidão Administrativa (CAR) | — | fora do escopo |
| 7 | Uso Restrito (CAR) | — | fora do escopo |
| 8 | Área Imóvel (CAR) | 551.292 | 3ª fonte de perímetro do imóvel (redundante com `#1`/`#12`, não cadastrar por ora) |

### Por que esse achado é confiável (checklist de verificação, não só "parece bom")

1. **Geometria real, não resumo estatístico.** O dead-end anterior (`Vegetacao_
   Nativa_IAT_2021`, documentado em §2) tinha atributos ricos mas `"layers":[]` —
   zero geometria consultável. Este serviço é o oposto: testei
   `FeatureServer/2/query?...&outSR=4674&resultRecordCount=1&f=geojson` e voltou um
   `MultiPolygon` de verdade, coordenadas dentro do Paraná (`lon ≈ -54.12, lat ≈
   -24.66`), já no `crs: EPSG:4674` — confirma que o serviço aceita reprojeção pro
   nosso SRID padrão.
2. **`cod_imovel` em todas as 9 camadas, no mesmo formato da nossa Silver.** Exemplo
   real retornado: `PR-4114609-EDA00CDD383C46A9B56852EDB3B074AE` — bate exatamente
   com o padrão de `silver.imovel_rural.cod_imovel` (UF + código IBGE do município +
   hash). Junta direto, sem trabalho de reconciliação de chave.
3. **`des_condic` com o texto oficial do SICAR que o validador já conhece.** Valor
   real retornado: `"Analisado, em conformidade com a Lei n 12.651/2012"` — é
   literalmente um dos sinônimos que `ValidadorDominio` já resolve pra
   `ANALISADO_SEM_PENDENCIAS` (`CLAUDE.md` §9.17, implementado em 2026-09-13/14).
   Zero trabalho novo de normalização de domínio pra esse campo.
4. **Mesma família de serviço que já sabemos processar.** É ArcGIS REST com
   `maxRecordCount: 2000` por página — exatamente o padrão que `PaginadorUrlGeoJson`
   já pagina (`resultOffset`+`resultRecordCount`) pras 6+ fontes do IAT/PR já
   cadastradas. **Não precisa de código novo** — `IngestaoGeoJsonExecutor` processa
   assim que a fonte for cadastrada com `tipo_aquisicao=API_REST`,
   `formato_esperado=GEOJSON`, mesma URL + `outSR=4674` (padrão do §5.6/§9.4) + filtro
   de UF/PR se o serviço cobrir mais de um estado (a confirmar ao cadastrar — o
   geoportal é do IAT **do Paraná**, então é provável que já seja só PR, mas vale
   conferir contra o total antes de assumir).
5. **Público, sem credenciamento.** `access: public` no metadado do item — não
   depende do processo pesado do §1 (Conecta Gov, certificado ICP-Brasil, pedido
   institucional à SGD/ME).

### Como cada indicador fica calculável agora

- **IRL** = área de `Reserva Legal (CAR)` do imóvel ÷ área do imóvel, comparada ao
  mínimo do bioma — direto, 1 camada.
- **ICV** = área de `Vegetação Nativa (CAR)` do imóvel ÷ área do imóvel — direto, 1
  camada.
- **IAPP** = área de `APP Total (CAR)` ∩ `Vegetação Nativa (CAR)` (a "APP conservada")
  ÷ área total de `APP Total (CAR)` do imóvel — precisa interseccionar as 2 camadas,
  não é 1 camada só. Ainda assim, direto: ambas já trazem `cod_imovel`, dá pra
  recortar as duas pro mesmo imóvel e rodar `SDO_GEOM.SDO_INTERSECTION` /
  `SDO_RELATE`, mesmo padrão já usado em `pkg_calculo_territorial`/`pkg_calculo_
  ambiental` (`docs/gold-procedures-calculo-publicacao.md`).

### O que ainda falta fazer (trabalho de engenharia normal, não mais pesquisa)

1. Cadastrar as 3 fontes (`fonte_dado`) — `APP Total`, `Reserva Legal`, `Vegetação
   Nativa` do CAR, via `POST /api/fontes` ou seed de migration, mesmo padrão das
   fontes já existentes do IAT/PR.
2. Rodar uma ingestão de teste real (`POST /api/fontes/{id}/executar`) e conferir
   volume completo, tempo de paginação (300-400 mil registros cada, maior que
   qualquer fonte já ingerida hoje — `imovel_rural` tem ~4.600 reais do circuito, CAR
   completo via WFS tem 556 mil mas nunca foi ingerido de ponta a ponta) e se cai
   limpo ou gera quarentena.
3. Decidir o mapeamento Silver: essas 3 camadas não são "entidades de fato" novas
   tipo `embargo_ambiental` — são **atributos do próprio imóvel** (RL/APP/vegetação
   declaradas). Modelagem mais provável: tabelas novas tipo `silver.reserva_legal`,
   `silver.app_declarada`, `silver.vegetacao_nativa_declarada`, cada uma com
   `imovel_rural_id` (FK) + geometria + `num_area` + `ind_status`/`des_condic` — a
   confirmar antes de escrever a migration (mesmo cuidado de modelagem de
   `docs/modelagem-silver-gold-quarentena.md`).
4. Implementar `calcular_irl`, `calcular_iapp`, `calcular_icv` de verdade em
   `pkg_calculo_territorial`/`pkg_calculo_conservacao` (hoje stub, só `p_indicador_
   versao_id := NULL` — `docs/gold-procedures-calculo-publicacao.md` §2/§3.3),
   seguindo o mesmo padrão `SDO_RELATE`+índice espacial já validado pra ISAP/IAE/
   IDesmat/IFC (`CLAUDE.md` §9.33) — **não repetir o erro do `SDO_ANYINTERACT`**, usar
   `SDO_RELATE(...,'mask=ANYINTERACT')` desde o início.

**Nada disso foi feito ainda nesta atualização — só a descoberta e verificação da
fonte. Implementação fica para quando o time decidir priorizar.**

---

## TL;DR

| Indicador | Antes | **Agora (2026-10-07)** |
|---|---|---|
| **IAPP** | 🔒 API SICAR Temas — credenciamento pesado | ✅ `APP Total (CAR)` + `Vegetação Nativa (CAR)` (interseção), geoportal IAT/PR — ver §0-bis |
| **IRL** | 🔒 idem | ✅ `Reserva Legal (CAR)`, geoportal IAT/PR — ver §0-bis |
| **ICV** | 🔒 idem / Plano B do IAT sem geometria | ✅ `Vegetação Nativa (CAR)`, geoportal IAT/PR — ver §0-bis |

**Os 3 indicadores têm fonte de dado confirmada agora.** O que falta é cadastro +
implementação (§0-bis, passos 1-4), não mais pesquisa ou credenciamento externo. As
seções §1 e §2 abaixo documentam o caminho que **não** foi necessário no fim — mantidas
como registro histórico da investigação, não como próximo passo.

---

## 1. [HISTÓRICO, SUPERADO EM 2026-10-07 — ver §0-bis] Caminho oficial — API SICAR Temas (Conecta Gov / Serpro)

> Esta seção documenta o caminho que a equipe investigou **antes** de achar o serviço
> público do IAT/PR (§0-bis). Não é mais necessário segui-la — mantida só como
> registro de que o credenciamento institucional foi considerado e por que decidimos
> não seguir por ele.

### O que essa API dá, se conseguirmos acesso

`GET https://apigateway.conectagov.estaleiro.serpro.gov.br/api-sicar-tema/v1/{codigoImovel}`
— por **imóvel individual** (não por município/lote), devolve os temas declarados do CAR:
perímetro, Reserva Legal, APP (por tipo), área líquida — como um array `results[]`, cada
item com `tema`, `areaTotalTema` e a geometria (`poligonoAreaTema` ou `geoareatema`,
nome do campo não confirmado até termos acesso real — ver exemplo em
`docs/fontes-de-dados-v2.md` §2.3).

Documentação: **`https://www.gov.br/conecta/catalogo/apis/sicar-tema`** (catálogo,
spec OpenAPI completa) e **`https://doc.conectagov.estaleiro.serpro.gov.br/man/
gestorConsumidorAPIs/`** (manual de como consumir qualquer API do Conecta).

### O processo de credenciamento, passo a passo (pesquisado hoje)

1. **Autocadastro do Diretor de TI e do Responsável Técnico** da instituição que vai
   consumir a API, em `https://gestor.conectagov.estaleiro.serpro.gov.br/`.
2. **Assinatura digital de um Termo de Responsabilidade** — exige **certificado digital
   (ICP-Brasil)** de quem assina. Sem certificado, não dá pra avançar esse passo.
3. **Pedido formal de acesso ao serviço** feito por um representante do **órgão** à
   Secretaria de Governo Digital (SGD/ME) — a plataforma foi desenhada pra integração
   **entre órgãos de governo**, não pra pessoa física/empresa privada se autocadastrar
   livremente.
4. Depois de aprovado: o Gestor da instituição acessa o **Conecta Manager**, cadastra a
   aplicação que vai consumir a API e gera a chave (client id/secret, fluxo OAuth2).
5. **Antes da primeira integração**, é preciso cadastrar o(s) **IP(s) de origem** da
   nossa aplicação — o Serpro libera no firewall deles só depois disso. É o que já
   tínhamos confirmado na prática: sem IP cadastrado, a chamada trava em timeout
   (`HTTP 000`), não dá erro de autenticação — o bloqueio é de rede, antes de chegar na
   API.

### Leitura honesta pra vocês

Isso **não é um formulário de 5 minutos** — é um processo pensado pra órgão público
confiando num certificado digital institucional. Pontos que praticamente obrigam apoio
externo:
- **Quem de vocês tem certificado digital ICP-Brasil?** Provavelmente ninguém,
  individualmente. A FATEC (como instituição de ensino, que é órgão público estadual) ou
  a **Visiona** (que, sendo parceira do desafio e provavelmente já tem integrações de
  governo) são os candidatos naturais a bancar esse passo.
- **"Representante do órgão pedindo à SGD/ME"** — de novo, não é algo que um grupo de
  alunos faz sozinho; precisa de alguém com papel institucional assinando pelo pedido.

**Ação concreta: perguntar pro Prof. Garcia e/ou contato da Visiona se algum dos dois já
tem (ou pode abrir) uma conta de consumidor no Conecta Gov**, e se topam emprestar o
certificado digital/papel institucional pra esse credenciamento específico. Sem isso, o
caminho A não anda — não é uma questão de "tentar mais", é uma barreira estrutural do
processo.

---

## 2. [HISTÓRICO, SUPERADO EM 2026-10-07 — ver §0-bis] Caminho alternativo — vegetação nativa via geoportal do IAT (Plano B)

> Esta seção documenta a investigação de 2026-10-03, que testou o serviço **errado**
> do geoportal IAT (`Vegetacao_Nativa_IAT_2021`, sem geometria consultável) e concluiu
> "não está pronto". O serviço certo (`00_PUBLICACOES/Base_Geo_Cadastro_Ambiental_
> rural`, com geometria real) é **outro item do mesmo portal**, achado em 2026-10-07 —
> ver §0-bis. Mantida como registro de que o caminho errado foi tentado primeiro,
> pra quem for procurar de novo não repetir o mesmo teste.

Esse caminho só cobre **ICV** (ainda assim calculado por nós, cruzando perímetro do
imóvel × polígono de vegetação — não é o "tema pronto" do CAR). **Não ajuda IAPP nem
IRL** — essas dependem mesmo da RL/APP *declaradas no CAR*, que só a API oficial tem.

### O que achei hoje, testando ao vivo (atualiza o doc v2, que dizia "não testado")

Existem **pelo menos 7 serviços** no geoportal do IAT com "vegetação" no nome
(`Hosted/Vegetacao_Nativa_IAT_2021`, `vegetacao_nativa_2021_iat`,
`Porcentagem_Vegetacao_Nativa_Municipio`, `Vegetacao_Nativa_pcts`,
`Vegetacao_estatistica`, `Vegetacao_Nativa_Terra_Indigena`, `Vegetação_2012_2021`).
Testei os dois mais prováveis:

| Serviço | Tipo | O que achei |
|---|---|---|
| `Hosted/Vegetacao_Nativa_IAT_2021` (`FeatureServer`) | **Tabela, sem geometria** (`"layers":[]`, só `"tables"`) | **197.963 registros** com atributos ricos (`area_ha`, `bioma`, `municipio`, `codibge`, `nivel_i/ii/iii`, `fito`) — mas **a geometria não vem na consulta**. Parece ser um resumo estatístico por polígono, não o polígono em si. |
| `Hosted/vegetacao_nativa_2021_iat` (existe só como `MapServer`, não `FeatureServer`) | Layer 0 "Vegetação Nativa 2021 - IAT" existe, mas **a consulta (`/query`) devolveu erro 500** | Não confirmei se dá pra extrair geometria daqui — pode ser camada só de renderização (ex.: suporta `/export` pra imagem, não `/query` pra feição) |
| `Porcentagem_Vegetacao_Nativa_Municipio`, `Vegetacao_Nativa_pcts`, `Vegetacao_estatistica` | — | Consulta de metadata falhou nos 3 (sem resposta válida) — não deu pra nem confirmar se existem como testados aqui; pode ter sido instabilidade pontual do servidor, preciso retestar com calma |

**Conclusão de hoje: o Plano B não está pronto.** O candidato mais óbvio
(`Vegetacao_Nativa_IAT_2021`) tem o dado estatístico certo mas **sem a geometria que
precisamos pra fazer a interseção com o imóvel** — não dá pra calcular "quanto da área
do imóvel X é vegetação nativa" sem o polígono da vegetação. Isso é diferente do que
`docs/fontes-de-dados-v2.md` registrava ("candidato identificado, não testado") — agora
sabemos que **esse candidato específico, do jeito que está exposto, não serve**.

### Próximos passos técnicos pro Plano B (antes de desistir dele)

1. Re-testar `vegetacao_nativa_2021_iat` (MapServer) com outros parâmetros de query —
   pode ser limite de registros/timeout no servidor, não ausência de geometria de fato.
2. Checar se existe uma versão "não-Hosted" da mesma camada (o geoportal do IAT tem
   outras pastas além de `Hosted`, catalogadas mas não exploradas — `docs/fontes-de-
   dados-v2.md` §0 menciona 212 serviços só na pasta `Hosted`, sem contar as outras).
3. Se nada funcionar: procurar a mesma informação em **MapBiomas** (coleção de uso do
   solo do Brasil inteiro, série histórica, é open data com API própria — ainda não
   investigado neste projeto) como terceira opção, fora do IAT e fora do CAR.

---

## 3. Lista final — "quais fontes faltam", direto ao ponto

**Atualizado em 2026-10-07: nenhuma fonte falta mais.** As 10 linhas do slide oficial
têm caminho técnico confirmado (ver tabela completa §0). O que resta é cadastro +
implementação, não pesquisa — ver §0-bis, passos 1-4.

**Resolvido em 2026-10-04:**
- **Limites de biomas** (IBGE, indicador IRL) — cadastrada, ingerida, geometria virou
  seed fixo (`V040`) — não depende de ninguém disparar nada.
- **Áreas protegidas e territórios especiais** (indicador ISAP) — ICMBio/FUNAI já
  cadastrados antes; MMA (CNUC) e SFB (CNFP) cadastrados e testados agora, 16.059
  registros promovidos. INCRA como fonte própria fica opcional (§0) — 67% do SFB já
  resolve como `orgao=INCRA`.

**Resolvido em 2026-10-07:**
- **APP declarada, Reserva Legal declarada e Vegetação nativa** (indicadores IAPP,
  IRL, ICV) — serviço público do IAT/PR achado e verificado ao vivo, sem precisar do
  credenciamento SICAR Temas. Ver §0-bis pro achado completo e os 4 passos que ainda
  faltam (cadastrar fonte → ingerir → modelar Silver → implementar `calcular_*`).

Os 3 indicadores que bloqueavam `pkg_calculo_conservacao` e metade do
`pkg_calculo_territorial` (`docs/gold-procedures-calculo-publicacao.md`) agora têm
fonte — a implementação real fica pra quando o time priorizar (§0-bis, passos 1-4).
Nenhuma procedure de cálculo dos 7 indicadores oficiais depende mais de fonte faltando.

---

## 4. Ação recomendada

1. **Cadastrar as 3 fontes do CAR achadas em 2026-10-07** (`APP Total`, `Reserva
   Legal`, `Vegetação Nativa`) e rodar uma ingestão de teste real — ver §0-bis passos
   1-2. Isso é trabalho que a própria equipe resolve sozinha, sem depender de
   terceiros nem de credenciamento.
2. **Decidir a modelagem Silver** pras 3 camadas novas (§0-bis passo 3) antes de
   escrever a migration — são atributos do imóvel (RL/APP/vegetação declaradas), não
   uma entidade de fato nova como `embargo_ambiental`.
3. **Implementar `calcular_irl`, `calcular_iapp`, `calcular_icv` de verdade**
   (§0-bis passo 4), usando `SDO_RELATE` desde o início (não repetir o achado do
   `SDO_ANYINTERACT`, `CLAUDE.md` §9.33).
4. O processo de credenciamento Conecta Gov (§1) e o retest do Plano B antigo (§2)
   **não são mais necessários** — podem ser descartados da lista de pendências do
   time, a menos que surja outro motivo pra precisar da API oficial do SICAR
   (ex.: algum tema que o serviço do IAT não cubra).
5. Decidir, sem pressa (não bloqueia nada): categorização fina de `area_protegida`
   pro MMA/SFB (`categoria_area_protegida`/`tipo_area_protegida` ainda não cobrem
   Projeto de Assentamento/Território Quilombola/Gleba Arrecadada) e se vale cadastrar
   o INCRA separado ou deixar só a cobertura via SFB.
