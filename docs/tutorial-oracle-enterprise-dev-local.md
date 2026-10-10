# Tutorial: liberar o Oracle sem o limite de 12GB no ambiente local

> **Pra quem é este tutorial:** qualquer pessoa da equipe que vai rodar o backend
> (`docker compose up`) na própria máquina. Siga os passos **na ordem**, sem pular
> nenhum — cada um depende do anterior.
>
> **Quanto tempo leva:** ~10 minutos, só uma vez. Depois de feito, nunca mais
> precisa repetir nessa máquina.
>
> **Custa alguma coisa?** Não. É uma licença de desenvolvedor da própria Oracle,
> grátis pra sempre, mas **só pode ser usada pra desenvolvimento e teste — nunca em
> produção** (isso não afeta a gente agora, decidimos deixar "ambiente de produção"
> pra decidir depois).

---

## 0. Por que isso existe

O banco Oracle que usamos no `docker compose` (`gvenzl/oracle-free`) tem um limite
de **12GB de dados**, embutido na própria licença — não é configuração, é trava do
software. Pra sair disso sem pagar nada, a Oracle oferece a versão completa
(Enterprise Edition) de graça pra quem só vai usar em desenvolvimento/teste. É só
mais burocrática pra conseguir: precisa de uma conta e aceitar os termos de uso
antes de conseguir baixar a imagem do Docker.

Este tutorial cobre **só essa parte burocrática** (conta + aceite + login). A troca
do `docker-compose.yml` em si já está sendo preparada separadamente — depois que
você terminar os passos daqui, vai ser só `git pull` e `docker compose up` como
sempre.

---

## 1. Crie uma conta Oracle (gratuita, simples — NÃO é conta de nuvem/OCI)

**Atenção pra não se confundir**: existe a "Conta Oracle" simples (o que você vai
criar agora) e a "Conta Oracle Cloud" (OCI, pra hospedar servidor na nuvem — **isso
é outra coisa, não precisamos disso aqui**). Siga exatamente este link:

1. Acesse: **https://profile.oracle.com/myprofile/account/create-account.jspx**
2. Preencha com seu e-mail, nome e um país (pode ser Brasil).
3. Crie uma senha. A Oracle exige: mínimo 8 caracteres, 1 letra minúscula, 1 letra
   maiúscula, 1 número e 1 caractere especial (ex.: `Datagis@2026`).
4. Você vai receber um e-mail de confirmação (**"Confirm your Oracle account"** ou
   similar). O link dentro dele expira rápido (geralmente 30 minutos) — abra o
   e-mail logo depois de se cadastrar.
5. Clique no link do e-mail. Isso finaliza sua conta.

**Já tem uma conta Oracle de algum outro uso (ex.: já baixou algo da Oracle antes)?**
Pode usar a mesma, não precisa criar outra.

---

## 2. Entre no Oracle Container Registry e aceite a licença

1. Acesse: **https://container-registry.oracle.com**
2. Faça login com a conta que você criou no passo 1 (botão de login no canto
   superior direito).
3. Na busca/lista de repositórios, procure por **"Database"**.
4. Dentro de Database, entre no repositório **"enterprise"** (Oracle Database
   Enterprise Edition).
5. Vai aparecer um botão **"Continue"** — clique nele.
6. Vai abrir o texto da licença (**"Oracle Standard Terms and Restrictions"**).
   Leia (ou pelo menos role até o fim) e clique em **"Accept"**.
7. Depois de aceitar, a página do repositório deve mostrar uma confirmação do tipo
   "✅ You last accepted the Oracle Standard Terms and Restrictions" com a data/hora.
   **Essa confirmação é a prova de que funcionou** — se não aparecer, repita o
   passo 5-6.

⚠️ **Essa aceitação é só pra ESTE repositório específico** ("enterprise"). Se um dia
precisarmos de outra imagem Oracle licenciada, repete esse mesmo passo pra ela.

---

## 3. Gere o seu Auth Token (a "senha" que o Docker vai usar)

O Docker **não usa sua senha normal da conta Oracle** pra fazer login no registry —
usa um token separado, gerado uma vez.

1. Ainda logado em **https://container-registry.oracle.com**, clique na sua conta
   no canto superior direito (onde aparece seu nome/e-mail).
2. Procure uma opção chamada **"Auth Token"** (pode estar num menu que abre com uma
   setinha ▼ ao lado do seu nome).
3. Clique em **"Generate Key"** (ou "Generate Token").
4. Vai aparecer uma sequência de letras/números (o token). **Copie e guarde em
   algum lugar seguro agora** (um gerenciador de senhas, ou um bloco de notas
   temporário) — depois de fechar essa tela, **você não consegue ver esse token de
   novo**, só gerar um novo se perder.

> Se os nomes dos menus estiverem um pouco diferentes do que está escrito aqui (a
> Oracle muda a interface de vez em quando), procure por qualquer variação de
> **"Auth Token"**, **"Access Token"** ou **"API Key"** perto do seu perfil — é a
> mesma ideia.

---

## 4. Faça login no Docker com esse token

Abra o terminal (WSL) e rode:

```bash
docker login container-registry.oracle.com
```

Vai pedir 2 coisas:
- **Username**: o e-mail da sua conta Oracle (a mesma do passo 1).
- **Password**: **não é sua senha normal** — é o **Auth Token** que você copiou no
  passo 3.

Se der tudo certo, aparece:
```
Login Succeeded
```

**Se der erro** (`unauthorized` ou `denied`): confira se você copiou o token certo
(sem espaço extra no começo/fim) e se o e-mail usado como username está exatamente
igual ao da conta que aceitou a licença no passo 2.

---

## 5. Confirme que está tudo pronto

Rode este comando pra testar (vai demorar um pouco, a imagem é grande — pode levar
de alguns minutos a bem mais, dependendo da internet):

```bash
docker pull container-registry.oracle.com/database/enterprise:latest
```

Se baixar sem erro de permissão, **está tudo certo, pode parar aqui** — os passos 1
a 4 só precisam ser feitos uma vez nessa máquina.

---

## O que vem depois

Depois que você (e o resto do time) completar esses passos, o próximo passo é dar
`git pull` neste repositório pra pegar o `docker-compose.yml`/scripts atualizados
pra essa imagem nova, e rodar `docker compose up` como sempre. Essa parte vai
aparecer documentada à parte quando estiver pronta — **não precisa adiantar nada
disso agora, só os passos 1 a 5 acima**.

---

## Perguntas frequentes

**"Preciso fazer isso de novo depois, ou só essa vez?"**
Só uma vez por máquina. O `docker login` fica salvo no seu computador.

**"E se eu trocar de computador?"**
Repete os passos 4 e 5 (login + pull) na máquina nova — a conta e o token dos
passos 1-3 continuam os mesmos, não precisa recriar.

**"Isso vale pra sempre ou expira?"**
A conta e o aceite da licença não expiram. Alguns relatos antigos (não confirmados
oficialmente) mencionam que a licença podia precisar ser re-aceita depois de um
tempo rodando — se um dia o `docker pull` começar a falhar do nada, o primeiro
passo é voltar no site e conferir se a aceitação da licença (passo 2) ainda está
valendo.

**"Isso é ilegal ou arriscado?"**
Não — é exatamente o uso que a Oracle libera de propósito (licença de
desenvolvedor, gratuita, pra isso mesmo). A única regra é: **só desenvolvimento e
teste, nunca produção** — o que já bate com a decisão do time de deixar o ambiente
de produção pra decidir depois.

**"Posso mandar minha imagem já baixada pra um colega, pra ele não precisar fazer
tudo isso?"**
Não — cada pessoa precisa fazer o próprio login/aceite. Não é só burocracia: não
temos confirmação de que a licença permite repassar a imagem pra outra pessoa, então
não vamos arriscar isso.
