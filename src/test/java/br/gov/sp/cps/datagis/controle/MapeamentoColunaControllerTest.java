package br.gov.sp.cps.datagis.controle;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new br.gov.sp.cps.datagis.config.TratadorDeErro())
                .build();
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

    @Test
    @DisplayName("POST /api/fontes/{fonteId}/mapeamentos permite múltiplas colunas de origem para a mesma coluna Silver")
    void salvarMapeamentosPermiteMultiplasOrigensParaMesmoAtributoDestino() throws Exception {
        FonteDado fonte = new FonteDado();
        fonte.setId(5L);
        when(fonteDadoRepository.findById(5L)).thenReturn(Optional.of(fonte));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/fontes/5/mapeamentos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "colunaOrigem": "cod_imovel_1",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  },
                                  {
                                    "colunaOrigem": "cod_imovel_2",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  }
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].atributoDestino").value("cod_imovel"))
                .andExpect(jsonPath("$[1].atributoDestino").value("cod_imovel"));
    }

    @Test
    @DisplayName("POST /api/fontes/{fonteId}/mapeamentos rejeita duplicidade de par exato (colunaOrigem -> atributoDestino)")
    void salvarMapeamentosRejeitaDuplicidadeParExatoNaMesmaRequisicao() throws Exception {
        FonteDado fonte = new FonteDado();
        fonte.setId(5L);
        when(fonteDadoRepository.findById(5L)).thenReturn(Optional.of(fonte));

        mvc.perform(post("/api/fontes/5/mapeamentos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "colunaOrigem": "cod_imovel",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  },
                                  {
                                    "colunaOrigem": "cod_imovel",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  }
                                ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("O par de mapeamento (cod_imovel -> cod_imovel) foi informado mais de uma vez."));
    }

    @Test
    @DisplayName("POST /api/fontes/{fonteId}/mapeamentos rejeita mesma coluna de origem mapeada para destinos distintos")
    void salvarMapeamentosRejeitaMesmaColunaOrigemParaDiferentesDestinos() throws Exception {
        FonteDado fonte = new FonteDado();
        fonte.setId(5L);
        when(fonteDadoRepository.findById(5L)).thenReturn(Optional.of(fonte));

        mvc.perform(post("/api/fontes/5/mapeamentos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "colunaOrigem": "num_car",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  },
                                  {
                                    "colunaOrigem": "num_car",
                                    "atributoDestino": "area_ha",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "numero"
                                  }
                                ]
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("A coluna de origem \"num_car\" foi mapeada mais de uma vez nesta requisição."));
    }

    @Test
    @DisplayName("POST /api/fontes/{fonteId}/mapeamentos permite cadastrar nova origem para coluna Silver já existente no banco")
    void salvarMapeamentosPermiteMultiplasOrigensQuandoJaExisteOutraNoBanco() throws Exception {
        FonteDado fonte = new FonteDado();
        fonte.setId(5L);
        when(fonteDadoRepository.findById(5L)).thenReturn(Optional.of(fonte));

        MapeamentoColuna existente = MapeamentoColuna.builder()
                .id(20L)
                .fonteDadoId(5L)
                .colunaOrigem("num_sicar")
                .atributoDestino("cod_imovel")
                .destinoTipo("COLUNA")
                .build();
        when(repository.findByFonteDadoId(5L)).thenReturn(java.util.List.of(existente));
        when(repository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(post("/api/fontes/5/mapeamentos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "colunaOrigem": "outro_campo",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  }
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].colunaOrigem").value("outro_campo"))
                .andExpect(jsonPath("$[0].atributoDestino").value("cod_imovel"));
    }

    @Test
    @DisplayName("PUT /api/fontes/{fonteId}/mapeamentos/atributo/{atributoDestino} sincroniza todas as origens do atributo")
    void sincronizarPorAtributoComSucesso() throws Exception {
        FonteDado fonte = new FonteDado();
        fonte.setId(5L);
        fonte.setEntidadeSilver("imovel_rural");
        when(fonteDadoRepository.findById(5L)).thenReturn(Optional.of(fonte));

        MapeamentoColuna existente = new MapeamentoColuna();
        existente.setId(10L);
        existente.setFonteDadoId(5L);
        existente.setColunaOrigem("num_car");
        existente.setAtributoDestino("cod_imovel");

        MapeamentoColuna antigoParaRemover = new MapeamentoColuna();
        antigoParaRemover.setId(11L);
        antigoParaRemover.setFonteDadoId(5L);
        antigoParaRemover.setColunaOrigem("col_antiga");
        antigoParaRemover.setAtributoDestino("cod_imovel");

        when(repository.findByFonteDadoIdAndAtributoDestinoIgnoreCase(5L, "cod_imovel"))
                .thenReturn(List.of(existente, antigoParaRemover));
        when(repository.saveAllAndFlush(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/fontes/5/mapeamentos/atributo/cod_imovel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                [
                                  {
                                    "colunaOrigem": "num_car",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  },
                                  {
                                    "colunaOrigem": "numero_car",
                                    "atributoDestino": "cod_imovel",
                                    "destinoTipo": "COLUNA",
                                    "tipoDado": "texto"
                                  }
                                ]
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        verify(repository).deleteAll(List.of(antigoParaRemover));
        verify(repository).flush();
        verify(repository).saveAllAndFlush(anyList());
    }

    @Test
    @DisplayName("DELETE /api/fontes/{fonteId}/mapeamentos/atributo/{atributoDestino} remove todas as origens do atributo")
    void removerPorAtributoComSucesso() throws Exception {
        when(fonteDadoRepository.existsById(5L)).thenReturn(true);

        mvc.perform(delete("/api/fontes/5/mapeamentos/atributo/cod_imovel"))
                .andExpect(status().isNoContent());

        verify(repository).deleteByFonteDadoIdAndAtributoDestinoIgnoreCase(5L, "cod_imovel");
    }
}
