# feat<LIZARDS-8>: cadastra e lista conjuntos de dados

Destino: `feat/conjunto-de-dados` → `sprint-1`.

Implementa o cadastro e a listagem de conjuntos associados a fontes de dados,
com competência, status e cobertura territorial opcional. Atende à tarefa
**LIZARDS-8** e fornece os endpoints consumidos pela tela da **LIZARDS-9**.

## Implementação

- Entidade, repositório, serviço e DTOs de conjuntos.
- `GET /conjuntos` e `POST /conjuntos`, com HTTP 201 no cadastro.
- Rejeição de fonte inexistente com HTTP 400 e mensagem no campo `erro`.
- Migration V032 com tabela `conjunto`, FK de fonte e metadados espaciais SRID 4674.
- Configuração `ddl-auto=none` para desativar a validação de esquema do Hibernate;
  as migrations continuam sob responsabilidade do Flyway.
- Nove testes de serviço e contrato HTTP, com instruções em `docs/testes-conjuntos.md`.

## Validação local — 2026-09-16

- `./mvnw -Dtest=ConjuntoServiceTest,ConjuntoControllerTest test`: 9 testes aprovados.
- `./mvnw '-Dtest=*Test' package`: 283 testes aprovados, sem falhas ou testes pulados;
  JAR gerado com sucesso.
- `git diff --check`: sem erros.
- Integração `ConjuntoOracleIT` no Oracle local: 3 testes aprovados e 1 falha.
  V032/metadados, cadastro sem geometria e persistência/releitura espacial passaram.
  A listagem com geometria real retornou HTTP 400:
  `Operation does not support GeometryCollection arguments`.
  Rollback confirmado: 2 conjuntos preexistentes preservados e 0 fontes de teste.

## Impactos e pontos de atenção

- `DatagisApplicationTests` não faz parte do filtro `*Test`; o teste de integração
  genérico não foi executado; o Oracle local foi exercitado por `ConjuntoOracleIT`.
- V032 já aplicada e persistência espacial foram verificadas no Oracle real.
  Aplicação em banco vazio e entrada HTTP de geometria não nula não foram testadas.
- **Pendência antes de aprovar:** corrigir a resposta JSON de geometria e fazer
  passar o teste de listagem espacial. Os testes unitários aprovados não cobrem essa falha.
- A rota é `/conjuntos`, sem prefixo `/api`; o frontend configura proxy específico.
- O status é informado no cadastro e ainda não é atualizado pelo pipeline.
- `ddl-auto=none` desliga a conferência entre entidades e esquema para toda a aplicação.

Antes de publicar: revisar o código, validar as integrações pendentes e conferir
se a base está atualizada com a sprint remota. Este arquivo é apenas um rascunho
local de descrição; nenhuma PR foi aberta.
