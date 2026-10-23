package br.gov.sp.cps.datagis.gold;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.gov.sp.cps.datagis.config.TratadorDeErro;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Testes dos endpoints da tela de consulta de indicadores (LIZARDS-34).
 *
 * <p>
 * Garante o contrato que o front vai consumir: os nomes dos campos no JSON e o
 * fato de indicador sem valor chegar como null, e nao como 0 — a tela usa essa
 * diferenca para pintar celula vazia em vez de uma faixa vermelha falsa.
 */
@ExtendWith(MockitoExtension.class)
class ConsultaIndicadoresControllerTest {

    @Mock
    private ConsultaIndicadoresService service;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ConsultaIndicadoresController(service))
                .setControllerAdvice(new TratadorDeErro())
                .build();
    }

    @Test
    @DisplayName("GET /api/indicadores/municipios - Deve retornar 200 OK com os indicadores agregados")
    void deveListarPorMunicipio() throws Exception {
        ConsultaMunicipioResponse curitiba = new ConsultaMunicipioResponse(
                4106902L, "Curitiba", 111L, 7L, 1316.97,
                16.25, 6.05, 17.67, 22.32, 0.00, 11.36, 4.10);
        ConsultaMunicipioResponse pontaGrossa = new ConsultaMunicipioResponse(
                4119905L, "Ponta Grossa", 2657L, 812L, 187029.84,
                27.31, 16.98, 59.69, 17.95, 0.23, 28.94, 9.87);

        when(service.listarPorMunicipio()).thenReturn(List.of(curitiba, pontaGrossa));

        mvc.perform(get("/api/indicadores/municipios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].codIbge").value(4106902))
                .andExpect(jsonPath("$[0].nome").value("Curitiba"))
                .andExpect(jsonPath("$[0].qtdImoveis").value(111))
                .andExpect(jsonPath("$[0].qtdImoveisComIntersecao").value(7))
                .andExpect(jsonPath("$[0].areaTotalHa").value(1316.97))
                .andExpect(jsonPath("$[0].valorIcv").value(16.25))
                .andExpect(jsonPath("$[0].valorIfc").value(4.10))
                .andExpect(jsonPath("$[1].nome").value("Ponta Grossa"))
                .andExpect(jsonPath("$[1].qtdImoveis").value(2657));

        verify(service).listarPorMunicipio();
    }

    @Test
    @DisplayName("GET /api/indicadores/imoveis - Deve retornar 200 OK com o codigo completo do imovel")
    void deveListarPorImovel() throws Exception {
        ConsultaImovelResponse imovel = new ConsultaImovelResponse(
                "PR-4106902-00B2EA81503E46B8BD95A7E35BBB8472",
                4106902L, "Curitiba", 8.89,
                15.54, 6.05, 17.67, 22.32, 0.00, 0.00, 4.10,
                LocalDateTime.of(2026, 10, 16, 23, 30));

        when(service.listarPorImovel()).thenReturn(List.of(imovel));

        mvc.perform(get("/api/indicadores/imoveis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                // O codigo vai inteiro no JSON; abreviar e decisao de exibicao do front,
                // senao a busca pelo codigo completo deixa de funcionar.
                .andExpect(jsonPath("$[0].codImovel")
                        .value("PR-4106902-00B2EA81503E46B8BD95A7E35BBB8472"))
                .andExpect(jsonPath("$[0].municipioCodIbge").value(4106902))
                .andExpect(jsonPath("$[0].municipioNome").value("Curitiba"))
                .andExpect(jsonPath("$[0].areaHa").value(8.89))
                .andExpect(jsonPath("$[0].valorIcv").value(15.54));

        verify(service).listarPorImovel();
    }

    @Test
    @DisplayName("GET /api/indicadores/imoveis - Indicador sem valor deve chegar como null, nao como zero")
    void indicadorSemValorDeveChegarComoNulo() throws Exception {
        ConsultaImovelResponse semIcv = new ConsultaImovelResponse(
                "PR-4106902-ABC", 4106902L, "Curitiba", 8.89,
                null, 6.05, null, null, 0.00, null, null,
                LocalDateTime.of(2026, 10, 16, 23, 30));

        when(service.listarPorImovel()).thenReturn(List.of(semIcv));

        mvc.perform(get("/api/indicadores/imoveis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].valorIcv").doesNotExist())
                .andExpect(jsonPath("$[0].valorIrl").value(6.05))
                // Zero calculado e diferente de ausencia de calculo: precisa vir como 0.
                .andExpect(jsonPath("$[0].valorIae").value(0.0));
    }

    @Test
    @DisplayName("GET /api/indicadores/municipios - Deve retornar lista vazia quando nao houver publicacao")
    void deveRetornarListaVaziaQuandoNaoHaPublicacao() throws Exception {
        when(service.listarPorMunicipio()).thenReturn(List.of());

        mvc.perform(get("/api/indicadores/municipios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(service).listarPorMunicipio();
    }

    @Test
    @DisplayName("GET /api/indicadores/imoveis - Deve retornar lista vazia quando nao houver imovel")
    void deveRetornarListaVaziaDeImoveis() throws Exception {
        when(service.listarPorImovel()).thenReturn(List.of());

        mvc.perform(get("/api/indicadores/imoveis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        verify(service).listarPorImovel();
    }
}