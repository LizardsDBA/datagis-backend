# Motor de Validações — Bronze → Silver / Quarentena

> Documento de referência do pacote `br.gov.sp.cps.datagis.validacao`.
> Atualizado em: **2026-09-13**

---

## 1. Objetivo

Fornecer uma esteira extensível de validações que atua **antes** que um registro Bronze seja
promovido para a Silver. Registros que falham em qualquer regra são redirecionados para a
Quarentena (`quarentena.*`) com tipo de erro, campo e motivo detalhados, para revisão humana
posterior.

---

## 2. Arquitetura — Strategy / Chain of Responsibility

```
RegistroBruto (Bronze)
        │
        ▼
┌───────────────────┐
│  SanitizadorEntrada │  ← sempre executa primeiro
└───────────────────┘
        │  valor sanitizado
        ▼
┌───────────────────┐
│  ValidadorCpfCnpj  │  ← executa se suporta() = true
└───────────────────┘
        │
        ▼
  (próximos validadores...)
        │
        ▼
   ┌─────────┐      ┌──────────────┐
   │  Silver │  ou  │  Quarentena  │
   └─────────┘      └──────────────┘
```

Cada validador implementa `ValidadorCampo`:

```java
public interface ValidadorCampo {
    boolean suporta(ContextoValidacaoCampo contexto);
    ResultadoValidacao validar(Object valor, ContextoValidacaoCampo contexto);
}
```

---

## 3. Contratos base

### `ContextoValidacaoCampo` (record + @Builder)
Carrega os metadados de `controle.mapeamento_coluna` para a coluna em processamento:

| Campo | Tipo | Descrição |
|---|---|---|
| `colunaOrigem` | `String` | Nome da coluna no payload Bronze |
| `atributoDestino` | `String` | Nome do campo na Silver |
| `destinoTipo` | `String` | Tipo de dado destino |
| `tipoDado` | `String` | Dica semântica do campo (ex.: `cpf`, `cnpj`) |
| `obrigatorio` | `boolean` | Gera erro se valor resultar em null |
| `valorMinimo` | `Double` | Limite numérico inferior |
| `tabelaReferencia` | `String` | Tabela de domínio na Silver |
| `referenciaEntidadeSilver` | `String` | Integridade cruzada |
| `validarCpfCnpj` | `boolean` | Ativa `ValidadorCpfCnpj` |
| `validarGeometria` | `boolean` | Ativa validador de geometria |
| `validarRecortePr` | `boolean` | Ativa validador de recorte PR |
| `impedirRegressao` | `boolean` | Ativa validador de regressão |
| `regrasExtras` | `String` | JSON para regras futuras |

### `ResultadoValidacao` (record imutável)

```java
record ResultadoValidacao(
    boolean valido,
    Object valorTransformado,   // valor limpo/normalizado (sucesso)
    String tipoErro,            // código do tipo_erro_quarentena
    String campoProblema,
    String valorRecebido,
    String motivoRejeicao)
```

Construtores estáticos: `ResultadoValidacao.sucesso(valor)` e `ResultadoValidacao.erro(tipo, campo, valor, motivo)`.

---

## 4. Validadores implementados

### 4.1. `SanitizadorEntrada` — [FEITO, 2026-09-13]

**Executa em todos os campos** (`suporta()` sempre retorna `true`).

| Entrada | Saída |
|---|---|
| `null` | `null` |
| `""`, `"   "`, `"\t"` | `null` |
| `"NULL"`, `"null"`, `"Null"` | `null` |
| `"N/A"`, `"n/a"` | `null` |
| `"-"` | `null` |
| `"  Curitiba  "` | `"Curitiba"` (trim) |
| Qualquer não-String | objeto preservado |

Se `obrigatorio = true` e o valor sanitizado resultar em `null` → erro `CAMPO_OBRIGATORIO_AUSENTE`.

**Testes:** `SanitizadorEntradaTest` — 26 cenários.

---

### 4.2. `ValidadorCpfCnpj` — [FEITO, 2026-09-13]

**Ativa quando** `validarCpfCnpj = true` **ou** `tipoDado` ∈ `{cpf, cnpj, cpf_cnpj}` (case-insensitive).

**Pipeline interno:**
1. `null` / string vazia → `sucesso(null)` (já tratado pelo Sanitizador).
2. `replaceAll("\\D", "")` — remove `.`, `-`, `/`, espaços e qualquer não-dígito.
3. Comprimento ≠ 11 e ≠ 14 → erro `CPF_CNPJ_INVALIDO`.
4. Regex `(\d)\1{10}` (CPF) / `(\d)\1{13}` (CNPJ) → rejeita dígitos repetidos.
5. Módulo 11 CPF (dois passes com pesos decrescentes 10→2 e 11→2).
6. Módulo 11 CNPJ (dois passes com pesos `PESOS_CNPJ_DIGITO1` e `PESOS_CNPJ_DIGITO2`).
7. Sucesso → `valorTransformado` = apenas os dígitos normalizados.

**Helpers estáticos públicos:** `isCpfValido(String)` e `isCnpjValido(String)`.

**Testes:** `ValidadorCpfCnpjTest` — 18 cenários.

### 4.3. `ValidadorDominio` — [FEITO, 2026-09-13, Fase 2.3 | EVOLUÍDO 2026-09-14]

**Ativa quando** `tabelaReferencia` está configurada em `mapeamento_coluna` (não nula e não em branco).

**Mecanismo de funcionamento:**
1. `null` ou string em branco → `sucesso(null)` (responsabilidade do `SanitizadorEntrada`).
2. Sanitiza o nome da tabela (remove prefixo `silver.` se fornecido e valida contra `^[a-zA-Z0-9_]+$` para proteção contra SQL Injection).
3. Consulta o **cache thread-safe** em memória (`ConcurrentHashMap<String, Map<String, String>>`).
4. **Lazy loading**: na primeira ocorrência da tabela, executa `SELECT codigo, descricao FROM {silverSchema}.{tabela} WHERE ativo = 1`.
   - Se a tabela não possuir coluna `descricao`, aplica fallback para `SELECT codigo FROM {silverSchema}.{tabela} WHERE ativo = 1`.
5. Mapeia em memória tanto códigos quanto descrições apontando para o código canônico persistido na Silver, com indexação multi-chave tolerante:
   - **Case-insensitive**: busca direta pelo texto original em maiúsculas (`toUpperCase(Locale.ROOT)`).
   - **Insensibilidade a Acentos e Diacríticos**: normalização NFD (`\p{M}`) que casa automaticamente `"Aguardando análise"` com `'Aguardando analise'`.
   - **Tolerância a Separadores**: equivalência fonética entre sublinhado, espaço e hífen (`_`, ` `, `-`).
   - **Normalização Alfanumérica**: ignora pontuação, vírgulas, parênteses e caracteres como `º`.
   - **Resolução Semântica de Sinônimos e Sub-estados Oficiais**: mapeia expressões oficiais da Lei 12.651/2012 do SICAR/CAR para códigos canônicos:
     - `"Analisado, em conformidade com a Lei nº 12.651/2012"` $\to$ `ANALISADO_SEM_PENDENCIAS`
     - `"Analisado, aguardando regularização ambiental..."` $\to$ `ANALISADO_COM_PENDENCIAS`
     - `"Aguardando análise, após revisão ou atendimento..."` $\to$ `AGUARDANDO_ANALISE`
     - `"Cancelado por decisão administrativa"` $\to$ `CANCELADO_ADM`
6. Se o valor recebido não existir no domínio ativo → erro `VALOR_FORA_DO_DOMINIO` com detalhes completos do campo, valor e tabela.
7. Se existir → `sucesso(codigoCanonico)` (normaliza para o código canônico).

**Preparação para Ação do Analista / Revalidação:**
- Métodos públicos de gestão de cache:
  - `invalidarCache(String tabelaReferencia)`: remove o cache da tabela específica para recarregar do banco após o analista aprovar/cadastrar um novo código.
  - `invalidarTodoCache()`: limpa o cache de todas as referências em memória.
  - `isEmCache(String tabelaReferencia)` e `getQuantidadeTabelasEmCache()`: inspeção e monitoramento do cache.

### 4.4. `ValidadorIntegridadeCruzada` — [FEITO, 2026-09-13, Fase 2.4]

**Ativa quando** `referenciaEntidadeSilver` está configurada em `mapeamento_coluna` (não nula e não em branco).

**Mecanismo de funcionamento:**
1. `null` ou string em branco → `sucesso(null)` (responsabilidade do `SanitizadorEntrada`).
2. Interpreta a referência configurada, suportando:
   - Nome simples de entidade: `municipio`, `imovel_rural`, `area_protegida`, `auto_infracao`, `embargo_ambiental` (resolvendo a coluna canônica por convenção, ex.: `municipio` → `cod_ibge`).
   - Prefixo de schema: `silver.municipio` → `municipio`.
   - Especificação explícita de coluna: `municipio.cod_ibge` ou `outra_tabela.minha_coluna`.
3. Mitiga SQL Injection validando identificadores de tabela e coluna via regex `^[a-zA-Z0-9_]+$`.
4. **Estratégia híbrida de cache thread-safe:**
   - **Tabelas dimensionais/estáticas** (`silver.municipio`): carga em lote sob demanda de todos os códigos cadastrados (`SELECT cod_ibge FROM silver.municipio`).
   - **Tabelas dinâmicas volumosas** (`imovel_rural`, etc.): consulta pontual com cache de chaves já verificadas (`SELECT COUNT(1) ... WHERE chave = ?`).
5. Se o registro referenciado não existir na Silver → erro `REFERENCIA_NAO_ENCONTRADA`.
6. Se existir → `sucesso(valor)`.

**Preparação para Ação do Analista / Revalidação:**
- Métodos de gestão de cache: `invalidarCache(String referencia)` e `invalidarTodoCache()`.

### 4.5. `ValidadorFaixaMinima` — [FEITO, 2026-09-13, Fase 2.5]

**Ativa quando** `valorMinimo` está configurado em `mapeamento_coluna` (não nulo).

**Mecanismo de funcionamento:**
1. `null` ou string em branco → `sucesso(null)` (responsabilidade do `SanitizadorEntrada`).
2. Converte com tolerância o valor de entrada para `Double`:
   - Aceita tipos `Number` (Integer, Long, Double, BigDecimal, etc.).
   - Suporta formatos de texto com ponto decimal (`"15.5"`), vírgula decimal brasileira (`"15,5"`), separadores de milhar (`"1.250,50"` e `"1,250.50"`) e símbolos de moeda (`"R$ 50,00"`).
3. Se o valor textual não for conversível para número → erro `TIPO_DE_DADO_INVALIDO`.
4. Se o valor numérico for estritamente menor que `valorMinimo` → erro `VALOR_ABAIXO_DO_MINIMO`.
5. Se for maior ou igual → `sucesso(valorNumerico)` (normalizado para `Double`).

**Testes:** `ValidadorFaixaMinimaTest` — 23 cenários.

### 4.6. `ValidadorGeometria` — [FEITO, 2026-09-13, Fase 2.6]

**Ativa quando** `validarGeometria = true`, `validarRecortePr = true`, `destinoTipo = 'GEOMETRIA'` ou `tipoDado` ∈ `{wkt, geojson}`.

**Mecanismo de funcionamento:**
1. `null` ou string em branco:
   - Se `obrigatorio = true` → erro `GEOMETRIA_NULA`.
   - Se opcional → `sucesso(null)`.
2. **Parsing**: converte texto WKT ou GeoJSON para geometria JTS (`Geometry`).
   - Se a sintaxe estiver corrompida → erro `GEOMETRIA_MALFORMADA`.
3. **Validação Topológica (JTS `IsValidOp`)**:
   - Analisa auto-interseções, auto-loops ou anéis desconexos.
   - Se inválida → erro `GEOMETRIA_INVALIDA` com detalhamento da inconsistência e coordenada do erro.
4. **Compatibilidade de Primitivo**:
   - Compara o tipo real com a exigência configurada em `regrasExtras` ou `tipoDado` (ex.: `POLYGON`, `POINT`, `LINESTRING`).
   - Se incompatível → erro `GEOMETRIA_TIPO_INCOMPATIVEL`.
5. **Recorte Territorial do Paraná (`validarRecortePr = true`)**:
   - Verifica se o envelope da geometria intercepta os limites oficiais do Paraná (SRID 4674 - Bounding Box `[-55.00, -47.50]` lon / `[-27.20, -22.00]` lat).
   - Se estiver fora → erro `FORA_DO_RECORTE_TERRITORIAL`.
6. Sucesso → `sucesso(wktPadronizado)`.

### 4.7. `ValidadorRegressao` — [FEITO, 2026-09-13, Fase 2.7]

**Ativa quando** `impedirRegressao = true` em `mapeamento_coluna` (padrão `DEFAULT 1`).

**Mecanismo de funcionamento:**
1. Compara o valor atual recebido no payload com o `valorAnterior` do mesmo campo no snapshot/lote anterior (carregado da Silver).
2. Se o valor atual for nulo, vazio ou falso nulo (`"null"`, `"N/A"`, `"-"`), **E** o valor anterior estiver preenchido com um dado válido:
   - Detecta perda indevida de dado entre lotes consecutivos.
   - Gera o erro `REGRESSAO_DE_VALOR` com detalhes do campo e valor anterior para a Quarentena.
3. Se o novo valor estiver preenchido (atualização legítima) ou se o campo já era nulo anteriormente:
   - Aprova a operação (`sucesso(valor)`).

**Testes:** `ValidadorRegressaoTest` — 22 cenários.

### 4.8. `DeduplicadorRegistros` — [FEITO, 2026-09-13, Fase 2.8]

**Ativa quando** múltiplos registros possuem a mesma chave natural de negócio (`campoChave`, ex.: `cod_imovel`, `numero_termo`) entre snapshots consecutivos ou repetidos no mesmo lote de ingestão.

**Mecanismo de funcionamento:**
1. Agrupa os registros brutos pela chave natural extraída do JSON do `payload`.
2. Registros sem chave natural ou com chave em branco são preservados individualmente sem descarte por colisão.
3. Para cada grupo com chaves coincidentes, determina o registro vencedor de acordo com a hierarquia de precedência:
   - **Regra de Negócio do CAR:** Registros com `situacao_imovel = "AT"` (Ativo) têm precedência estrita sobre estados transitórios ou históricos (`"CA"`, `"PE"`).
   - **Snapshot Mais Recente:** Maior `loteIngestaoId` vence (snapshot mais atualizado promovido à Silver).
   - **Sequência Temporal de Ingestão:** Maior `dataIngestao` ou maior `id` do registro desempata.
4. Retorna `ResultadoDeduplicacao<T>` com:
   - `registrosPromovidos`: lista apenas dos registros vencedores para seguir para validação e carga na Silver.
   - `duplicatasDescartadas`: registros superados, com `motivo`, `chaveNatural` e ponteiro para o `registroVencedor`.
5. Fornece método genérico e tipado `deduplicarGenerico(itens, extratorChave, comparador)` para reuso em outras entidades do pipeline.

**Testes:** `DeduplicadorRegistrosTest` — 7 cenários.

### 4.9. `OrquestradorValidacao` — [FEITO, 2026-09-13, Fase 3]

**Ativa durante** a execução do pipeline de processamento do lote Bronze → Silver / Quarentena.

**Mecanismo de funcionamento:**
1. Recebe a coleção de `RegistroBruto` do lote, o `fonteDadoId` e o `campoChaveNatural`.
2. Executa a **Deduplicação de Snapshot** via `DeduplicadorRegistros`:
   - Descarta duplicatas superadas e registra auditoria completa com link para o registro vencedor.
3. Carrega em tempo de execução os mapeamentos de coluna cadastrados em `controle.mapeamento_coluna` para a fonte.
4. Para cada registro vencedor deduplicado:
   - Extrai e parseia o payload JSON original;
   - Itera por todos os mapeamentos de coluna da entidade;
   - Executa a cadeia completa:
     - `SanitizadorEntrada` sempre roda primeiro (converte falsos nulos e checa obrigatoriedade);
     - Executa validadores especializados que suportam o contexto (`ValidadorCpfCnpj`, `ValidadorDominio`, `ValidadorIntegridadeCruzada`, `ValidadorFaixaMinima`, `ValidadorGeometria`, `ValidadorRegressao`);
     - Se ocorrer erro em qualquer campo: classifica o registro imediatamente como `RegistroRejeitadoQuarentena` (com `tipoErro`, `campoProblema`, `valorRecebido`, `motivoRejeicao`) e interrompe os demais campos (fail-fast por registro);
     - Se todos os campos forem aprovados: classifica como `RegistroAprovadoSilver` com o mapa de atributos transformados e geometria padronizada.
5. Retorna `ResultadoOrquestracao` com as listas estruturadas de aprovados, quarentena e duplicatas descartadas.

**Testes:** `OrquestradorValidacaoTest` — 5 cenários.

### 4.10. `TransicaoBronzeSilverService` e `PersistidorSilverService` — [FEITO, 2026-09-13 | EVOLUÍDO 2026-09-14]

Coordena a passagem segura dos dados aprovados para a Silver e a retenção de inconsistências na Quarentena.

**Regra de Integridade Total (*All-or-Nothing*):**
1. Ao processar um lote de ingestão da Bronze:
   - Se **100%** dos registros forem aprovados: o lote é persistido na Silver e marcado como `PROMOVIDO_SILVER`.
   - Se **1 único registro** for para a Quarentena: **0 registros** desse lote entram na Silver. O lote é pausado com o status `RETIDO_QUARENTENA`.
2. **Governança e Ações do Analista:**
   - **Liberar para Silver** (`POST /api/quarentena/lotes/{id}/liberar`): reprocessa o lote, persiste os registros válidos na Silver (`PersistidorSilverService`), atualiza os registros de quarentena do lote para `CORRIGIDO_REPROCESSADO` (`status_analise_id = 3`) e define o lote como `PROMOVIDO_SILVER`.
   - **Descartar Lote** (`POST /api/quarentena/lotes/{id}/descartar`): define os registros de quarentena como `DESCARTADO` (`status_analise_id = 4`), registra a justificativa em `motivo_rejeicao`, preenche `analista_responsavel_id` e marca o lote como `DESCARTADO` (nenhum dado vai para a Silver).

**Persistência Silver com Oracle Spatial e LOBs Nativos:**
- Em `PersistidorSilverService`, o comando SQL `MERGE INTO` de cada uma das 7 entidades canônicas (`imovel_rural`, `area_protegida`, `auto_infracao`, `embargo_ambiental`, `desmatamento`, `foco_calor`, `municipio`) utiliza `SDO_GEOMETRY(s.wkt, 4674)`.
- Para evitar o erro `ORA-01461: The value at bind position exceeded the maximum VARCHAR2 length` com polígonos extensos (ex.: CAR com 20.000 a 100.000+ caracteres), strings maiores que 2.000 caracteres são convertidas explicitamente em `java.sql.Clob` de sessão via `ps.getConnection().createClob()` e liberadas no bloco `finally` com `clob.free()`.
- O bind direto via `PreparedStatementCallback` contorna uma falha do parser client-side do driver Oracle JDBC 23ai (`ArrayIndexOutOfBoundsException` em `OracleParameterMetaDataParser`).

---

## 5. Tipos de erro registrados na Quarentena

| Código | Quando é gerado |
|---|---|
| `CAMPO_OBRIGATORIO_AUSENTE` | Campo `obrigatorio = true` com valor nulo ou falso nulo |
| `CPF_CNPJ_INVALIDO` | CPF/CNPJ com DV errado, sequência trivial ou quantidade de dígitos inválida |
| `VALOR_FORA_DO_DOMINIO` | Valor informado não consta como código ativo na tabela de referência Silver |
| `REFERENCIA_NAO_ENCONTRADA` | Chave informada não existe na entidade Silver de destino (ex.: `cod_ibge` em `silver.municipio`) |
| `VALOR_ABAIXO_DO_MINIMO` | Valor numérico < `valor_minimo` configurado |
| `TIPO_DE_DADO_INVALIDO` | Valor não conversível para o tipo esperado |
| `GEOMETRIA_NULA` | Geometria obrigatória ausente ou vazia |
| `GEOMETRIA_MALFORMADA` | Sintaxe WKT ou GeoJSON corrompida |
| `GEOMETRIA_INVALIDA` | Geometria presente mas topologicamente inválida (auto-interseção, etc.) |
| `GEOMETRIA_TIPO_INCOMPATIVEL` | Primitivo geométrico incompatível com a entidade alvo |
| `FORA_DO_RECORTE_TERRITORIAL` | Geometria localizada fora dos limites territoriais do Paraná |
| `REGRESSAO_DE_VALOR` | Campo preenchido no snapshot anterior veio nulo/vazio no lote mais recente |
| `CAMPO_EM_BRANCO` | *(mapeado na V030; produzido pelo SanitizadorEntrada via CAMPO_OBRIGATORIO_AUSENTE)* |

---

## 6. Roadmap de validadores e esteira

| Fase | Componente | Status |
|---|---|---|
| 2.1 | `SanitizadorEntrada` — falsos nulos e obrigatoriedade | ✅ FEITO |
| 2.2 | `ValidadorCpfCnpj` — CPF/CNPJ com Módulo 11 | ✅ FEITO |
| 2.3 | `ValidadorDominio` — lookup em cache de `tabela_referencia` da Silver + normalização fonética + sinônimos | ✅ FEITO |
| 2.4 | `ValidadorIntegridadeCruzada` — integridade relacional em `referencia_entidade_silver` | ✅ FEITO |
| 2.5 | `ValidadorFaixaMinima` — valor numérico ≥ `valor_minimo` | ✅ FEITO |
| 2.6 | `ValidadorGeometria` — tipo primitivo + topologia + recorte PR | ✅ FEITO |
| 2.7 | `ValidadorRegressao` — campo preenchido no snapshot anterior, veio null agora | ✅ FEITO |
| 2.8 | `DeduplicadorRegistros` — deduplicação de chaves naturais e seleção do mais novo para Silver | ✅ FEITO |
| 3.1 | `OrquestradorValidacao` — encadeia todos os validadores por campo e lote (Bronze → Silver/Quarentena) | ✅ FEITO |
| 3.2 | `TransicaoBronzeSilverService` & `PersistidorSilverService` — All-or-Nothing, liberação/descarte e persistência Silver | ✅ FEITO |
| 3.3 | `QuarentenaController` & `QuarentenaService` — APIs REST de auditoria, liberação de lote e resolução de inconsistências | ✅ FEITO |
| 3.4 | Frontend: Telas de Quarentena (`/quarentena`) e Gestão de Lotes & Pipeline (`/lotes`) | ✅ FEITO |






