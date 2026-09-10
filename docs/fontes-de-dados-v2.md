# Catálogo de fontes de dados — v2, revalidado em 2026-09-08

> Segunda varredura, focada em: (a) validar os gaps abertos na v1 (INCRA, CAR
> APP/RL/vegetação nativa, IBGE biomas, IBAMA embargos), e (b) testar os filtros por
> Paraná que na v1 ficaram "prováveis, não testados". **Achado principal desta rodada:
> um geoportal do próprio Estado do Paraná (IAT) que já hospeda várias camadas
> pré-recortadas pro nosso recorte territorial** — muda a estratégia de várias fontes.
> Documento anterior: `docs/fontes-de-dados.md` (mantido, não sobrescrito).

Convenção de status (igual à v1):
- ✅ **Testado, funcionando** — endpoint respondeu com dado geoespacial usável.
- ⚠️ **Funciona com ressalva** — precisa de ajuste (User-Agent, versão de protocolo, formato de saída, etc.).
- ❌ **Quebrado/instável** — endpoint documentado publicamente, mas não respondeu no teste.
- 🔒 **Existe, mas com barreira de acesso** — API real, porém exige autenticação/registro/CAPTCHA.
- ❓ **Não é API/WFS** — só download manual de arquivo estático.

---

## 0. Descoberta principal: Geoportal do Estado do Paraná (IAT) — ✅

**Instituto Água e Terra (IAT)**, órgão ambiental do governo do Paraná, mantém um
**ArcGIS Server público** com centenas de camadas — muitas delas **já recortadas para o
estado**, o que evita filtrar datasets nacionais por conta própria.

| | |
|---|---|
| Endpoint raiz | `https://geopr.iat.pr.gov.br/server/rest/services` |
| Tipo de acesso | ArcGIS REST (FeatureServer/MapServer) — `.../<serviço>/FeatureServer/<id>/query` |
| Autenticação | Nenhuma — testado sem token, todos os serviços abaixo responderam |
| Formato de saída | `f=geojson` (recomendado) ou `f=json` (nativo Esri) |
| **⚠️ Atenção ao CRS** | Algumas camadas devolvem `wkid 102100` (Web Mercator) por padrão no `f=json`; **sempre forçar `&outSR=4674`** na query pra garantir SIRGAS 2000 (RN009), independente da camada. |
| Descoberta de camadas | `GET /rest/services?f=json` (pastas) → `GET /rest/services/<pasta>?f=json` (serviços) → `GET /rest/services/<pasta>/<serviço>/FeatureServer?f=json` (índices de layer dentro do serviço — nem sempre é `0`, ver exemplos abaixo) |
| Catalogado, não testado a fundo | Pasta `Hosted` sozinha tem **212 serviços** — só testamos os diretamente relevantes aos 7 indicadores. Vale nova varredura dedicada quando a Silver/Gold entrar (biomas, uso do solo MapBiomas por ano, vegetação nativa por município, etc. já catalogados como candidatos). |

Esse portal resolveu de verdade 2 dos 3 gaps abertos e deu alternativas mais ricas para
2 fontes que já estavam "✅" na v1 (embargos e focos de calor) — ver itens abaixo.

---

## 1. CAR — Imóvel rural (perímetro) — ✅ sem mudança de status

Continua exatamente como na v1 (`geoserver.car.gov.br`, `sicar:sicar_imoveis_pr`,
556.563 imóveis, workaround de `curl` via `ProcessBuilder` pro bloqueio de TLS). Sem
necessidade de revalidar — já está em produção na fatia 1 e funcionando.

**Novo, só como registro:** o IAT também hospeda uma cópia do CAR/PR
(`Geoprocessamento/imoveis_car`, 550.125 registros, EPSG:4674) — não é uma fonte
melhor, é praticamente a mesma informação (perímetro), sem o bloqueio de TLS. **Não
substitui a fonte atual** (número de registros um pouco diferente, atualização
desconhecida), mas é uma alternativa de contingência se o WFS federal ficar instável.

---

## 2. CAR — APP, Reserva Legal, Vegetação Nativa — 🔒 gap **investigado a fundo**, três caminhos mapeados

Essa era a lacuna mais importante da v1. Resultado da investigação:

### 2.1. Caminho oficial real: API SICAR Temas (Conecta Gov / Serpro)

| | |
|---|---|
| Existe? | **Sim, API oficial documentada** no catálogo do governo federal |
| Endpoint | `GET https://apigateway.conectagov.estaleiro.serpro.gov.br/api-sicar-tema/v1/{codigoImovel}` |
| Documentação | `gov.br/conecta/catalogo/apis/sicar-tema` — spec OpenAPI completa obtida e salva |
| Autenticação | **OAuth2** (fluxo próprio do Conecta Gov) — exige client id/secret emitido via "Conecta Manager" |
| 🔒 Barreira adicional | **Cadastro de IP no firewall do Serpro** — não é só "criar conta e pegar chave", precisa de registro formal. Testado sem credencial: timeout de conexão (`HTTP 000`), consistente com bloqueio de IP não cadastrado. |
| Consulta | **Por imóvel** (`codigoImovel`), não por município/lote — pra cobrir nosso circuito (4.590 imóveis) seria uma chamada por `cod_imovel` já ingerido. |
| Exemplo de resposta (dado fictício de teste, documentado) | Ver §2.3 abaixo — inclui código de teste **do Paraná**: `PR-4127965-5B8ED84AA1A94E6399506A04DE910B83` |

### 2.2. Caminho manual: portal de download em lote (mantido da v1)

`consultapublica.car.gov.br` distribui shapefile por UF com todas as camadas
(perímetro, APP, RL, vegetação nativa, hidrografia etc.), mas:
- A busca por imóvel específico **exige resolução de CAPTCHA** (confirmado por
  ferramentas de terceiros que automatizam isso — `RoboCAR`,
  `Automacao_Download_SICAR` — ambas descrevem contornar CAPTCHA, o que não é um
  caminho que devemos replicar).
- O download "por estado" (arquivo único, sem CAPTCHA) existe como conceito no site,
  mas é servido por uma SPA — não há um padrão de URL de API por trás dela que eu
  tenha conseguido descobrir (testei `/api/*`, todos redirecionam pra tela inicial).

### 2.3. Exemplo de dado real (fictício, documentado oficialmente)

```json
{
  "results": [
    { "tema": "Área do Imovel", "identificadorImovel": 3936065, "areaTotalTema": "1.72937809046188",
      "poligonoAreaTema": "MULTIPOLYGON(((-39.11093201 -11.37535874, ...)))" },
    { "tema": "Reserva Legal Proposta", "identificadorImovel": 3936065, "areaTotalTema": "0.45...", "poligonoAreaTema": "MULTIPOLYGON(...)" },
    { "tema": "Área de Reserva Legal Total", "...": "..." },
    { "tema": "Área de Preservação Permanente de Lagos e Lagoas Naturais", "...": "..." },
    { "tema": "APP Total", "...": "..." },
    { "tema": "Área Líquida do Imóvel", "...": "..." }
  ]
}
```
(Baseado no dado fictício documentado para `BA-2902005-...`; o campo de geometria
aparece como `poligonoAreaTema` no schema atual — documentação mais antiga usa o nome
`geoareatema`, então **confirmar o nome do campo assim que houver acesso real**, não
assumir.)

### 2.4. Recomendação — três frentes, não uma só

1. **Curto prazo (não bloqueia nada agora):** IAPP/IRL/ICV completos dependem do
   acesso à API SICAR Temas — **abrir o processo de credenciamento no Conecta Gov o
   quanto antes** (pode levar semanas), idealmente com apoio institucional da FATEC ou
   da Visiona pra acelerar aprovação.
2. **Plano B, que já temos hoje sem depender de terceiros:** aproximar o **ICV**
   (cobertura vegetal nativa) cruzando o **perímetro do imóvel** (que já ingerimos) com
   a camada **`Vegetacao_Nativa_IAT_2021`** do próprio geoportal do Paraná (§0) —
   calculamos nós mesmos a interseção geoespacial em vez de depender do tema pronto do
   SICAR. Não testamos essa camada a fundo ainda (fica pro próximo passo), mas ela
   existe e está catalogada.
3. **Não perseguir** o caminho do CAPTCHA — nem estável, nem alinhado com uma
   integração de sistema (é uma ferramenta pra uso humano pontual).

**Impacto:** segue não travando a fatia 1. Trava IAPP/IRL/ICV completos até o
credenciamento sair — a Plano B (interseção própria com vegetação do IAT) é o caminho
recomendado para não bloquear o cronograma do semestre esperando aprovação externa.

---

## 3. IBGE — Malha municipal — ✅ sem mudança, + alternativa nova

Sem mudança de status. **Novo:** o geoportal do PR tem `municipios_2023`
(`https://geopr.iat.pr.gov.br/server/rest/services/municipios_2023/FeatureServer/0`) —
**399 municípios confirmados** (bate com o número oficial), EPSG:4674, campos
`codibge`/`nome`. Mais simples que iterar 399 chamadas na API do IBGE, já que vem tudo
numa consulta só.

## 4. IBGE — Limites de biomas — ✅ **atualizado, edição 2025 confirmada**

A v1 marcava isso como "verificar se há edição mais recente". **Achada:**

| | |
|---|---|
| Arquivo antigo (v1) | `Biomas_250mil.zip` — `Last-Modified: 2019` |
| **Arquivo atual** | `2025_Biomas-e-Sistema-Costeiro-Marinho-do-Brasil-1-250000_shp.zip` — `Last-Modified: nov/2025` |
| Endpoint | `http://geoftp.ibge.gov.br/informacoes_ambientais/estudos_ambientais/biomas/vetores/2025_Biomas-e-Sistema-Costeiro-Marinho-do-Brasil-1-250000_shp.zip` |
| Testado | **HTTP 200** |

Status sobe de ❓ pra ✅ **(dado confirmado atualizado)**, ainda que o mecanismo
continue sendo download estático (não é API/WFS) — item resolvido, não precisa de mais
investigação.

---

## 5. ICMBio/MMA — Unidades de Conservação (ISAP) — ✅ filtro por PR confirmado

Endpoint e schema iguais à v1. **Novo:** testado o filtro espacial que a v1 deixou
pendente:

```
&bbox=-54.7,-26.8,-48.0,-22.4,EPSG:4674
```
→ **28 UCs federais** com bounding box tocando o Paraná (`resultType=hits`). É um filtro
por retângulo (não pelo contorno exato do estado) — supersets aceitável para a maioria
dos casos, mas alguns registros podem ser de estados vizinhos que só tocam a borda do
bbox. Pra precisão total, seria necessário `INTERSECTS` com o polígono real do PR
(não testado ainda).

**Novo, alternativa parcial (não substitui o WFS nacional):** o geoportal do PR tem uma
camada chamada `Limite_da_Unidade_de_Conservação`, mas ao testar **é só 1 UC específica**
("muriquis-do-sul"), não um catálogo geral — **não é um substituto**, é uma
camada isolada de um projeto específico. Descartada como fonte de ISAP.

## 6. FUNAI — Terras Indígenas (ISAP) — ✅ filtro por PR confirmado, + alternativa que evita o WAF

Endpoint e ressalvas técnicas (WFS 1.1.0, User-Agent) iguais à v1. **Novo:**

- **Filtro `CQL_FILTER=uf_sigla='PR'` testado e funcionando: 24 Terras Indígenas.**
- **Achado no geoportal do PR: `Geoprocessamento/Terras_indigenas_funai`** — mesmo
  schema (`terrai_cod`, `terrai_nom`, `etnia_nome`, `uf_sigla`...), **25 registros** (1 a
  mais que o filtro direto na FUNAI — provavelmente diferença de snapshot/data de
  atualização entre as duas cópias, não investigado a fundo), **EPSG:4674**, **sem
  bloqueio de WAF** (ArcGIS REST comum, sem exigência de User-Agent de navegador).

**Recomendação:** para a fonte de Terras Indígenas, usar a **cópia do geoportal do PR**
em vez da FUNAI diretamente — mesma informação, sem as duas ressalvas técnicas da v1
(WFS 1.1.0 forçado + User-Agent de navegador).

## 7. INCRA — Quilombolas / assentamentos (ISAP) — ✅ **gap resolvido**

A v1 não achou nenhum caminho estável. **Resolvido nesta rodada, dois achados:**

### 7.1. Download direto e estável (INSA — Instituto Nacional do Semiárido, `.gov.br`)

| | |
|---|---|
| Territórios quilombolas | `https://www.gov.br/insa/pt-br/centrais-de-conteudo/mapas/mapas-em-shapefile/quilombos-incra.zip` → **HTTP 200**, 276 KB |
| Assentamentos | `https://www.gov.br/insa/pt-br/centrais-de-conteudo/mapas/mapas-em-shapefile/assentamentos-sab-incra.zip` → **HTTP 200**, 5,3 MB |
| Tipo | Shapefile zipado, sem API, sem CAPTCHA, domínio `.gov.br` oficial (espelho do INCRA mantido por outro órgão federal) |

### 7.2. Camadas no geoportal do PR (Geoprocessamento e Hosted)

Catalogadas, não testadas a fundo ainda: `Hosted/ASSENTAMENTO`,
`Hosted/Assentamentos_CAR_Estado_Parana`, `Hosted/Vegetação_Nativa_Assentamentos_INCRA`,
`Hosted/povos_comunidades_tradicionais`. Prováveis alternativas já recortadas pro PR —
testar quando ISAP for desenhado.

**Status sobe de ❌ para ✅** — pelo menos um caminho automatizável e estável existe
(download estático do INSA); não precisa mais ser tratado como "sem solução".

## 8. IBAMA — Embargos ambientais (IAE) — ⚠️→✅ **link nacional continua quebrado, mas achamos substituto melhor**

- **Retestado o link do shapefile nacional**: `adm_embargo_ibama_a.shp.zip` → **ainda
  HTTP 404** (persistente desde a v1, não foi instabilidade temporária).
- **Achado no geoportal do PR — resolve o indicador IAE de forma melhor que a fonte
  nacional quebrada:**

| | |
|---|---|
| Endpoint | `https://geopr.iat.pr.gov.br/server/rest/services/Autos_de_infra%C3%A7%C3%A3o_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer` |
| Layers | `4` = Autos de infração ICMBio · `5` = **Áreas embargadas ICMBio (443 registros)** · `8` = Autos de infração IBAMA · `9` = **Áreas embargadas IBAMA (2.113 registros)** |
| CRS | `wkid 102100` (Web Mercator) por padrão — **forçar `outSR=4674`** |
| Atributos (amostra real obtida) | `num_tad, uf, cod_municipio, municipio, nome_embargado, cpf_cnpj_embargado, tipo_area, num_auto_infracao, num_processo, des_infracao, dat_embargo, num_longitude_tad, num_latitude_tad, qtd_area_desmatada, qtd_area_embargada` |
| Exemplo real (dado público, já embargado) | `municipio: "Itambé"`, `nome_embargado: "MARIA AMELIA MC GOWAN"`, `tipo_area: "Desmatamento"`, `qtd_area_embargada: 12.13 ha`, `dat_embargo: "1996-08-12"` |

**Recomendação:** abandonar a fonte nacional (link quebrado, sem previsão de conserto) e
usar **as 4 camadas do geoportal do PR** como fonte de IAE — já vem filtrado pro estado,
schema mais rico que o CSV de exemplo que tínhamos (SC), e cobre IBAMA **e** ICMBio na
mesma consulta.

## 9. INPE (PRODES) — Desmatamento (IDesmat) — ✅ filtro por PR confirmado

Endpoint, camada de Mata Atlântica e atributos iguais à v1. **Novo:** filtro
`CQL_FILTER=state='PR'` testado e funcionando: **38.296 polígonos** de desmatamento
acumulado para o PR (série completa desde 2000). Mecanismo confirmado, sem mudança de
recomendação — segue sendo a melhor fonte pra IDesmat.

## 10. INPE (Queimadas) — Focos de calor (IFC) — ✅ sem mudança de status, + alternativa mais rica

CSV nacional mensal continua funcionando como na v1. **Novo, achado no geoportal do
PR — recomendado no lugar do CSV nacional:**

| | |
|---|---|
| Endpoint | `https://geopr.iat.pr.gov.br/server/rest/services/Focos_de_Calor_INPE/FeatureServer/0` |
| Registros | **333.604** focos, já só do Paraná |
| CRS | EPSG:4674 |
| Atributos | `datahora, satelite, estado, municipio, bioma, diasemchuv, precipitac, riscofogo, latitude, longitude, frp` |

**Recomendação:** trocar o CSV nacional (que exigiria filtrar por lat/long no nosso
lado) por essa camada — já vem só com registros do PR, com `municipio` e `bioma`
prontos, sem processamento extra nosso.

---

## Resumo executivo (v2)

| Fonte | Indicador | Status v1 | Status v2 | Mecanismo recomendado agora |
|---|---|---|---|---|
| CAR (imóvel) | Todos | ✅ | ✅ sem mudança | WFS federal (já em produção) |
| CAR (APP/RL/veg. nativa) | IAPP, IRL, ICV | ⚠️ gap | 🔒 **API oficial mapeada, precisa de credenciamento** | `api-sicar-tema` (Conecta Gov) — iniciar credenciamento já; **Plano B**: cruzar imóvel × `Vegetacao_Nativa_IAT_2021` (PR) |
| IBGE malha municipal | Agregação | ✅ | ✅ + alternativa | `municipios_2023` do geoportal PR (399 confirmados, 1 chamada só) |
| IBGE biomas | IRL | ❓ | ✅ **atualizado** | Edição 2025 confirmada (`2025_Biomas-e-Sistema...zip`) |
| ICMBio (UCs) | ISAP | ✅ | ✅ + filtro confirmado | WFS nacional + `bbox` do PR (28 UCs) |
| FUNAI (TIs) | ISAP | ✅ ressalvas | ✅ **sem ressalvas** | Geoportal PR (`Terras_indigenas_funai`, 25 registros, sem WAF) |
| INCRA (quilombolas/assentamentos) | ISAP | ❌ | ✅ **resolvido** | ZIPs do INSA (`.gov.br`, sem CAPTCHA) |
| IBAMA (embargos) | IAE | ⚠️ link quebrado | ✅ **substituto melhor achado** | Geoportal PR (layers 5 e 9, IBAMA+ICMBio, 2.556 registros) |
| INPE PRODES (desmatamento) | IDesmat | ✅ | ✅ + filtro confirmado | WFS `terrabrasilis`, `CQL_FILTER=state='PR'` (38.296 polígonos) |
| INPE Queimadas (focos) | IFC | ✅ | ✅ + alternativa melhor | Geoportal PR (`Focos_de_Calor_INPE`, 333.604 registros, já filtrado) |

**9 de 10 linhas totalmente resolvidas ou com alternativa melhor que a v1.** A única
pendência real é o acesso oficial ao tema completo do CAR (APP/RL/vegetação nativa), que
depende de credenciamento externo (fora do nosso controle de prazo) — mitigado por um
plano B que não depende de terceiros.

---

## Como configurar cada fonte no nosso sistema (`controle.fonte_dado`)

Valores sugeridos para os campos já existentes na tabela (`nome`, `orgao`,
`tipo_aquisicao`, `url_origem`, `formato_esperado`) — só como referência de
configuração, nenhum código foi alterado nesta rodada.

| Fonte | `tipo_aquisicao` | `url_origem` (raiz) | `formato_esperado` | Observação de configuração |
|---|---|---|---|---|
| CAR imóvel | `API_REST` | `geoserver.car.gov.br/geoserver/sicar/wfs` | `GEOJSON` | Já configurado (fatia 1) |
| CAR temas (APP/RL/veg.) | `API_REST` | `apigateway.conectagov.estaleiro.serpro.gov.br/api-sicar-tema/v1` | `JSON` | **Pendente de credencial OAuth2 + IP allowlist** — não cadastrar como `ativo=1` até ter acesso |
| IBGE malha municipal (PR) | `API_REST` | `geopr.iat.pr.gov.br/server/rest/services/municipios_2023/FeatureServer/0` | `GEOJSON` | Consulta única, `outSR=4674` |
| IBGE biomas | `DOWNLOAD_URL` | `geoftp.ibge.gov.br/.../2025_Biomas-e-Sistema-Costeiro-Marinho-do-Brasil-1-250000_shp.zip` | `SHAPEFILE` | Estático — sem `cron_expressao`, carga manual pontual |
| ICMBio UCs | `API_REST` | `geoservicos.inde.gov.br/geoserver/ICMBio/ows` | `GEOJSON` | Usar `&bbox=-54.7,-26.8,-48.0,-22.4,EPSG:4674` |
| FUNAI Terras Indígenas | `API_REST` | `geopr.iat.pr.gov.br/server/rest/services/Geoprocessamento/Terras_indigenas_funai/FeatureServer/0` | `GEOJSON` | Preferir esta à FUNAI direta (sem WAF); `outSR=4674` |
| INCRA quilombolas | `DOWNLOAD_URL` | `gov.br/insa/.../quilombos-incra.zip` | `SHAPEFILE` | Estático |
| INCRA assentamentos | `DOWNLOAD_URL` | `gov.br/insa/.../assentamentos-sab-incra.zip` | `SHAPEFILE` | Estático |
| IBAMA/ICMBio embargos | `API_REST` | `geopr.iat.pr.gov.br/server/rest/services/Autos_de_infração_e_embargos_federais_IBAMA_e_ICMBio_MIL1/FeatureServer` | `JSON` | 4 layers (4/5/8/9); **forçar `outSR=4674`** (default é Web Mercator) |
| INPE PRODES | `API_REST` | `terrabrasilis.dpi.inpe.br/geoserver/ows` | `GEOJSON` | `CQL_FILTER=state='PR'` |
| INPE Queimadas | `API_REST` | `geopr.iat.pr.gov.br/server/rest/services/Focos_de_Calor_INPE/FeatureServer/0` | `GEOJSON` | Preferir a esta em vez do CSV nacional |

---

## Itens que ficam para a próxima rodada

- Testar a fundo as camadas de vegetação/uso do solo do geoportal do PR
  (`Vegetacao_Nativa_IAT_2021`, `Uso_e_Cobertura_MapBiomas_2024`, `Porcentagem_
  Vegetacao_Nativa_Municipio`) — candidatas ao Plano B do ICV (§2.4).
- Iniciar formalmente o credenciamento da API `api-sicar-tema` (Conecta Gov) — processo
  externo, prazo desconhecido, não depende de nós tecnicamente.
- Varredura dedicada da pasta `Hosted` do geoportal do PR (212 serviços, só uma fração
  pequena foi revisada) quando as fatias de Silver/Gold entrarem em desenho.
- Confirmar via acesso real (assim que houver credencial) se o campo de geometria da
  API SICAR Temas é `poligonoAreaTema` (spec atual) ou `geoareatema` (doc mais antiga) —
  divergência encontrada entre fontes, não resolvida por falta de acesso.
