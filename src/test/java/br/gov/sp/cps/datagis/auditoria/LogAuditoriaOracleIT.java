package br.gov.sp.cps.datagis.auditoria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.gov.sp.cps.datagis.auditoria.LogAuditoriaRepositorio.FiltroConsultaAuditoria;
import br.gov.sp.cps.datagis.auditoria.LogAuditoriaRepositorio.PaginaAuditoria;
import br.gov.sp.cps.datagis.auditoria.LogAuditoriaRepositorio.RegistroAuditoria;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Oracle local real (V075 aplicada). Cada teste faz rollback - a tabela e somente insercao,
 * entao nao daria pra limpar de outro jeito.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
@Transactional
class LogAuditoriaOracleIT {

    private static final String LOGIN = "teste.oracle.it.lizards48";

    @Autowired private LogAuditoriaRepositorio repositorio;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void gravaEConsultaComFiltros() {
        repositorio.registrar(registro("POST", "/api/quarentena/lotes/45/liberar", "Liberar lote para a Silver", 200));
        repositorio.registrar(registro("POST", "/api/fontes/3/executar", "Executar ingestao da fonte", 403));

        PaginaAuditoria todos = repositorio.buscar(filtro(null, null), 0, 50);
        assertThat(todos.total()).isEqualTo(2);
        // Mais recente primeiro
        assertThat(todos.itens().get(0).acao()).isEqualTo("Executar ingestao da fonte");
        assertThat(todos.itens().get(0).usuarioLogin()).isEqualTo(LOGIN);

        PaginaAuditoria negados = repositorio.buscar(filtro(false, null), 0, 50);
        assertThat(negados.itens()).singleElement().satisfies(item -> {
            assertThat(item.statusHttp()).isEqualTo(403);
            assertThat(item.sucesso()).isFalse();
        });

        PaginaAuditoria porTexto = repositorio.buscar(filtro(null, "silver"), 0, 50);
        assertThat(porTexto.itens()).singleElement()
                .satisfies(item -> assertThat(item.rotaPadrao()).isEqualTo("/api/quarentena/lotes/{loteId}/liberar"));
    }

    @Test
    void paginaComOffset() {
        for (int i = 0; i < 3; i++) {
            repositorio.registrar(registro("POST", "/api/fontes", "Cadastrar fonte de dados", 201));
        }
        PaginaAuditoria segunda = repositorio.buscar(filtro(null, null), 1, 2);
        assertThat(segunda.total()).isEqualTo(3);
        assertThat(segunda.itens()).hasSize(1);
    }

    /** V082: o antes e depois vai e volta como JSON; acao sem eles continua com null. */
    @Test
    void gravaOAntesEDepoisDaAlteracao() {
        RegistroAuditoria base = registro("PUT", "/api/admin/usuarios/u1", "Editar dados de usuario", 200);
        repositorio.registrar(new RegistroAuditoria(base.dataHora(), base.usuarioId(), base.usuarioLogin(), base.perfis(),
                base.metodo(), base.rota(), base.rotaPadrao(), base.parametros(), base.acao(), base.statusHttp(),
                base.sucesso(), base.duracaoMs(), base.ipOrigem(),
                "{\"email\":\"a@x.com\",\"ativo\":true}", "{\"email\":\"b@x.com\",\"ativo\":false}"));
        repositorio.registrar(registro("POST", "/api/fontes/3/executar", "Executar ingestao da fonte", 200));

        PaginaAuditoria pagina = repositorio.buscar(filtro(null, null), 0, 50);
        assertThat(pagina.itens()).filteredOn(i -> i.metodo().equals("PUT")).singleElement().satisfies(item -> {
            assertThat(item.dadosAntes()).contains("a@x.com");
            assertThat(item.dadosDepois()).contains("b@x.com");
        });
        assertThat(pagina.itens()).filteredOn(i -> i.metodo().equals("POST")).singleElement().satisfies(item -> {
            assertThat(item.dadosAntes()).isNull();
            assertThat(item.dadosDepois()).isNull();
        });
    }

        /** RN025: nem o dono da tabela consegue alterar ou apagar o historico. */
    @Test
    void tabelaRecusaUpdateEDelete() {
        repositorio.registrar(registro("POST", "/api/fontes", "Cadastrar fonte de dados", 201));

        assertThatThrownBy(() -> jdbc.update("UPDATE log_auditoria SET status_http = 200 WHERE usuario_login = ?", LOGIN))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ORA-20025");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM log_auditoria WHERE usuario_login = ?", LOGIN))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ORA-20025");
    }

    private static FiltroConsultaAuditoria filtro(Boolean sucesso, String texto) {
        LocalDate hoje = LocalDate.now(ZoneId.systemDefault());
        return new FiltroConsultaAuditoria(LOGIN, null, sucesso, texto, hoje.minusDays(1), hoje.plusDays(1));
    }

    private static RegistroAuditoria registro(String metodo, String rota, String acao, int status) {
        String molde = DescricaoAcao.identificar(metodo, rota).map(DescricaoAcao.Acao::rotaPadrao).orElse(null);
        return new RegistroAuditoria(Instant.now(), "uuid-teste", LOGIN, "perfil-operador", metodo, rota, molde,
                null, acao, status, status < 400, 12L, "127.0.0.1", null, null);
    }
}
