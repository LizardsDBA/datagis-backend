/*
 * Ajustes da tela de login que o CSS sozinho nao faz (LIZARDS-61):
 * rotulo flutuante e rodape com o direito autoral.
 *
 * Rotulo flutuante (login e troca de senha):
 * O CSS sobe o rotulo para o topo do campo quando ele tem foco ou valor, e para saber se
 * o campo esta vazio usa :placeholder-shown - que so funciona em campo COM placeholder.
 * Os campos sao montados pelas macros do field.ftl do Keycloak, que nao tem opcao de
 * placeholder; em vez de copiar login.ftl + field.ftl para o tema (e conferir os dois a
 * cada atualizacao do Keycloak), este script poe um placeholder de um espaco so: nada
 * aparece na tela e o leitor de tela nao repete o rotulo.
 * Sem JavaScript, o rotulo fica sempre no alto do campo (legivel, so sem a animacao).
 *
 * Rodape "(c) 2026 LizardsDBA", com o nome levando ao GitHub da equipe em nova aba: vai no
 * fim do formulario, abaixo do idioma (o CSS so cria texto, nao link). Aparece em todas as
 * telas do tema. Sem JavaScript, a tela funciona igual, so sem o rodape.
 */
(function () {
    var CAMPOS = ['username', 'password', 'password-new', 'password-confirm'];
    var EQUIPE_URL = 'https://github.com/LizardsDBA/API-2026-4';

    function prepararCampos() {
        CAMPOS.forEach(function (id) {
            var campo = document.getElementById(id);
            if (campo && !campo.placeholder) {
                campo.placeholder = ' ';
            }
        });
    }

    function adicionarDireitos() {
        var formulario = document.querySelector('.pf-v5-c-login__main');
        if (!formulario || formulario.querySelector('.datagis-direitos')) {
            return;
        }
        var link = document.createElement('a');
        link.href = EQUIPE_URL;
        link.target = '_blank';
        link.rel = 'noopener noreferrer';
        link.textContent = 'LizardsDBA';

        var direitos = document.createElement('p');
        direitos.className = 'datagis-direitos';
        // \u00a9 = simbolo de copyright (escape: o arquivo pode ser servido sem charset)
        direitos.appendChild(document.createTextNode('\u00a9 2026 '));
        direitos.appendChild(link);
        formulario.appendChild(direitos);
    }

    function preparar() {
        prepararCampos();
        adicionarDireitos();
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', preparar);
    } else {
        preparar();
    }
})();
