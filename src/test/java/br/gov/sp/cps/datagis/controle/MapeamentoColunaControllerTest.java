package br.gov.sp.cps.datagis.controle;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class MapeamentoColunaControllerTest {

    @Mock
    private MapeamentoColunaRepository repository;

    @Mock
    private FonteDadoRepository fonteDadoRepository;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        MapeamentoColunaController controller = new MapeamentoColunaController(repository, fonteDadoRepository);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("DELETE /api/fontes/{fonteId}/mapeamentos remove todos os mapeamentos da fonte e retorna 204")
    void removerTodosPorFonteComSucesso() throws Exception {
        when(fonteDadoRepository.existsById(10L)).thenReturn(true);

        mvc.perform(delete("/api/fontes/10/mapeamentos"))
                .andExpect(status().isNoContent());

        verify(repository).deleteByFonteDadoId(10L);
    }

    @Test
    @DisplayName("DELETE /api/fontes/{fonteId}/mapeamentos retorna 404 quando fonte não existe")
    void removerTodosPorFonteInexistente() throws Exception {
        when(fonteDadoRepository.existsById(99L)).thenReturn(false);

        mvc.perform(delete("/api/fontes/99/mapeamentos"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("POST /api/fontes/{fonteId}/mapeamentos preenche tabelaDestino a partir da entidadeSilver da fonte quando omitido")
    void salvarMapeamentosComFallbackTabelaDestino() throws Exception {
        FonteDado fonte = new FonteDado();
        fonte.setId(5L);
        fonte.setEntidadeSilver("imovel_rural");

        when(fonteDadoRepository.findById(5L)).thenReturn(Optional.of(fonte));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/fontes/5/mapeamentos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "colunaOrigem": "cod_imovel",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  }
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tabelaDestino").value("imovel_rural"))
                .andExpect(jsonPath("$[0].fonteDadoId").value(5));
    }

    @Test
    @DisplayName("POST /api/fontes/{fonteId}/mapeamentos preserva tabelaDestino enviada no corpo")
    void salvarMapeamentosPreservandoTabelaDestino() throws Exception {
        FonteDado fonte = new FonteDado();
        fonte.setId(5L);
        fonte.setEntidadeSilver("imovel_rural");

        when(fonteDadoRepository.findById(5L)).thenReturn(Optional.of(fonte));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/fontes/5/mapeamentos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "colunaOrigem": "num_termo",
                                    "atributoDestino": "numero_termo",
                                    "tabelaDestino": "embargo_ambiental",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  }
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tabelaDestino").value("embargo_ambiental"));
    }
}
