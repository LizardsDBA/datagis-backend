# Controle de acesso (RBAC) com Keycloak

> Tickets: LIZARDS-47 (Administrador — fundação), LIZARDS-45 (Operador), LIZARDS-46
> (Analista), LIZARDS-48 (Auditor).

## Como funciona, em uma frase

O frontend faz login no **Keycloak**, recebe um token (JWT) e manda esse token em toda
chamada à API; o backend só confere a assinatura do token e se ele traz a **role de
permissão** que a rota exige.

## Dois níveis: permissões e perfis

**Permissões** — cada uma libera uma coisa só. É só isso que o código confere
(`SecurityConfig` no backend, `meta.permissao` nas rotas do frontend).

| Permissão | Libera |
|---|---|
| `fonte:visualizar` | listar e ver fontes e mapeamentos |
| `fonte:gerenciar` | cadastrar, editar, executar, fazer upload, editar mapeamentos |
| `lote:visualizar` | listar lotes e pipeline |
| `lote:gerenciar` | liberar, descartar e reprocessar lote |
| `quarentena:visualizar` | ver a fila e o detalhe dos registros |
| `quarentena:tratar` | corrigir, revalidar, descartar, cadastrar domínio |
| `dados:visualizar` | comparar Bronze/Silver/Quarentena (`/conjuntos-dados`) |
| `conjunto:visualizar` / `conjunto:gerenciar` | conjuntos e cobertura |
| `indicador:visualizar` | indicadores, imóveis publicados, status da Gold |
| `indicador:publicar` | publicar/recalcular a Gold, configurar tipos de indicador |
| `auditoria:visualizar` | logs de auditoria |
| `usuario:gerenciar` | cadastro de usuários e atribuição de acessos |

**Perfis** — pacotes de permissões (no Keycloak, *roles compostas*). Um usuário pode ter
vários perfis e ainda permissões avulsas.

| Perfil | Permissões | Ticket |
|---|---|---|
| Administrador | todas | LIZARDS-47 |
| Operador | `fonte:*`, `lote:*`, `quarentena:*`, `dados:visualizar` | LIZARDS-45 |
| Analista | `conjunto:*`, `indicador:*`, `fonte:visualizar`, `dados:visualizar` | LIZARDS-46 |
| Auditor | todas as `:visualizar` + `auditoria:visualizar` | LIZARDS-48 |

**A fonte de verdade é o código**: `Permissao.java` e `Perfil.java`. Na subida, o
`KeycloakProvisionador` cria no Keycloak o que falta e acerta a composição de cada perfil
(inclusive removendo permissão que saiu). Por isso mudar perfil/permissão **não** exige
recriar o volume do Keycloak — basta reiniciar o backend.

## Subindo o ambiente

`docker compose up -d` agora sobe 4 containers: `oracle`, `keycloak`, `backend`, `frontend`.

| O quê | Onde |
|---|---|
| Sistema | http://localhost:5173 (redireciona pro login) |
| Console do Keycloak | http://localhost:8180 — usuário `admin`, senha `KEYCLOAK_ADMIN_PASSWORD` do `.env` |
| Swagger | http://localhost:8080/swagger-ui.html (botão **Authorize** recebe o token) |

**Usuários de teste** (criados pelo backend quando `app.keycloak.usuarios-dev=true`, que é
o padrão no compose; senha = login):

| Login | Perfil |
|---|---|
| `admin` | Administrador |

(Os tickets dos outros perfis adicionam `operador`, `analista` e `auditor`.)

Usuários reais são cadastrados pela tela **Usuários e Acessos** (`/admin/usuarios`), com
senha temporária — o Keycloak obriga a troca no primeiro login.

## Pegando um token pra testar a API (só desenvolvimento)

O client `datagis-dev` aceita usuário/senha direto (grant `password`) — existe só pra isso:

```bash
curl -s -X POST http://localhost:8180/realms/datagis/protocol/openid-connect/token \
  -d grant_type=password -d client_id=datagis-dev -d username=admin -d password=admin
```

Copie o `access_token` da resposta e use em `Authorization: Bearer <token>` (ou cole no
Authorize do Swagger). O token vale 5 minutos.

## Como adicionar uma rota/permissão nova

1. Rota nova na API **cai fechada** (`denyAll`) até ganhar regra — de propósito.
2. Use uma permissão existente ou crie uma constante em `Permissao.java`.
3. Adicione a regra em `SecurityConfig` (leitura `GET` antes da regra geral de escrita).
4. Inclua a permissão nos perfis que devem tê-la (`Perfil.java`).
5. `MatrizAcessoTest`: acrescente a linha em `rotas()`. O teste
   `todoEndpointDosControllersTemRegraDeAcesso` falha se algum endpoint ficou sem regra.
6. No frontend: `meta.permissao` na rota (`router/index.js`), `permissao` no item do menu
   (`Sidebar.vue`) e, nos botões de ação, `v-if="temPermissao(PERMISSOES.X)"`.

## Pegadinhas já encontradas

- **Issuer do token**: o navegador fala com `localhost:8180`, o backend (dentro do Docker)
  com `keycloak:8080`. O compose fixa `KC_HOSTNAME=http://localhost:8180` pra que o token
  sempre saia com o mesmo `iss`; o backend valida esse issuer mas busca as chaves pelo
  endereço interno (`spring.security.oauth2.resourceserver.jwt.jwk-set-uri`).
- **`--import-realm` só importa na primeira subida.** Mudança em
  `infra/keycloak/realm-datagis.json` (clients, conta de serviço) só vale com o volume
  recriado: `docker compose down` + `docker volume rm datagis-backend_keycloak-data`
  (apaga também os usuários cadastrados). Roles e perfis **não** dependem disso.
- **Nome com pontuação é recusado pelo Keycloak 26** (`error-person-name-invalid-character`)
  — nome/sobrenome aceitam só letras, espaço, hífen e apóstrofo.
- **Não usamos a lib `keycloak-admin-client`**: ela traz RESTEasy + Jackson 2 e conflita com
  Boot 4/Jackson 3. `KeycloakAdminClient` chama a Admin REST API direto com `RestClient`.
