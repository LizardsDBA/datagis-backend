package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IngestaoCsvExecutorTest {

    private static final String CSV_AMOSTRA = String.join("\n",
            "id,lat,lon,municipio",
            "1,-25.42,-49.27,Curitiba",
            "2,-23.5,-46.6,\"São Paulo, Capital\"",
            "3,abc,-46.6,Sem coordenada valida");

    @Mock
    private HttpFetchClient httpFetchClient;
    @Mock
    private LoteIngestaoWriter writer;

    private IngestaoCsvExecutor executor;

    @BeforeEach
    void configurar() {
        executor = new IngestaoCsvExecutor(httpFetchClient, writer, new ObjectMapper());
    }

    @Test
    void suportarSoDependeDoFormatoEsperado() {
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("CSV").build())).isTrue();
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("csv").build())).isTrue();
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("GEOJSON").build())).isFalse();
    }

    @Test
    void deveMontarPontoWktQuandoColunasDeCoordenadaEstaoConfiguradas() {
        FonteDado fonte = FonteDado.builder()
                .id(2L)
                .urlOrigem("https://exemplo.gov.br/dados.csv")
                .campoGeometriaLat("lat")
                .campoGeometriaLon("lon")
                .build();
        when(httpFetchClient.buscarCorpoBruto(fonte.getUrlOrigem())).thenReturn(CSV_AMOSTRA);
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(LoteIngestao.builder().id(1L).build());

        executor.executar(fonte);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        List<RegistroPreparado> registros = captor.getValue();

        assertThat(registros).hasSize(3);

        // Linha 1: coordenada valida.
        assertThat(registros.get(0).geometriaWkt()).contains("POINT").contains("-49.27").contains("-25.42");
        assertThat(registros.get(0).payloadJson()).contains("\"municipio\":\"Curitiba\"");

        // Linha 2: campo entre aspas com virgula dentro nao pode quebrar em dois campos.
        assertThat(registros.get(1).payloadJson()).contains("\"municipio\":\"São Paulo, Capital\"");

        // Linha 3: lat invalida - registro entra mesmo assim, so sem geometria.
        assertThat(registros.get(2).geometriaWkt()).isNull();
        assertThat(registros.get(2).payloadJson()).contains("Sem coordenada valida");
    }

    @Test
    void naoDeveMontarGeometriaQuandoFonteNaoDeclaraColunasDeCoordenada() {
        FonteDado fonte = FonteDado.builder().id(3L).urlOrigem("https://exemplo.gov.br/dados.csv").build();
        when(httpFetchClient.buscarCorpoBruto(fonte.getUrlOrigem())).thenReturn(CSV_AMOSTRA);
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(LoteIngestao.builder().id(1L).build());

        executor.executar(fonte);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(writer).gravar(eq(fonte), anyString(), captor.capture());

        assertThat(captor.getValue()).allSatisfy(registro -> assertThat(registro.geometriaWkt()).isNull());
    }
}
