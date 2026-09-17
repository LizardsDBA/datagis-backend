package br.gov.sp.cps.datagis.controle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.locationtech.jts.io.WKTReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/** Oracle local real. Cada teste usa rollback; não executa migrations ou ingestões. */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.quartz.auto-startup=false",
        "spring.batch.job.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "DATAGIS_ORACLE_IT", matches = "true")
@Transactional
class ConjuntoOracleIT {
    @Autowired private ConjuntoService service;
    @Autowired private FonteDadoRepository fontes;
    @Autowired private ConjuntoRepository conjuntos;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private WebApplicationContext context;
    private MockMvc mvc;
    private Long fonteId;

    @BeforeEach
    void preparar() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        fonteId = fontes.saveAndFlush(FonteDado.builder()
                .nome("TESTE_ORACLE_LIZARDS_8_ROLLBACK")
                .tipoAquisicao("UPLOAD_MANUAL").formatoEsperado("GEOJSON")
                .dataAdd(java.time.LocalDateTime.now()).build()).getId();
    }

    @Test
    void migrationV032EMetadadosEspaciaisEstaoAplicados() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM "flyway_schema_history"
                WHERE "version" = '032' AND "success" = 1
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT srid FROM user_sdo_geom_metadata
                WHERE table_name = 'CONJUNTO' AND column_name = 'COBERTURA_TERRITORIAL'
                """, Integer.class)).isEqualTo(4674);
    }

    @Test
    void persisteERelePoligonoOracleSpatial() throws Exception {
        var geometria = new WKTReader().read("POLYGON ((-50 -25, -49 -25, -49 -24, -50 -25))");
        geometria.setSRID(4674);
        var request = new ConjuntoRequest();
        request.setFonteDadoId(fonteId);
        request.setCompetencia(LocalDate.of(2026, 9, 1));
        request.setStatusCamada(StatusCamada.ATIVA);
        request.setCoberturaTerritorial(geometria);
        var salvo = service.criar(request);
        entityManager.flush();
        entityManager.clear();

        var relido = conjuntos.findById(salvo.getId()).orElseThrow();
        assertThat(relido.getCoberturaTerritorial().equalsExact(geometria)).isTrue();
        assertThat(relido.getCoberturaTerritorial().getSRID()).isEqualTo(4674);
        assertThat(jdbc.queryForObject("""
                SELECT SDO_GEOM.VALIDATE_GEOMETRY_WITH_CONTEXT(c.cobertura_territorial, 0.05)
                FROM conjunto c WHERE c.id = ?
                """, String.class, salvo.getId())).isEqualTo("TRUE");
    }

    @Test
    void cadastraPeloHttpSemGeometriaComoFormulario() throws Exception {
        mvc.perform(post("/conjuntos").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fonteDadoId":%d,"competencia":"2026-09-01","statusCamada":"ATIVA","coberturaTerritorial":null}
                        """.formatted(fonteId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fonteDadoNome").value("TESTE_ORACLE_LIZARDS_8_ROLLBACK"));
        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM conjunto WHERE fonte_dado_id = ?",
                Integer.class, fonteId)).isEqualTo(1);
    }

    @Test
    void listaGeometriaRealEmGeoJsonCompativelComMapa() throws Exception {
        var geometria = new WKTReader().read("POLYGON ((-50 -25, -49 -25, -49 -24, -50 -25))");
        geometria.setSRID(4674);
        var conjunto = conjuntos.saveAndFlush(Conjunto.builder()
                .fonteDado(fontes.getReferenceById(fonteId))
                .competencia(LocalDate.of(2026, 9, 1)).statusCamada(StatusCamada.ATIVA)
                .coberturaTerritorial(geometria).build());
        Long id = conjunto.getId();
        entityManager.clear();

        mvc.perform(get("/conjuntos"))
                .andDo(result -> {
                    if (result.getResponse().getStatus() != 200) {
                        String body = result.getResponse().getContentAsString();
                        System.out.println("Falha na listagem espacial: " + body.substring(0, Math.min(body.length(), 1200)));
                    }
                })
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].coberturaTerritorial.type").value("Polygon"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].coberturaTerritorial.coordinates[0][0][0]").value(-50.0));
    }
}
