# Catálogo de fontes de dados — testado em 2026-09-04

> Levantamento de todos os endpoints/serviços citados na página "DADOS NECESSÁRIOS,
> FONTES E FORMATOS" da apresentação da Visiona (`Desafio_API_4BD_2026-2...`, pág. 7).
> Cada fonte foi testada de verdade (curl/WFS real) nesta data — não é uma lista teórica.
> Recorte territorial do projeto: **Paraná (PR)**, ver `CLAUDE.md` §5.6.

Convenção de status:
- ✅ **Testado, funcionando** — endpoint respondeu com dado geoespacial usável.
- ⚠️ **Funciona com ressalva** — precisa de ajuste (User-Agent, versão de protocolo, etc.).
- ❌ **Quebrado/instável** — endpoint documentado publicamente, mas não respondeu no teste.
- ❓ **Não é API/WFS** — só download manual de arquivo estático, sem serviço consultável.

---

## 1. CAR — Imóvel rural (perímetro) — ✅ já é a fonte da fatia 1

| | |
|---|---|
| Indicador(es) | Todos (base geométrica de todo cruzamento) |
| Órgão | SFB (Serviço Florestal Brasileiro) / SICAR |
| Tipo de acesso | WFS 2.0.0 (GeoServer público) |
| Endpoint | `https://geoserver.car.gov.br/geoserver/sicar/wfs` |
| Layer PR | `sicar:sicar_imoveis_pr` |
| Exemplo testado | `?service=WFS&version=2.0.0&request=GetFeature&typeNames=sicar:sicar_imoveis_pr&outputFormat=application/json&count=1` |
| Resultado | **556.563** imóveis no PR (`resultType=hits`). CRS `EPSG:4674`. Geometria `MultiPolygon`. |
| Atributos | `cod_imovel, status_imovel, dat_criacao, area, condicao, uf, municipio, cod_municipio_ibge, m_fiscal, tipo_imovel` |
| Filtro por município | `&CQL_FILTER=cod_municipio_ibge=4106902` — funciona, testado com vários municípios do PR |
| Paginação | WFS2 `count` + `startIndex` |

**⚠️ Bloqueio de TLS contra cliente Java — descoberto em 2026-09-05, ao construir a
fatia 1.** `java.net.http.HttpClient` (usado por `RestClient`/`WebClient`) recebe
`SSLHandshakeException: handshake_failure` **sempre**, mesmo restringindo a TLS 1.2 e
oferecendo os mesmos cipher suites que o `curl`. `curl`/OpenSSL **sempre** funciona
(inclusive simulando o User-Agent do Java). Confirmado com `javax.net.debug=ssl:handshake`
que a rejeição acontece na camada TLS, antes de qualquer cabeçalho HTTP — não é
rate-limit nem filtro de User-Agent, é bloqueio por *fingerprint* de TLS (JA3/JA4) de
algum WAF na frente do `geoserver.car.gov.br`. Mesma categoria de bloqueio "anti-bot"
do FUNAI (item 5), só que numa camada mais baixa.

**Workaround adotado**: `CarWfsClient` chama `curl` via `ProcessBuilder` em vez de usar
o cliente HTTP nativo do Java. Funciona de forma consistente (testado repetidas vezes).
Se algum dia trocar a imagem base do container, confirmar que `curl` continua presente.

## ⚠️ GAP encontrado — APP, Reserva Legal e Vegetação Nativa do CAR **não estão nesse WFS**

O slide pede do CAR não só o perímetro do imóvel, mas também **APP declarada**, **Reserva
Legal declarada** e **Vegetação nativa** (linhas separadas na tabela do slide 6). Enumerei
**todas** as layers do workspace `sicar:` no GeoServer público — são só 27, uma por UF,
todas do padrão `sicar_imoveis_<uf>` (perímetro de imóvel). **Não existe layer pública de
APP/RL/vegetação nesse WFS.**

Essas 3 camadas existem, mas em outro canal: o portal **`consultapublica.car.gov.br`**
("base de dados abertos" do SICAR) distribui, por UF, pacotes de shapefile com "perímetro,
APP, Reserva Legal, vegetação nativa, hidrografia, área consolidada, uso restrito,
servidão administrativa e pousio" — como **download em lote (ZIP)**, não como WFS
consultável por filtro. Testei a URL (`https://consultapublica.car.gov.br/publico/estados/downloads`)
e ela responde (**HTTP 302**, redireciona pra dentro da SPA) — está no ar, mas navegar até
o link de download exato do PR precisa ser feito manualmente (ou com engenharia reversa
da API interna da SPA, não investigado ainda).

**Impacto:** não trava a fatia 1 (que só precisa do perímetro, já coberto). Trava,
eventualmente, a Silver/Gold quando IAPP/IRL/ICV forem calculados — nesse ponto,
alguém precisa entrar manualmente em `consultapublica.car.gov.br`, baixar o ZIP do PR e
tratar como fonte `DOWNLOAD_URL`/`UPLOAD_MANUAL` (schema `fonte_dado`, §2.8), já que não
dá pra automatizar via WFS. **Ação:** registrar como item de investigação antes da fatia
que calcular IAPP/IRL/ICV — ver `CLAUDE.md` §9.3.

---

## 2. IBGE — Malha municipal — ✅

| | |
|---|---|
| Indicador(es) | Agregação por município |
| Tipo de acesso | REST (não é WFS — API própria do IBGE) |
| Endpoint | `https://servicodados.ibge.gov.br/api/v3/malhas/municipios/{codigo_ibge}?formato=application/vnd.geo+json` |
| Testado | Curitiba (4106902) → **HTTP 200** |
| Formato | GeoJSON (ou TopoJSON/SVG, por parâmetro) |
| Observação | Um município por chamada; pra malha do PR inteiro, usar `https://servicodados.ibge.gov.br/api/v3/malhas/estados/PR` ou iterar os 399 códigos. |

## 3. IBGE — Limites de biomas — ❓ (download direto, sem API)

| | |
|---|---|
| Indicador(es) | IRL (define o mínimo de Reserva Legal por bioma) |
| Tipo de acesso | Download direto de shapefile (sem WFS) |
| Endpoint | `http://geoftp.ibge.gov.br/informacoes_ambientais/estudos_ambientais/biomas/vetores/Biomas_250mil.zip` |
| Testado | **HTTP 200** (HEAD confirma que o arquivo existe) |
| Observação | `Last-Modified: 2019` nesse arquivo específico — **verificar se há edição mais recente** antes de usar como definitiva (o IBGE publicou atualização de biomas em 2025 segundo referências de terceiros, mas não achei o link oficial atualizado ainda). Estático — baixa uma vez, vira seed/carga manual, não precisa de agendamento. |

---

## 4. ICMBio/MMA — Unidades de Conservação (ISAP) — ✅

| | |
|---|---|
| Indicador(es) | ISAP |
| Órgão | ICMBio, via GeoServer da INDE (Infraestrutura Nacional de Dados Espaciais) |
| Tipo de acesso | WFS 2.0.0 |
| Endpoint | `https://geoservicos.inde.gov.br/geoserver/ICMBio/ows` |
| Layer | `ICMBio:limiteucsfederais_a` (unidades de conservação federais) |
| Exemplo testado | `?service=WFS&version=2.0.0&request=GetFeature&typeNames=ICMBio:limiteucsfederais_a&outputFormat=application/json&count=1` |
| Resultado | CRS `EPSG:4674`, `MultiPolygon`. Amostra: "PARQUE NACIONAL DO PANTANAL MATO-GROSSENSE". |
| Atributos | `nomeuc, cnuc, criacaoano, areahaalb, perimm, criacaoato, esferaadm (Federal/Estadual), grupouc (proteção integral/uso sustentável), biomas, gregional` |
| Bônus | O mesmo workspace `ICMBio:` também expõe `embargos_icmbio` (embargos aplicados pelo próprio ICMBio — complementa o item 7) e camadas de biomas (`mataatlantica_2a_atualiz`, etc.) |
| Filtro | Sem teste de `CQL_FILTER` por UF ainda — provável filtro espacial (`bbox`/`intersects` contra o limite do PR) já que UC não tem coluna de UF direta; ou filtrar por `esferaadm`/nome. A investigar na hora de construir o slice de ISAP. |

## 5. FUNAI — Terras Indígenas (ISAP) — ✅ com ressalvas técnicas

| | |
|---|---|
| Indicador(es) | ISAP |
| Tipo de acesso | WFS (GeoServer da FUNAI) |
| Endpoint | `https://geoserver.funai.gov.br/geoserver/ows` |
| Layer | `Funai:tis_poligonais` |
| ⚠️ Ressalva 1 | O WAF do servidor **bloqueia `curl` com User-Agent padrão** (retorna 403 puro do nginx) nas chamadas de `GetFeature` — funciona com `-A "Mozilla/5.0"` ou qualquer UA de navegador. `GetCapabilities` funciona com qualquer UA. |
| ⚠️ Ressalva 2 | **WFS 2.0.0 quebra** no `GetFeature` dessa layer especificamente: `java.lang.RuntimeException: ... Cannot do natural order without a primary key` (a tabela no banco deles não tem PK, e o WFS2 exige ordenação natural pra paginar com `count`). **Usar WFS 1.1.0** (`typeName` no singular, `maxFeatures` em vez de `count`) — testado e funcionando. |
| Exemplo testado (funcionando) | `?service=WFS&version=1.1.0&request=GetFeature&typeName=Funai:tis_poligonais&maxFeatures=1&outputFormat=json` com `-A "Mozilla/5.0"` |
| Resultado | **665** Terras Indígenas no Brasil todo. CRS `EPSG:4674`. `MultiPolygon`. |
| Atributos | `terrai_codigo, terrai_nome, etnia_nome, municipio_nome, uf_sigla, superficie_perimetro_ha, fase_ti (Regularizada/...), modalidade_ti, dominio_uniao` |
| Filtro por UF | `uf_sigla` existe — `CQL_FILTER=uf_sigla='PR'` deve funcionar (não testei o filtro em si, só confirmei que a coluna existe na amostra). |

## 6. INCRA — Quilombolas / assentamentos (ISAP) — ❌/❓ sem WFS estável encontrado

| | |
|---|---|
| Indicador(es) | ISAP |
| Tentativa 1 | `https://acervofundiario.incra.gov.br/i3geo/ogc/index.php?service=WFS&version=1.1.0&request=GetCapabilities` → **HTTP 200, mas devolve a página HTML do visualizador i3geo**, não um XML de capabilities. O broker OGC do i3geo precisa de parâmetros diferentes dos que testei (i3geo é uma tecnologia mais antiga e menos padronizada que GeoServer). |
| Alternativa confirmada só como portal | `https://acervofundiario.incra.gov.br/i3geo/datadownload.htm` — página de download de shapefile por UF (territórios quilombolas, assentamentos, SIGEF). Não testei a navegação completa (é uma página com formulário, não uma URL direta parametrizável por curl). |
| Ação pendente | Investigar os parâmetros corretos do broker i3geo (provavelmente precisa de `map=` apontando pro arquivo de configuração do mapa) **ou** aceitar que INCRA será sempre download manual por UF, tratado como fonte `DOWNLOAD_URL` sem automação de consulta. Não bloqueia a fatia 1. |

## 7. IBAMA — Embargos ambientais (IAE) — ⚠️ portal ok, link do dado quebrado agora

| | |
|---|---|
| Indicador(es) | IAE |
| Tipo de acesso | Portal de dados abertos (CKAN) + GeoServer próprio (`pamgia.ibama.gov.br`) |
| Portal | `https://dadosabertos.ibama.gov.br/dataset/termos-de-embargo` → **HTTP 200** |
| API do CKAN (pra descobrir a URL do recurso atual) | `https://dadosabertos.ibama.gov.br/api/3/action/package_show?id=termos-de-embargo` → **funciona**, devolve `SHP-ZIP -> https://pamgia.ibama.gov.br/geoservicos/arquivos/adm_embargo_ibama_a.shp.zip` |
| ❌ Mas o arquivo em si | `https://pamgia.ibama.gov.br/geoservicos/arquivos/adm_embargo_ibama_a.shp.zip` → **HTTP 404** (testado com HEAD e GET, com e sem User-Agent de navegador) — o link publicado no catálogo está quebrado agora. |
| ArcGIS REST (`pamgia.ibama.gov.br/server/rest/services/...`) | Tentado como alternativa — devolve `{"error":{"code":499,"message":"Token Required"}}` nesse serviço específico, ou 404 pra outro caminho tentado — **não é público sem autenticação** do jeito que testei. |
| **Fallback que já temos** | O `CLAUDE.md` §2.2 já cita um `areas_embargadas.csv` do IBAMA (SC) como exemplo em mãos — esse segue sendo o caminho mais confiável pra embargos até o link WFS/shapefile ser corrigido pelo IBAMA ou eu achar o caminho certo. Pro PR, precisaria de uma exportação nova/equivalente. |
| Ação pendente | Revalidar o link periodicamente (pode ser instabilidade temporária do servidor deles) ou contatar o suporte de dados abertos. Não bloqueia a fatia 1 (é fonte de IAE, não de imóvel). |

## 8. INPE (PRODES) — Desmatamento (IDesmat) — ✅, inclusive pra Mata Atlântica

| | |
|---|---|
| Indicador(es) | IDesmat |
| Tipo de acesso | WFS (TerraBrasilis, plataforma oficial do INPE) |
| Endpoint | `https://terrabrasilis.dpi.inpe.br/geoserver/ows` |
| Layer específica de Mata Atlântica | **`prodes-mata-atlantica-nb:accumulated_deforestation_2000`** — existe! (resolvia uma dúvida: PRODES tradicionalmente é conhecido por cobrir a Amazônia Legal, mas o INPE também mantém uma série "novo bioma" pra Mata Atlântica, Cerrado, Caatinga, Pampa e Pantanal.) |
| Exemplo testado | `?service=WFS&version=1.1.0&request=GetFeature&typeName=prodes-mata-atlantica-nb:accumulated_deforestation_2000&maxFeatures=1&outputFormat=json` |
| Resultado | CRS `EPSG:4674`, `MultiPolygon`. Amostra real: estado MG, ano 2000, fonte "FA-BIOMAS MATA ATLANTICA". |
| Atributos | `state, path_row, main_class, class_name, julian_day, image_date, year, area_km, source, satellite, sensor` |
| Filtro por UF | Existe coluna `state` (ex. `'MG'`) — `CQL_FILTER=state='PR'` deve funcionar (não testado o filtro em si). |
| ⚠️ Ponto a resolver depois | O slide pede datas de corte específicas (22/07/2008, 31/07/2019, 31/12/2020 — datas do Código Florestal), enquanto essa layer tem `year`/`image_date` por classificação anual PRODES. Mapear as datas do slide pros anos/classes certas da série é trabalho de quando o IDesmat for desenhado — não é ambíguo tecnicamente, só não foi feito ainda. |

## 9. INPE (Queimadas) — Focos de calor (IFC) — ✅

| | |
|---|---|
| Indicador(es) | IFC |
| Tipo de acesso | Download direto de CSV (sem WFS necessário — já é exatamente o formato pedido no slide 6: "CSV com lat/long") |
| Endpoint | `https://dataserver-coids.inpe.br/queimadas/queimadas/focos/csv/mensal/Brasil/focos_mensal_br_{AAAAMM}.csv` |
| Testado | `focos_mensal_br_202401.csv` → **HTTP 200** |
| Observação | CSV é nacional (Brasil inteiro) por mês — filtrar pro PR é um passo de tratamento nosso (por `lat/long` dentro do limite do estado, ou por coluna de UF se o CSV já tiver uma — não confirmei as colunas exatas, só a existência/acesso do arquivo). Tem granularidade mais fina (10 em 10 min) pra quem quiser near-real-time, mas o agregado mensal já serve pra IFC. |

---

## Resumo executivo

| Fonte | Indicador | Status | Mecanismo |
|---|---|---|---|
| CAR (imóvel) | Todos | ✅ | WFS `geoserver.car.gov.br` |
| CAR (APP/RL/veg. nativa) | IAPP, IRL, ICV | ⚠️ gap | Download em lote, `consultapublica.car.gov.br` — não automatizado ainda |
| IBGE malha municipal | Agregação | ✅ | REST `servicodados.ibge.gov.br` |
| IBGE biomas | IRL | ❓ | Shapefile estático, verificar versão mais recente |
| ICMBio (UCs) | ISAP | ✅ | WFS `geoservicos.inde.gov.br` |
| FUNAI (TIs) | ISAP | ✅ (com WFS 1.1.0 + UA de navegador) | WFS `geoserver.funai.gov.br` |
| INCRA (quilombolas) | ISAP | ❌ | Sem WFS estável achado; download manual |
| IBAMA (embargos) | IAE | ⚠️ | Portal ok, link do arquivo 404 agora |
| ICMBio (embargos) | IAE | ✅ | Mesmo WFS do item 4, layer `embargos_icmbio` — pode complementar/substituir IBAMA |
| INPE PRODES (desmatamento) | IDesmat | ✅ | WFS `terrabrasilis.dpi.inpe.br`, tem camada Mata Atlântica |
| INPE Queimadas (focos) | IFC | ✅ | CSV direto `dataserver-coids.inpe.br` |

**8 de 10 linhas do slide 6 têm caminho técnico confirmado hoje.** Os 2 pendentes (INCRA,
e o gap de APP/RL/vegetação do CAR) não bloqueiam a fatia 1 — só precisam de mais
investigação antes das fatias que dependem deles (ISAP completo e IAPP/IRL/ICV).
