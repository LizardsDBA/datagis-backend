/*
 * Rotulo flutuante da tela de login e da troca de senha (LIZARDS-61).
 *
 * O CSS sobe o rotulo para o topo do campo quando ele tem foco ou valor, e para saber se
 * o campo esta vazio usa :placeholder-shown - que so funciona em campo COM placeholder.
 * Os campos sao montados pelas macros do field.ftl do Keycloak, que nao tem opcao de
 * placeholder; em vez de copiar login.ftl + field.ftl para o tema (e conferir os dois a
 * cada atualizacao do Keycloak), este script poe um placeholder de um espaco so: nada
 * aparece na tela e o leitor de tela nao repete o rotulo.
 * Sem JavaScript, o rotulo fica sempre no alto do campo (legivel, so sem a animacao).
 */
(function () {
    var CAMPOS = ['username', 'password', 'password-new', 'password-confirm'];

    function preparar() {
        CAMPOS.forEach(function (id) {
            var campo = document.getElementById(id);
            if (campo && !campo.placeholder) {
                campo.placeholder = ' ';
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', preparar);
    } else {
        preparar();
    }
})();
