package br.gov.sp.cps.datagis.gold;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.gov.sp.cps.datagis.config.TratadorDeErro;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class GeoServerProxyControllerTest {

    @Mock
    private GeoServerProxyService service;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new GeoServerProxyController(service))
                .setControllerAdvice(new TratadorDeErro())
                .build();
    }

    @Test
    void repassaServicoEParametrosEDevolveORespostaDoGeoServer() throws Exception {
        byte[] png = {1, 2, 3};
        when(service.encaminhar(eq("wms"), any()))
                .thenReturn(ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png));

        mvc.perform(get("/api/indicadores/geoserver/wms")
                        .param("REQUEST", "GetMap")
                        .param("LAYERS", "datagis:vw_mapa_imovel"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(png));

        verify(service).encaminhar(eq("wms"), argThat(p ->
                "GetMap".equals(p.getFirst("REQUEST")) && "datagis:vw_mapa_imovel".equals(p.getFirst("LAYERS"))));
    }

    @Test
    void pedidoRecusadoVira400ComAMensagem() throws Exception {
        when(service.encaminhar(eq("wms"), any()))
                .thenThrow(new IllegalArgumentException("Camada nao permitida: datagis:outra"));

        mvc.perform(get("/api/indicadores/geoserver/wms").param("LAYERS", "datagis:outra"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erro").value("Camada nao permitida: datagis:outra"));
    }

    @Test
    void geoServerForaDoArVira502() throws Exception {
        when(service.encaminhar(eq("wfs"), any()))
                .thenThrow(new IllegalStateException("GeoServer indisponivel: Connection refused"));

        mvc.perform(get("/api/indicadores/geoserver/wfs").param("REQUEST", "GetFeature"))
                .andExpect(status().isBadGateway());
    }
}
