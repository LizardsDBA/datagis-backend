package br.gov.sp.cps.datagis.gold;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class TipoIndicadorControllerTest {

        @Mock
        private TipoIndicadorService service;

        private MockMvc mvc;

        @BeforeEach
        void setup() {
                mvc = MockMvcBuilders.standaloneSetup(new TipoIndicadorController(service))
                                .setControllerAdvice(new TratadorDeErro())
                                .build();
        }

        @Test
        @DisplayName("GET /api/indicadores/tipos - Deve retornar 200 OK e lista de tipos de indicadores")
        void deveListarTiposComoJson() throws Exception {
                // O ISAP e montado sem unidade de proposito: o JSON abaixo precisa trazer
                // PERCENTUAL e "%", que e o padrao da entidade. Isso cobre o caminho inteiro
                // do valor padrao, do @Builder.Default ate a serializacao.
                TipoIndicador tipo1 = TipoIndicador.builder()
                                .id(1L)
                                .codigo("ISAP")
                                .descricao("Áreas Protegidas")
                                .temFonte(true)
                                .ativo(true)
                                .dimensao("TERRITORIAL")
                                .sentido("MENOR_MELHOR")
                                .limiteBom(30)
                                .limiteAtencao(60)
                                .ordemExibicao(4)
                                .dataAdd(LocalDateTime.of(2026, 9, 1, 12, 0))
                                .build();

                TipoIndicador tipo2 = TipoIndicador.builder()
                                .id(2L)
                                .codigo("ICV")
                                .descricao("Cobertura Vegetal")
                                .temFonte(false)
                                .ativo(true)
                                .dataAdd(LocalDateTime.of(2026, 9, 1, 12, 0))
                                .build();

                when(service.listar()).thenReturn(List.of(tipo1, tipo2));

                mvc.perform(get("/api/indicadores/tipos"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.length()").value(2))
                                .andExpect(jsonPath("$[0].id").value(1))
                                .andExpect(jsonPath("$[0].codigo").value("ISAP"))
                                .andExpect(jsonPath("$[0].descricao").value("Áreas Protegidas"))
                                .andExpect(jsonPath("$[0].temFonte").value(true))
                                .andExpect(jsonPath("$[0].ativo").value(true))
                                .andExpect(jsonPath("$[0].dimensao").value("TERRITORIAL"))
                                .andExpect(jsonPath("$[0].sentido").value("MENOR_MELHOR"))
                                .andExpect(jsonPath("$[0].limiteBom").value(30))
                                .andExpect(jsonPath("$[0].limiteAtencao").value(60))
                                .andExpect(jsonPath("$[0].ordemExibicao").value(4))
                                .andExpect(jsonPath("$[0].unidade").value("PERCENTUAL"))
                                .andExpect(jsonPath("$[0].unidadeSimbolo").value("%"))
                                .andExpect(jsonPath("$[1].id").value(2))
                                .andExpect(jsonPath("$[1].codigo").value("ICV"))
                                .andExpect(jsonPath("$[1].temFonte").value(false));

                verify(service).listar();
        }

        @Test
        @DisplayName("GET /api/indicadores/tipos/{id} - Deve retornar 200 OK quando ID existir")
        void deveBuscarPorIdComSucesso() throws Exception {
                TipoIndicador tipo = TipoIndicador.builder()
                                .id(3L)
                                .codigo("IAE")
                                .descricao("Área Embargada")
                                .temFonte(true)
                                .ativo(true)
                                .dataAdd(LocalDateTime.of(2026, 9, 1, 10, 0))
                                .build();

                when(service.buscarPorId(3L)).thenReturn(tipo);

                mvc.perform(get("/api/indicadores/tipos/3"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(3))
                                .andExpect(jsonPath("$.codigo").value("IAE"))
                                .andExpect(jsonPath("$.descricao").value("Área Embargada"));

                verify(service).buscarPorId(3L);
        }

        @Test
        @DisplayName("GET /api/indicadores/tipos/{id} - Deve expor a unidade de densidade do IFC")
        void deveExporUnidadeDeDensidade() throws Exception {
                // Unico indicador do catalogo que nao e percentual: a tela usa a unidade para
                // escolher a escala da barra e o texto exibido ao lado do valor.
                TipoIndicador ifc = TipoIndicador.builder()
                                .id(7L)
                                .codigo("IFC")
                                .descricao("Índice de Focos de Calor")
                                .temFonte(true)
                                .ativo(true)
                                .dimensao("AMBIENTAL")
                                .sentido("MENOR_MELHOR")
                                .limiteBom(30)
                                .limiteAtencao(60)
                                .ordemExibicao(7)
                                .unidade("DENSIDADE")
                                .unidadeSimbolo("focos/1.000 ha")
                                .build();

                when(service.buscarPorId(7L)).thenReturn(ifc);

                mvc.perform(get("/api/indicadores/tipos/7"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.codigo").value("IFC"))
                                .andExpect(jsonPath("$.dimensao").value("AMBIENTAL"))
                                .andExpect(jsonPath("$.sentido").value("MENOR_MELHOR"))
                                .andExpect(jsonPath("$.limiteBom").value(30))
                                .andExpect(jsonPath("$.limiteAtencao").value(60))
                                .andExpect(jsonPath("$.ordemExibicao").value(7))
                                .andExpect(jsonPath("$.unidade").value("DENSIDADE"))
                                .andExpect(jsonPath("$.unidadeSimbolo").value("focos/1.000 ha"));

                verify(service).buscarPorId(7L);
        }

        @Test
        @DisplayName("GET /api/indicadores/tipos/{id} - Deve retornar 400 quando ID não for encontrado")
        void deveRetornar400AoBuscarIdInexistente() throws Exception {
                when(service.buscarPorId(99L))
                                .thenThrow(new IllegalArgumentException("Tipo de indicador com ID 99 não encontrado"));

                mvc.perform(get("/api/indicadores/tipos/99"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.erro").value("Tipo de indicador com ID 99 não encontrado"));

                verify(service).buscarPorId(99L);
        }

        @Test
        @DisplayName("POST /api/indicadores/tipos - Deve cadastrar e retornar 201 Created")
        void deveCadastrarRetornando201() throws Exception {
                TipoIndicador criado = TipoIndicador.builder()
                                .id(10L)
                                .codigo("NOVO_IND")
                                .descricao("Novo Indicador Customizado")
                                .temFonte(true)
                                .ativo(true)
                                .dataAdd(LocalDateTime.of(2026, 9, 17, 12, 0))
                                .build();

                when(service.cadastrar(any(TipoIndicadorRequest.class))).thenReturn(criado);

                String jsonEntrada = """
                                {
                                    "codigo": "NOVO_IND",
                                    "descricao": "Novo Indicador Customizado",
                                    "temFonte": true,
                                    "ativo": true
                                }
                                """;

                mvc.perform(post("/api/indicadores/tipos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonEntrada))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").value(10))
                                .andExpect(jsonPath("$.codigo").value("NOVO_IND"))
                                .andExpect(jsonPath("$.descricao").value("Novo Indicador Customizado"))
                                .andExpect(jsonPath("$.temFonte").value(true))
                                .andExpect(jsonPath("$.ativo").value(true))
                                .andExpect(jsonPath("$.unidade").value("PERCENTUAL"))
                                .andExpect(jsonPath("$.unidadeSimbolo").value("%"));

                verify(service).cadastrar(any(TipoIndicadorRequest.class));
        }

        @Test
        @DisplayName("POST /api/indicadores/tipos - Deve retornar 400 quando serviço acusar código duplicado")
        void deveRetornar400QuandoCodigoDuplicadoNoService() throws Exception {
                when(service.cadastrar(any(TipoIndicadorRequest.class)))
                                .thenThrow(new IllegalArgumentException(
                                                "Já existe um tipo de indicador com o código ISAP"));

                String jsonEntrada = """
                                {
                                    "codigo": "ISAP",
                                    "descricao": "Tentativa Duplicada",
                                    "temFonte": true,
                                    "ativo": true
                                }
                                """;

                mvc.perform(post("/api/indicadores/tipos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonEntrada))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.erro")
                                                .value("Já existe um tipo de indicador com o código ISAP"));
        }

        @Test
        @DisplayName("POST /api/indicadores/tipos - Deve retornar 400 por falha de @Valid quando campos obrigatórios forem inválidos")
        void deveRetornar400QuandoPayloadForInvalido() throws Exception {
                String jsonSemCodigo = """
                                {
                                    "codigo": "",
                                    "descricao": "Descrição sem código",
                                    "temFonte": true,
                                    "ativo": true
                                }
                                """;

                mvc.perform(post("/api/indicadores/tipos")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonSemCodigo))
                                .andExpect(status().isBadRequest());

                verifyNoInteractions(service);
        }

        @Test
        @DisplayName("PUT /api/indicadores/tipos/{id} - Deve atualizar e retornar 200 OK")
        void deveAtualizarRetornando200() throws Exception {
                TipoIndicador atualizado = TipoIndicador.builder()
                                .id(4L)
                                .codigo("IDESMAT")
                                .descricao("Descrição Atualizada")
                                .temFonte(false)
                                .ativo(true)
                                .build();

                when(service.atualizar(eq(4L), any(TipoIndicadorRequest.class))).thenReturn(atualizado);

                String jsonEntrada = """
                                {
                                    "codigo": "IDESMAT",
                                    "descricao": "Descrição Atualizada",
                                    "temFonte": false,
                                    "ativo": true
                                }
                                """;

                mvc.perform(put("/api/indicadores/tipos/4")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonEntrada))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(4))
                                .andExpect(jsonPath("$.codigo").value("IDESMAT"))
                                .andExpect(jsonPath("$.descricao").value("Descrição Atualizada"))
                                .andExpect(jsonPath("$.temFonte").value(false));

                verify(service).atualizar(eq(4L), any(TipoIndicadorRequest.class));
        }

        @Test
        @DisplayName("PUT /api/indicadores/tipos/{id} - Deve retornar 400 quando ID para atualização não existir")
        void deveRetornar400AoAtualizarIdInexistente() throws Exception {
                when(service.atualizar(eq(99L), any(TipoIndicadorRequest.class)))
                                .thenThrow(new IllegalArgumentException("Tipo de indicador com ID 99 não encontrado"));

                String jsonEntrada = """
                                {
                                    "codigo": "QUALQUER",
                                    "descricao": "Atualização",
                                    "temFonte": true,
                                    "ativo": true
                                }
                                """;

                mvc.perform(put("/api/indicadores/tipos/99")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonEntrada))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.erro").value("Tipo de indicador com ID 99 não encontrado"));
        }

        @Test
        @DisplayName("DELETE /api/indicadores/tipos/{id} - Deve desativar e retornar 204 No Content")
        void deveDesativarRetornando204() throws Exception {
                mvc.perform(delete("/api/indicadores/tipos/1"))
                                .andExpect(status().isNoContent());

                verify(service).desativar(1L);
        }

        @Test
        @DisplayName("DELETE /api/indicadores/tipos/{id} - Deve retornar 400 se ID a ser desativado não existir")
        void deveRetornar400AoDesativarIdInexistente() throws Exception {
                doThrow(new IllegalArgumentException("Tipo de indicador com ID 99 não encontrado"))
                                .when(service).desativar(99L);

                mvc.perform(delete("/api/indicadores/tipos/99"))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.erro").value("Tipo de indicador com ID 99 não encontrado"));

                verify(service).desativar(99L);
        }
}