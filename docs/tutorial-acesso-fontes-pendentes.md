# Tutorial — fontes do slide oficial: o que está cadastrado e o que falta

> Testado ao vivo em 2026-10-03, cruzando as 10 linhas da tabela "O Desafio (6/9) |
> Dados Necessários, Fontes e Formatos" contra o `controle.fonte_dado` real do banco.
> Consolida e **atualiza** `docs/checklist-cobertura-fontes.md` (§"Gaps reais") e
> `docs/fontes-de-dados-v2.md` §2 com descobertas novas de hoje — algumas mudam o
> status do que estava escrito lá. Foco original (APP/RL/Vegetação) nas §1-2; visão
> completa de todas as 10 linhas do slide na §0.

## 0. Slide oficial × o que está cadastrado hoje × o que falta

Cruzamento linha a linha da tabela "O Desafio (6/9) | Dados Necessários, Fontes e
Formatos" contra `controle.fonte_dado` **consultado direto no banco em 2026-10-03**
(não é documentação antiga — são os registros reais de hoje, 12 fontes cadastradas).

| # | Dado necessário (slide) | Fonte pedida (slide) | Indicador(es) | Cadastrado no sistema hoje? |
|---|---|---|---|---|
| 1 | Imóvel rural (perímetro) | CAR | Todos | ✅ **2 fontes** — `#1` API/WFS (recorte por município) + `#12` Shapefile/upload (histórico completo, inclui imóveis cancelados) |
| 2 | APP declarada | CAR | IAPP, ICV | ❌ **não cadastrada** — bloqueada por credenciamento, §1 |
| 3 | Reserva Legal declarada | CAR | IRL | ❌ **não cadastrada** — mesmo bloqueio |
| 4 | Vegetação nativa | CAR | ICV, IAPP | ❌ **não cadastrada** — Plano B investigado hoje, não funciona ainda, §2 |
| 5 | Limites de biomas | IBGE | IRL | 🟠 **achado novo hoje: fonte validada (edição 2025, `docs/fontes-de-dados-v2.md` §4.x), mas NUNCA foi cadastrada** — é um "quase lá", não um gap de pesquisa |
| 6 | Áreas protegidas e territórios especiais | ICMBio, MMA, FUNAI, **INCRA**, SFB | ISAP | 🟡 **2 de 5 órgãos cadastrados** (`#2` ICMBio, `#8` FUNAI) — **achado novo hoje: INCRA (quilombolas+assentamentos) estava validado como ✅ funcionando desde a v2, mas também nunca foi cadastrado.** MMA/SFB seguem sem fonte encontrada (§3 do doc antigo) |
| 7 | Embargos ambientais | IBAMA, ICMBio | IAE | ✅ **4 fontes cadastradas** — `#5`/`#9` embargos (IBAMA/ICMBio) + `#10`/`#11` autos de infração (dado extra, não pedido literalmente pelo slide, mas do mesmo tema) |
| 8 | Desmatamento | INPE (PRODES) | IDesmat | ✅ `#3` |
| 9 | Focos de calor | INPE (Queimadas) | IFC | ✅ **2 fontes** — `#4` IAT/PR (granular, preferida) + `#6` CSV nacional mensal (fallback) |
| 10 | Malha municipal | IBGE | Agregação | ✅ `#7` |

**Resumindo em 3 baldes:**
- ✅ **6 das 10 linhas** do slide 100% cobertas (1, 7, 8, 9, 10 — e a 6 parcialmente).
- 🟠 **2 linhas são "trabalho de cadastro", não pesquisa** — biomas (IBGE) e INCRA já têm
  fonte confirmada, só falta o `POST /api/fontes` com os dados abaixo. **Zero
  investigação nova necessária, só tempo de alguém fazer o cadastro:**

  | Fonte | `tipo_aquisicao` | `url_origem` | `formato_esperado` |
  |---|---|---|---|
  | IBGE — Limites de biomas | `DOWNLOAD_URL` | `http://geoftp.ibge.gov.br/informacoes_ambientais/estudos_ambientais/biomas/vetores/2025_Biomas-e-Sistema-Costeiro-Marinho-do-Brasil-1-250000_shp.zip` | `SHAPEFILE` |
  | INCRA — Territórios quilombolas | `DOWNLOAD_URL` | `https://www.gov.br/insa/pt-br/centrais-de-conteudo/mapas/mapas-em-shapefile/quilombos-incra.zip` | `SHAPEFILE` |
  | INCRA — Assentamentos | `DOWNLOAD_URL` | `https://www.gov.br/insa/pt-br/centrais-de-conteudo/mapas/mapas-em-shapefile/assentamentos-sab-incra.zip` | `SHAPEFILE` |

  As 3 são `DOWNLOAD_URL`/`SHAPEFILE` estático — sem `cron_expressao`, carga manual
  pontual (baixar uma vez, subir via `IngestaoShapefileExecutor`, §9.12 do `CLAUDE.md`).
- 🔴 **3 linhas são gap de verdade** (2, 3, 4) — dependem da API SICAR Temas, sem
  alternativa equivalente confirmada ainda. Ver §1 e §2 abaixo.
- ⚪ **MMA e SFB** (dentro da linha 6) seguem sem fonte própria encontrada — não travam
  o ISAP (que já é calculável com ICMBio+FUNAI, e INCRA quando for cadastrado), é mais
  questão de cobertura literal do slide do que bloqueio funcional.

---

## TL;DR

| Indicador | Falta | Caminho A (oficial) | Caminho B (sem depender de terceiros) |
|---|---|---|---|
| **IAPP** | APP declarada do CAR | 🔒 API SICAR Temas — credenciamento pesado, ver §1 | ❌ não há alternativa equivalente conhecida |
| **IRL** | Reserva Legal declarada do CAR | 🔒 idem | ❌ idem |
| **ICV** | Vegetação nativa | 🔒 idem (tema do CAR) | ⚠️ camada do IAT existe, mas **hoje achei um problema novo**: a instância testada não tem geometria consultável — ver §2 |

**Nenhum dos dois caminhos está pronto para uso agora.** O Caminho A tem um processo real,
mas pesado (abaixo). O Caminho B precisa de mais um passo de investigação que não
estava previsto — façam ou não o credenciamento, vale separar alguém pra isso essa
semana.

---

## 1. Caminho oficial — API SICAR Temas (Conecta Gov / Serpro)

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

## 2. Caminho alternativo — vegetação nativa via geoportal do IAT (Plano B)

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

Pra quem só quer a resposta objetiva da pergunta do título — **3 gaps de verdade + 2
pendências que já têm solução conhecida, só falta cadastrar** (ver tabela completa §0):

**Gaps de verdade (pesquisa ainda não fechou o caminho):**
- **APP declarada** (indicador IAPP) — falta. Só a API SICAR Temas resolve. Credenciamento pesado (§1).
- **Reserva Legal declarada** (indicador IRL) — falta. Mesma API, mesmo bloqueio.
- **Vegetação nativa** (indicador ICV) — falta. API SICAR Temas resolveria; Plano B
  (IAT) **investigado hoje e ainda não funciona** (§2) — precisa de mais um passo antes
  de declarar resolvido ou descartado.

**Não são gaps de pesquisa, são gaps de cadastro (achado hoje, §0):**
- **Limites de biomas** (IBGE, indicador IRL) — fonte validada desde a v2, nunca virou `fonte_dado`.
- **INCRA** (quilombolas + assentamentos, parte do indicador ISAP) — mesma situação.

Os 3 primeiros bloqueiam especificamente os packages `pkg_calculo_conservacao` e metade
do `pkg_calculo_territorial` do desenho em `docs/gold-procedures-calculo-publicacao.md`
— travam só quando chegar a hora de implementar ICV/IRL/IAPP de verdade, não antes.
Os 2 últimos não bloqueiam nada tecnicamente — é só não terem sido cadastrados ainda.

---

## 4. Ação recomendada esta semana

1. **Alguém fala com o Prof. Garcia e/ou a Visiona hoje/amanhã** sobre o credenciamento
   Conecta Gov (§1) — é o item de maior prazo externo, quanto antes abrir, melhor.
2. **Alguém retesta o Plano B** (§2, item 1 e 2) — é trabalho técnico que a própria
   equipe resolve sozinha, sem depender de terceiros.
3. **Cadastrar biomas (IBGE) e INCRA agora** (§0) — 5 minutos de `POST /api/fontes` cada,
   zero pesquisa nova, fecha 2 das pendências da tabela do slide ainda esta semana.
3. Enquanto isso não resolve: **ICV, IRL e IAPP ficam como stub** nas procedures
   (`docs/gold-procedures-calculo-publicacao.md` §2) — ninguém trava esperando.
