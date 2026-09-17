package br.gov.sp.cps.datagis.controle;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.gov.sp.cps.datagis.config.TratadorDeErro;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class ConjuntoControllerTest {
    @Mock private ConjuntoService service;
    private MockMvc mvc;

    @BeforeEach
    void configurar() {
        mvc = MockMvcBuilders.standaloneSetup(new ConjuntoController(service))
                .setControllerAdvice(new TratadorDeErro()).build();
    }

    @Test
    void listaConjuntosComoJson() throws Exception {
        when(service.listarTodos()).thenReturn(List.of(ConjuntoResponse.builder()
                .id(42L).fonteDadoId(7L).fonteDadoNome("CAR PR")
                .competencia(LocalDate.of(2026, 9, 1)).statusCamada(StatusCamada.ATIVA).build()));
        mvc.perform(get("/conjuntos")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fonteDadoNome").value("CAR PR"))
                .andExpect(jsonPath("$[0].competencia").value("2026-09-01"))
                .andExpect(jsonPath("$[0].statusCamada").value("ATIVA"));
    }

    @Test
    void cadastroRetorna201EDesserializaPayloadDoFrontend() throws Exception {
        when(service.criar(any())).thenAnswer(call -> {
            ConjuntoRequest request = call.getArgument(0);
            return ConjuntoResponse.builder().id(42L).fonteDadoId(request.getFonteDadoId())
                    .competencia(request.getCompetencia()).statusCamada(request.getStatusCamada()).build();
        });
        mvc.perform(post("/conjuntos").contentType(MediaType.APPLICATION_JSON).content("""
                {"fonteDadoId":7,"competencia":"2026-09-01","statusCamada":"ATIVA","coberturaTerritorial":null}
                """))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.fonteDadoId").value(7))
                .andExpect(jsonPath("$.competencia").value("2026-09-01"))
                .andExpect(jsonPath("$.statusCamada").value("ATIVA"));
    }

    @Test
    void fonteInexistenteRetorna400ComMensagem() throws Exception {
        when(service.criar(any())).thenThrow(new IllegalArgumentException("Fonte de Dado não encontrada: 99"));
        mvc.perform(post("/conjuntos").contentType(MediaType.APPLICATION_JSON).content("""
                {"fonteDadoId":99,"competencia":"2026-09-01","statusCamada":"ATIVA"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Fonte de Dado não encontrada: 99"));
    }

    @Test
    void statusDesconhecidoRetorna400SemChamarServico() throws Exception {
        mvc.perform(post("/conjuntos").contentType(MediaType.APPLICATION_JSON).content("""
                {"fonteDadoId":7,"competencia":"2026-09-01","statusCamada":"INVALIDO"}
                """))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
