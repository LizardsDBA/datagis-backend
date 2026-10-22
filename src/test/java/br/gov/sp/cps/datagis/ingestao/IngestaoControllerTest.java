package br.gov.sp.cps.datagis.ingestao;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.gov.sp.cps.datagis.config.TratadorDeErro;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class IngestaoControllerTest {

    private MockMvc mockMvc;

    @Mock
    private IngestaoExecucaoService execucaoService;

    @Mock
    private ProgressoExecucaoRegistry progressoRegistry;

    @InjectMocks
    private IngestaoController controller;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new TratadorDeErro())
                .build();
    }

    @Test
    @DisplayName("POST /api/fontes/{id}/executar deve retornar 202 Accepted quando livre")
    void executar_FonteLivre_DeveRetornar202EDespacharAssincrono() throws Exception {
        when(progressoRegistry.isExecutando(1L)).thenReturn(false);
        when(progressoRegistry.obter(1L)).thenReturn(Optional.of(
                new ProgressoExecucao(FaseExecucao.INICIANDO, 0, null, null, LocalDateTime.now())));

        mockMvc.perform(post("/api/fontes/1/executar"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fase").value("INICIANDO"));

        verify(progressoRegistry).iniciar(1L);
        verify(execucaoService).executarAssincrono(1L);
    }

    @Test
    @DisplayName("POST /api/fontes/{id}/executar deve retornar 409 Conflict quando já em execução")
    void executar_FonteEmExecucao_DeveRetornar409Conflict() throws Exception {
        when(progressoRegistry.isExecutando(1L)).thenReturn(true);

        mockMvc.perform(post("/api/fontes/1/executar"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("A fonte 1 já está com uma execução em andamento."));

        verify(execucaoService, never()).executarAssincrono(1L);
    }

    @Test
    @DisplayName("POST /api/fontes/{id}/upload deve retornar 202 Accepted quando livre")
    void upload_FonteLivre_DeveRetornar202EDespacharAssincrono() throws Exception {
        when(progressoRegistry.isExecutando(2L)).thenReturn(false);
        when(progressoRegistry.obter(2L)).thenReturn(Optional.of(
                new ProgressoExecucao(FaseExecucao.INICIANDO, 0, null, null, LocalDateTime.now())));

        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "dados.zip", "application/zip", "teste".getBytes());

        mockMvc.perform(multipart("/api/fontes/2/upload").file(arquivo))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fase").value("INICIANDO"));

        verify(progressoRegistry).iniciar(2L);
        verify(execucaoService).executarComUploadAssincrono(eq(2L), eq("dados.zip"), any(Path.class));
    }

    @Test
    @DisplayName("POST /api/fontes/{id}/upload deve retornar 409 Conflict quando já em execução")
    void upload_FonteEmExecucao_DeveRetornar409Conflict() throws Exception {
        when(progressoRegistry.isExecutando(2L)).thenReturn(true);

        MockMultipartFile arquivo = new MockMultipartFile("arquivo", "dados.zip", "application/zip", "teste".getBytes());

        mockMvc.perform(multipart("/api/fontes/2/upload").file(arquivo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.erro").value("A fonte 2 já está com uma execução em andamento."));

        verify(execucaoService, never()).executarComUploadAssincrono(any(), any(), any());
    }

    @Test
    @DisplayName("GET /api/fontes/{id}/progresso deve retornar progresso da fonte")
    void progresso_DeveRetornarStatusAtual() throws Exception {
        when(progressoRegistry.obter(1L)).thenReturn(Optional.of(
                new ProgressoExecucao(FaseExecucao.LENDO_REGISTROS, 150, 1000L, "Processando página 2", LocalDateTime.now())));

        mockMvc.perform(get("/api/fontes/1/progresso"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fase").value("LENDO_REGISTROS"))
                .andExpect(jsonPath("$.registrosProcessados").value(150))
                .andExpect(jsonPath("$.totalEstimado").value(1000));
    }
}
