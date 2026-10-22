package br.gov.sp.cps.datagis.seguranca;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** Composicao de cada perfil, exatamente como aprovada pela equipe (docs/controle-de-acesso-rbac.md). */
class PerfilTest {

    @Test
    void administradorTemTodasAsPermissoes() {
        assertThat(Perfil.ADMINISTRADOR.permissoes()).containsExactlyInAnyOrder(Permissao.values());
    }

    @Test
    void operadorCuidaDeFontesLotesQuarentenaEVeOsDados() {
        assertThat(Perfil.OPERADOR.permissoes()).containsExactlyInAnyOrder(
                Permissao.FONTE_VISUALIZAR, Permissao.FONTE_GERENCIAR,
                Permissao.LOTE_VISUALIZAR, Permissao.LOTE_GERENCIAR,
                Permissao.QUARENTENA_VISUALIZAR, Permissao.QUARENTENA_TRATAR,
                Permissao.DADOS_VISUALIZAR);
    }

    @Test
    void analistaCuidaDeConjuntosEIndicadoresEVeFontesEDados() {
        assertThat(Perfil.ANALISTA.permissoes()).containsExactlyInAnyOrder(
                Permissao.CONJUNTO_VISUALIZAR, Permissao.CONJUNTO_GERENCIAR,
                Permissao.INDICADOR_VISUALIZAR, Permissao.INDICADOR_PUBLICAR,
                Permissao.FONTE_VISUALIZAR, Permissao.DADOS_VISUALIZAR);
    }

    @Test
    void auditorSoLeTodasAsTelasEOLogDeAuditoria() {
        assertThat(Perfil.AUDITOR.permissoes()).containsExactlyInAnyOrder(
                Permissao.FONTE_VISUALIZAR, Permissao.LOTE_VISUALIZAR,
                Permissao.QUARENTENA_VISUALIZAR, Permissao.DADOS_VISUALIZAR,
                Permissao.CONJUNTO_VISUALIZAR, Permissao.INDICADOR_VISUALIZAR,
                Permissao.AUDITORIA_VISUALIZAR);
        // Bate com "todas as :visualizar + auditoria:visualizar" do desenho aprovado
        assertThat(Perfil.AUDITOR.permissoes()).allMatch(p -> p.codigo().endsWith(":visualizar"))
                .containsAll(Arrays.stream(Permissao.values()).filter(p -> p.codigo().endsWith(":visualizar")).toList());
    }

    @Test
    void soOAdministradorGerenciaUsuarios() {
        assertThat(Arrays.stream(Perfil.values()).filter(p -> p.permissoes().contains(Permissao.USUARIO_GERENCIAR)))
                .containsExactly(Perfil.ADMINISTRADOR);
    }

    @Test
    void codigosEUsuariosDeTesteSaoUnicos() {
        assertThat(Arrays.stream(Perfil.values()).map(Perfil::codigo)).doesNotHaveDuplicates()
                .allMatch(codigo -> codigo.startsWith("perfil-"));
        assertThat(Arrays.stream(Perfil.values()).map(Perfil::usuarioDev)).doesNotHaveDuplicates();
    }
}
