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

### 4.3. `ValidadorDominio` — [FEITO, 2026-09-13, Fase 2.3]

**Ativa quando** `tabelaReferencia` está configurada em `mapeamento_coluna` (não nula e não em branco).

**Mecanismo de funcionamento:**
1. `null` ou string em branco → `sucesso(null)` (responsabilidade do `SanitizadorEntrada`).
2. Sanitiza o nome da tabela (remove prefixo `silver.` se fornecido e valida contra `^[a-zA-Z0-9_]+$` para proteção contra SQL Injection).
3. Consulta o **cache thread-safe** em memória (`ConcurrentHashMap<String, Map<String, String>>`).
4. **Lazy loading**: na primeira ocorrência da tabela, executa `SELECT codigo, descricao FROM {silverSchema}.{tabela} WHERE ativo = 1`.
   - Se a tabela não possuir coluna `descricao`, aplica fallback para `SELECT codigo FROM {silverSchema}.{tabela} WHERE ativo = 1`.
5. Mapeia em memória tanto códigos quanto descrições em maiúsculas apontando para o código canônico persistido na Silver.
6. Se o valor recebido não existir no domínio ativo → erro `VALOR_FORA_DO_DOMINIO` com detalhes completos do campo, valor e tabela.
7. Se existir → `sucesso(codigoCanonico)` (normaliza variações case-insensitive ou descrições para o código canônico).

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

---

## 5. Tipos de erro registrados na Quarentena

| Código | Quando é gerado |
|---|---|
| `CAMPO_OBRIGATORIO_AUSENTE` | Campo `obrigatorio = true` com valor nulo ou falso nulo |
| `CPF_CNPJ_INVALIDO` | CPF/CNPJ com DV errado, sequência trivial ou quantidade de dígitos inválida |
| `VALOR_FORA_DO_DOMINIO` | Valor informado não consta como código ativo na tabela de referência Silver |
| `REFERENCIA_NAO_ENCONTRADA` | Chave informada não existe na entidade Silver de destino (ex.: `cod_ibge` em `silver.municipio`) |
| `VALOR_ABAIXO_DO_MINIMO` | Valor numérico < `valor_minimo` configurado |
| `TIPO_DE_DADO_INVALIDO` | Valor não conversível para o tipo esperado (ex.: texto não numérico em coluna com faixa mínima) |
| `CAMPO_EM_BRANCO` | *(mapeado na V030; produzido pelo SanitizadorEntrada via CAMPO_OBRIGATORIO_AUSENTE)* |
| `GEOMETRIA_TIPO_INCOMPATIVEL` | Primitivo geométrico inválido *(a implementar)* |

---

## 6. Roadmap de próximos validadores

| Fase | Validador | Status |
|---|---|---|
| 2.1 | `SanitizadorEntrada` — falsos nulos e obrigatoriedade | ✅ FEITO |
| 2.2 | `ValidadorCpfCnpj` — CPF/CNPJ com Módulo 11 | ✅ FEITO |
| 2.3 | `ValidadorDominio` — lookup em cache de `tabela_referencia` da Silver + invalidação analista | ✅ FEITO |
| 2.4 | `ValidadorIntegridadeCruzada` — integridade relacional em `referencia_entidade_silver` | ✅ FEITO |
| 2.5 | `ValidadorFaixaMinima` — valor numérico ≥ `valor_minimo` | ✅ FEITO |
| 2.6 | `ValidadorGeometria` — tipo primitivo + topologia + recorte PR | 🔲 pendente |
| 2.7 | `ValidadorRegressao` — campo preenchido no snapshot anterior, veio null agora | 🔲 pendente |
| 2.8 | `OrquestradorValidacao` — encadeia todos os validadores por campo | 🔲 pendente |



