# Checklist de cobertura — dados exigidos pelo desafio vs. o que temos

> Cruzamento linha a linha da tabela oficial "O Desafio (6/9) | Dados Necessários,
> Fontes e Formatos" (`Desafio_API_4BD_2026-2_GeoRural_DataHub_Apresentacao_v1.pdf`)
> contra o que foi validado em `docs/fontes-de-dados.md` (v1) e
> `docs/fontes-de-dados-v2.md` (v2). Objetivo: responder com rigor — **contemplamos
> exatamente o que está sendo pedido, ou não?**
>
> Convenção: ✅ atende (fonte + formato equivalente) · ⚠️ atende parcialmente / caminho
> identificado mas não confirmado · ❌ não atende, gap real.

---

## Tabela de cruzamento

| Dado necessário (slide) | Fonte pedida | Abrangência pedida | Formatos aceitos (slide) | Indicador(es) | O que temos hoje | Formato que usamos | Bate? |
|---|---|---|---|---|---|---|---|
| Imóvel rural (perímetro) | CAR | Nacional | `.shp`, `.gpkg` | Todos | WFS `geoserver.car.gov.br` (já em produção, fatia 1) — recorte PR (RN007) | GeoJSON via WFS (arquivo estático não usado, API sim) | ⚠️ fonte certa, formato de acesso diferente — decisão consciente (ver §"Reconciliação de formato") |
| APP declarada | CAR | Nacional | `.shp`, `.geojson` | IAPP, ICV | 🔒 API oficial mapeada (`api-sicar-tema`), **sem credencial ainda** | — | ❌ **gap real, não temos hoje** |
| Reserva Legal declarada | CAR | Nacional | `.shp`, `.geojson` | IRL | Mesma situação da APP — mesma API, mesmo bloqueio | — | ❌ **gap real, não temos hoje** |
| Vegetação nativa | CAR | Nacional | `.tif` ou vetor `.shp` | ICV, IAPP | Candidato identificado (`Vegetacao_Nativa_IAT_2021`, geoportal PR) — **não testado a fundo ainda** | Vetor (FeatureServer), se confirmado | ⚠️ caminho existe, não confirmado |
| Limites de biomas | IBGE | Nacional | `.shp` | IRL | Shapefile IBGE, edição **2025** confirmada | `.shp` (idêntico ao pedido) | ✅ **exato** |
| Áreas protegidas e territórios especiais | ICMBio, MMA, FUNAI, INCRA, SFB | Nacional | `.shp` | ISAP | ICMBio (UCs) ✅ · FUNAI (TIs) ✅ · INCRA (quilombolas+assentamentos) ✅ `.zip` shapefile · **MMA**: não testado como fonte autônoma (só implícito no dataset do ICMBio/CNUC) · **SFB**: página institucional localizada, **nenhum link de download direto confirmado** | Shapefile (INCRA) / GeoJSON (ICMBio, FUNAI) | ⚠️ **3 de 5 órgãos confirmados**; MMA implícito, SFB em aberto |
| Embargos ambientais | IBAMA, ICMBio | Nacional | `.shp`, `.csv` | IAE | Geoportal PR (ArcGIS REST/JSON), 2.556 registros IBAMA+ICMBio — cobre os 2 órgãos pedidos. Link `.shp` oficial do IBAMA segue quebrado (retestado, 404 persistente) | JSON via API (não é `.shp` nem `.csv` — CSV de exemplo original (SC) só ilustrativo) | ✅ órgãos cobertos, formato equivalente (não literal) |
| Desmatamento | INPE (PRODES) | Por bioma, anual | `.shp`, `.geojson` | IDesmat | WFS TerraBrasilis, camada específica de Mata Atlântica, filtro `state='PR'`, série anual (`year`/`class_name`) | GeoJSON via WFS | ✅ **exato** (formato, granularidade e recorte batem) |
| Focos de calor | INPE (Queimadas) | Nacional, **diário** | `.csv` lat/long ou pontos `.shp` | IFC | v1: CSV **mensal** (não bate "diário") · v2: geoportal PR, `FeatureServer` com `datahora` por registro (permite granularidade diária/fina) | CSV (v1, mensal) → GeoJSON via API (v2, granular) | ⚠️→✅ **só com a fonte v2** a granularidade pedida é atendida |
| Malha municipal | IBGE | Nacional | `.shp` | Agregação | IBGE API ou geoportal PR (399 municípios confirmados) | GeoJSON via API | ✅ fonte certa, formato equivalente |

---

## Veredito

**Não, ainda não contemplamos exatamente 100% do que está pedido.** Resumindo por
severidade:

### 🔴 Gaps reais (2 linhas sem solução funcionando hoje)
- **APP declarada** e **Reserva Legal declarada** — ambas dependem da mesma API
  (`api-sicar-tema`), que existe e está documentada, mas exige credenciamento externo
  (OAuth2 + IP allowlist no Serpro) que não está em nossas mãos resolver sozinhos. Sem
  isso, **IAPP e IRL não têm fonte de dado funcionando**.

### 🟡 Parciais (2 linhas com caminho identificado, não fechado)
- **Vegetação nativa** — candidato (`Vegetacao_Nativa_IAT_2021`, geoportal PR) existe,
  mas não foi testado a fundo (schema, contagem, exemplo de dado real ainda faltam).
- **Áreas protegidas e territórios especiais** — 3 dos 5 órgãos citados pelo slide
  (ICMBio, FUNAI, INCRA) têm fonte confirmada; **MMA** não tem fonte própria validada
  (está implícito no dataset do ICMBio) e **SFB** (florestas públicas) não teve nenhum
  endpoint ou link de download confirmado nas duas rodadas de pesquisa.

### 🟢 Resolvidos com equivalência de formato, não literal (4 linhas)
Imóvel rural, embargos ambientais e malha municipal batem na **fonte** e no
**conteúdo**, mas usamos **API/GeoJSON em vez do arquivo estático Shapefile/CSV**
listado no slide — ver reconciliação abaixo.

### ✅ Exatos (2 linhas)
Limites de biomas e desmatamento batem fonte, formato **e** granularidade,
integralmente.

---

## Reconciliação de formato — por que "GeoJSON via API" em vez de "Shapefile"

O slide 6 lista os **formatos como as agências publicam o dado na origem**. Isso não
conflita com a decisão já tomada pelo time (slide 4 do próprio desafio, funcional): o
sistema aceita ingestão por **CSV, JSON e GeoJSON — não Shapefile diretamente**. Ou seja:

- Para fontes que **já expõem API/WFS** (CAR, ICMBio, FUNAI, INPE, geoportal do PR),
  consumimos direto — sem conversão, e é objetivamente mais automatizável que baixar
  arquivo `.shp` periodicamente.
- Para fontes que **só existem como Shapefile estático** (limites de biomas, INCRA
  quilombolas/assentamentos), a ingestão exige uma conversão **fora do sistema**
  (`ogr2ogr`/QGIS, Shapefile → GeoJSON) antes de entrar pela API/upload — isso já
  estava previsto desde a decisão de escopo de ingestão, não é uma pendência nova.

Essa reconciliação **não é um problema em si** — é preciso deixar explícito na
documentação/apresentação final, porque um avaliador que olhar literalmente a coluna
"formatos de origem" do slide pode perguntar "por que vocês não usam Shapefile
diretamente". A resposta é essa: manter o formato de origem intacto quando possível
(shapefile → conversão manual pontual), preferir API quando ela existe.

---

## Ações concretas para fechar os gaps (nenhuma delas é código)

1. **Iniciar o credenciamento na API `api-sicar-tema`** (Conecta Gov) — é o único item
   com prazo fora do nosso controle; quanto antes começar, menor o risco de atrasar
   IAPP/IRL. Verificar se a Visiona ou a FATEC podem patrocinar/acelerar o pedido.
2. **Testar a fundo `Vegetacao_Nativa_IAT_2021`** (geoportal do PR) — schema, contagem,
   exemplo real — para confirmar (ou descartar) como fonte de vegetação nativa/Plano B
   do ICV.
3. **Investigar SFB** (florestas públicas) — a página institucional não tem link de
   download direto visível; pode exigir navegação manual por estado no
   `gov.br/florestal` ou contato direto com o órgão.
4. **Decidir se MMA precisa de fonte própria** ou se "UC = CNUC (ICMBio/MMA)" já
   satisfaz o requisito do slide como está — é mais uma decisão de enquadramento do que
   uma pesquisa técnica pendente.

Nenhum desses 4 pontos bloqueia a fatia 1 (que já está entregue) nem o cadastro de
fontes (também entregue) — eles bloqueiam, especificamente, o **cálculo de IAPP, IRL e
parte do ISAP**, que ainda não foram desenhados.
