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

## Validação após correção da resposta espacial

- Conversão explícita de Geometry JTS para GeoJSON na resposta da API.
- 11 novos testes de conversão, preservando anéis internos, múltiplos polígonos,
  coleções e coordenadas Z.
- Integração `ConjuntoOracleIT`: 4 testes aprovados. A listagem com geometria real
  agora retorna HTTP 200 e GeoJSON; a falha HTTP 400 anterior está resolvida.
- Suíte combinada: 294 testes unitários/contrato e 4 testes Oracle (298 no total).
- Frontend: 17 testes aprovados e build concluído, incluindo preservação de coleções.

Para reproduzir no Oracle local:

```sh
docker compose exec -T -e DATAGIS_ORACLE_IT=true backend \
  ./mvnw '-Dtest=*Test,ConjuntoOracleIT' package
```

O aprovador deve obter todos os testes aprovados e JAR gerado; o cenário de
listagem verifica HTTP 200, tipo Polygon e coordenadas GeoJSON do polígono real.

## Impactos e pontos de atenção

- `DatagisApplicationTests` não faz parte do filtro `*Test`; o teste de integração
  genérico não foi executado; o Oracle local foi exercitado por `ConjuntoOracleIT`.
- V032 já aplicada e persistência espacial foram verificadas no Oracle real.
  Aplicação em banco vazio e entrada HTTP de geometria não nula não foram testadas.
- A resposta de cobertura agora usa GeoJSON; a entrada POST com geometria não nula
  ainda não foi validada. O formulário atual envia cobertura nula.
- A rota é `/conjuntos`, sem prefixo `/api`; o frontend configura proxy específico.
- O status é informado no cadastro e ainda não é atualizado pelo pipeline.
- `ddl-auto=none` desliga a conferência entre entidades e esquema para toda a aplicação.

Antes de publicar: revisar o código, validar as integrações pendentes e conferir
se a base está atualizada com a sprint remota. Esta descrição deve ser atualizada na PR existente após o envio da correção.
