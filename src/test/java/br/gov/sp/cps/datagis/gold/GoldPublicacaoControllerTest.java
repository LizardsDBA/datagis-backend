package br.gov.sp.cps.datagis.gold;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.gov.sp.cps.datagis.config.TratadorDeErro;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class GoldPublicacaoControllerTest {

    private MockMvc mockMvc;

    @Mock
    private GoldPublicacaoService service;

    @InjectMocks
    private GoldPublicacaoController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TratadorDeErro())
                .build();
    }

    @Test
    @DisplayName("POST /api/gold/publicar deve retornar 202 Accepted e despachar publicacao assincrona")
    void publicar_SemExecucaoAtiva_DeveRetornar202EDespacharAssincrono() throws Exception {
        var statusIndicadores = new GoldPublicacaoService.StatusPublicacaoIndicadores(
                1L, "CONCLUIDA", 10L, 10L, 10L, 0L, null, null, null);
        var statusConsolidado = new GoldPublicacaoService.StatusGoldConsolidado(
                false, statusIndicadores, Collections.emptyList());

        when(service.obterStatusConsolidado()).thenReturn(statusConsolidado);

        mockMvc.perform(post("/api/gold/publicar"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.algumEmAndamento").value(false));

        verify(service).publicarTudoAssincrono();
    }

    @Test
    @DisplayName("POST /api/gold/publicar com processo ativo deve retornar 409 Conflict")
    void publicar_ComExecucaoAtiva_DeveRetornar409Conflict() throws Exception {
        var statusIndicadores = new GoldPublicacaoService.StatusPublicacaoIndicadores(
                1L, "EM_ANDAMENTO", 10L, 5L, 5L, 0L, null, null, null);
        var statusConsolidado = new GoldPublicacaoService.StatusGoldConsolidado(
                true, statusIndicadores, Collections.emptyList());

        when(service.obterStatusConsolidado()).thenReturn(statusConsolidado);

        mockMvc.perform(post("/api/gold/publicar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("Já existe uma publicação para a camada Gold em andamento."));

        verify(service, never()).publicarTudoAssincrono();
    }
}
