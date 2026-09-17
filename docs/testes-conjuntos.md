# Testes de conjuntos — LIZARDS-8

Execute na raiz do backend, com Java 21:

```sh
./mvnw -Dtest=ConjuntoServiceTest,ConjuntoControllerTest test
./mvnw '-Dtest=*Test' package
```

O primeiro comando executa os 9 testes de conjuntos. O segundo executa todas as
classes terminadas em `Test` e gera o JAR. Ele não inclui `DatagisApplicationTests`,
que sobe um Oracle via Testcontainers e é um teste de integração.

`ConjuntoServiceTest` cobre cadastro com fonte existente, rejeição de fonte
inexistente sem persistência, geometria opcional, preservação de geometria/SRID
e listagem com identificação da fonte. Os repositórios são simulados com Mockito.

`ConjuntoControllerTest` usa MockMvc standalone para conferir as rotas
`GET /conjuntos` e `POST /conjuntos`, JSON de competência/status, HTTP 201 no
cadastro, HTTP 400 com campo `erro` para fonte inexistente e rejeição de enum inválido.
Não sobe o contexto completo nem acessa banco de dados.

Os testes unitários não comprovam a persistência Oracle Spatial ou a conversão
HTTP de geometria não nula. Para verificar o Oracle local real, execute com os
containers do compose já iniciados:

```sh
docker compose exec -T -e DATAGIS_ORACLE_IT=true backend \
  ./mvnw -Dtest=ConjuntoOracleIT test
```

`ConjuntoOracleIT` só executa com a variável de autorização acima. Usa o datasource
do container, não aplica migrations, desliga agendamentos e faz rollback de cada
teste. IDs de sequences podem ser consumidos mesmo com rollback.

Resultado no Oracle local em 2026-09-16: **4 testes, 3 aprovados e 1 falha**.

- V032 registrada com sucesso e metadados de cobertura no SRID 4674: aprovado.
  A migration já estava aplicada; não foi reaplicada em banco vazio neste teste.
- Cadastro HTTP com cobertura nula (payload do formulário): aprovado.
- Gravação JPA, flush, limpeza do contexto e releitura do polígono: aprovado,
  com coordenadas/SRID preservados e validação Oracle Spatial retornando `TRUE`.
- `GET /conjuntos` com polígono real: **falha**, HTTP 400 com
  `{"erro":"Operation does not support GeometryCollection arguments"}`.
  O contrato de resposta espacial ainda não entrega GeoJSON utilizável pelo mapa.

O teste que falha permanece como reprodução do problema; não foi alterado para
aceitar o erro. Nenhuma correção no código de produção foi feita nesta rodada.
A conversão de Geometry JTS para o JSON da API precisa ser investigada/corrigida.
O recebimento de geometria não nula por POST também não foi validado nesta rodada.

Após a execução, uma consulta independente confirmou os mesmos **2 conjuntos**
preexistentes e **0 fontes de teste** persistidas.

Os testes complementares da tela e do serviço HTTP estão no repositório
`datagis-frontend` (LIZARDS-9), em `src/components/ConjuntosView.test.js` e
`src/services/conjuntoService.test.js`.
