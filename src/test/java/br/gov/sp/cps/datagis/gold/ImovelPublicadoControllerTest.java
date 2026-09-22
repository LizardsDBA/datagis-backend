package br.gov.sp.cps.datagis.gold;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ImovelPublicadoControllerTest {

    private MockMvc mockMvc;

    @Mock
    private ImovelPublicadoService service;

    @InjectMocks
    private ImovelPublicadoController controller;

    @BeforeEach
    void setUp() {
        // Inicializa o MockMvc de forma isolada, sem subir o contexto do Spring Boot
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listarTodos_DeveRetornarStatus200ELista() throws Exception {
        ImovelPublicado imovel = new ImovelPublicado();
        imovel.setCodImovel("PR-DEMO-789");
        when(service.listarTodos()).thenReturn(List.of(imovel));

        mockMvc.perform(get("/api/gold/imoveis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].codImovel").value("PR-DEMO-789"));
    }

    @Test
    void buscarPorCodImovel_DeveRetornarStatus200EImovel() throws Exception {
        ImovelPublicado imovel = new ImovelPublicado();
        imovel.setCodImovel("PR-DEMO-000");
        imovel.setValorIdesmat(0.11);
        when(service.buscarPorCodImovel("PR-DEMO-000")).thenReturn(imovel);

        mockMvc.perform(get("/api/gold/imoveis/PR-DEMO-000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codImovel").value("PR-DEMO-000"))
                .andExpect(jsonPath("$.valorIdesmat").value(0.11));
    }
}