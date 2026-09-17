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

Resultado após a correção de GeoJSON: **4 testes Oracle aprovados**.

- V032 registrada e metadados no SRID 4674.
- Cadastro HTTP com cobertura nula.
- Persistência e releitura de polígono, com coordenadas/SRID preservados e
  validação Oracle Spatial retornando `TRUE`.
- `GET /conjuntos` com polígono real retorna HTTP 200 e GeoJSON com coordenadas
  numéricas aninhadas, compatível com a entrada do Leaflet.

A falha anterior (`Operation does not support GeometryCollection arguments`)
foi corrigida: `ConjuntoResponse` agora expõe uma estrutura GeoJSON produzida por
`CoberturaGeoJson`, evitando a serialização automática dos getters internos de JTS.
A entidade persistida permanece com Geometry JTS e SRID 4674.

`CoberturaGeoJsonTest` adiciona 11 testes: pontos, linhas, polígonos com anel interno,
múltiplas geometrias, coleções, coordenadas Z, geometrias vazias e cobertura nula.
O frontend preserva o campo `geometries` das coleções.

A aplicação da V032 em banco vazio e o POST com geometria não nula permanecem
fora desta validação. Não houve teste visual em navegador real.

Os testes complementares da tela e do serviço HTTP estão no repositório
`datagis-frontend` (LIZARDS-9), em `src/components/ConjuntosView.test.js` e
`src/services/conjuntoService.test.js`.
