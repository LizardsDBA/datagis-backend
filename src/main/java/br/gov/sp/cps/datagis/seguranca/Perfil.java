package br.gov.sp.cps.datagis.seguranca;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Perfis de acesso: so pacotes de {@link Permissao}. No Keycloak cada perfil vira uma
 * role composta ({@code codigo}) contendo exatamente as permissoes daqui - o Keycloak
 * expande a composta e o JWT ja chega com a lista final de permissoes, entao o codigo
 * nunca precisa saber de perfil pra decidir acesso.
 *
 * <p>Um usuario pode ter varios perfis e ainda permissoes avulsas. Mudar o que um perfil
 * pode fazer = mudar o conjunto aqui; o {@link KeycloakProvisionador} ajusta a composta
 * no Keycloak na proxima subida (adiciona o que falta e remove o que saiu).
 *
 * <p>{@code usuarioDev}: login de teste criado quando {@code app.keycloak.usuarios-dev=true}
 * (senha igual ao login). Um perfil por ticket do board: Administrador (LIZARDS-47),
 * Operador (LIZARDS-45), Analista (LIZARDS-46), Auditor (LIZARDS-48).
 */
public enum Perfil {

    ADMINISTRADOR("perfil-administrador", "Administrador",
            "Acesso a todas as telas, cadastro de usuarios e controle de acesso",
            "admin", EnumSet.allOf(Permissao.class)),

    /** LIZARDS-45: cuida da entrada do dado - fontes, lotes/pipeline e quarentena. */
    OPERADOR("perfil-operador", "Operador",
            "Cadastro e execucao de fontes, lotes e pipeline, tratamento da quarentena",
            "operador", EnumSet.of(
                    Permissao.FONTE_VISUALIZAR, Permissao.FONTE_GERENCIAR,
                    Permissao.LOTE_VISUALIZAR, Permissao.LOTE_GERENCIAR,
                    Permissao.QUARENTENA_VISUALIZAR, Permissao.QUARENTENA_TRATAR,
                    Permissao.DADOS_VISUALIZAR));

    private final String codigo;
    private final String nome;
    private final String descricao;
    private final String usuarioDev;
    private final Set<Permissao> permissoes;

    Perfil(String codigo, String nome, String descricao, String usuarioDev, Set<Permissao> permissoes) {
        this.codigo = codigo;
        this.nome = nome;
        this.descricao = descricao;
        this.usuarioDev = usuarioDev;
        this.permissoes = Collections.unmodifiableSet(permissoes);
    }

    public String codigo() {
        return codigo;
    }

    public String nome() {
        return nome;
    }

    public String descricao() {
        return descricao;
    }

    public String usuarioDev() {
        return usuarioDev;
    }

    public Set<Permissao> permissoes() {
        return permissoes;
    }

    public static boolean ehPerfil(String codigoRole) {
        for (Perfil p : values()) {
            if (p.codigo.equals(codigoRole)) {
                return true;
            }
        }
        return false;
    }
}
