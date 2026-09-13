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

---

## 5. Tipos de erro registrados na Quarentena

| Código | Quando é gerado |
|---|---|
| `CAMPO_OBRIGATORIO_AUSENTE` | Campo `obrigatorio = true` com valor nulo ou falso nulo |
| `CPF_CNPJ_INVALIDO` | CPF/CNPJ com DV errado, sequência trivial ou quantidade de dígitos inválida |
| `VALOR_ABAIXO_DO_MINIMO` | Valor numérico < `valor_minimo` configurado *(a implementar — Fase 2.3)* |
| `CAMPO_EM_BRANCO` | *(mapeado na V030; produzido pelo SanitizadorEntrada via CAMPO_OBRIGATORIO_AUSENTE)* |
| `GEOMETRIA_TIPO_INCOMPATIVEL` | Primitivo geométrico inválido *(a implementar — Fase 2.x)* |

---

## 6. Roadmap de próximos validadores

| Fase | Validador | Status |
|---|---|---|
| 2.1 | `SanitizadorEntrada` — falsos nulos e obrigatoriedade | ✅ FEITO |
| 2.2 | `ValidadorCpfCnpj` — CPF/CNPJ com Módulo 11 | ✅ FEITO |
| 2.3 | `ValidadorFaixaMinima` — valor numérico ≥ `valor_minimo` | 🔲 pendente |
| 2.4 | `ValidadorDominio` — lookup em `tabela_referencia` da Silver | 🔲 pendente |
| 2.5 | `ValidadorIntegridadeCruzada` — existe em `referencia_entidade_silver` | 🔲 pendente |
| 2.6 | `ValidadorGeometria` — tipo primitivo + topologia + recorte PR | 🔲 pendente |
| 2.7 | `ValidadorRegressao` — campo preenchido no snapshot anterior, veio null agora | 🔲 pendente |
| 2.8 | `OrquestradorValidacao` — encadeia todos os validadores por campo | 🔲 pendente |
