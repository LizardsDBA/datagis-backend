package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/**
 * Testa com um GeoJSON de amostra — não sabe (nem precisa saber) que o arquivo veio
 * originalmente do CAR; qualquer fonte com formatoEsperado=GEOJSON passa por aqui.
 */
@ExtendWith(MockitoExtension.class)
class IngestaoGeoJsonExecutorTest {

    @Mock
    private HttpFetchClient httpFetchClient;
    @Mock
    private LoteIngestaoWriter writer;

    private IngestaoGeoJsonExecutor executor;
    private String corpoAmostra;

    @BeforeEach
    void configurar() throws IOException {
        corpoAmostra = Files.readString(
                new ClassPathResource("fixtures/car-pr-amostra.json").getFile().toPath(),
                StandardCharsets.UTF_8);
        executor = new IngestaoGeoJsonExecutor(httpFetchClient, new GeoJsonWktMapper(), writer, new ObjectMapper());
    }

    @Test
    void suportarSoDependeDoFormatoEsperado() {
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("GEOJSON").build())).isTrue();
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("geojson").build())).isTrue();
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado("CSV").build())).isFalse();
        assertThat(executor.suportar(FonteDado.builder().formatoEsperado(null).build())).isFalse();
    }

    @Test
    void deveBuscarNaUrlDaFonteEPrepararUmRegistroPorFeature() {
        FonteDado fonte = FonteDado.builder().id(1L).urlOrigem("https://qualquer-fonte.gov.br/wfs").build();
        when(httpFetchClient.buscarCorpoBruto("https://qualquer-fonte.gov.br/wfs")).thenReturn(corpoAmostra);
        LoteIngestao loteEsperado = LoteIngestao.builder().id(10L).status("CONCLUIDO").build();
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(loteEsperado);

        LoteIngestao resultado = executor.executar(fonte);

        assertThat(resultado).isSameAs(loteEsperado);
        verify(httpFetchClient).buscarCorpoBruto("https://qualquer-fonte.gov.br/wfs");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).gravar(eq(fonte), anyString(), captor.capture());

        List<RegistroPreparado> registros = captor.getValue();
        assertThat(registros).hasSize(2);
        assertThat(registros.get(0).payloadJson()).contains("PR-4106902-00B2EA81503E46B8BD95A7E35BBB8472");
        assertThat(registros.get(0).geometriaWkt()).startsWith("MULTIPOLYGON");
        assertThat(registros.get(1).payloadJson()).contains("Maringá");
    }
}
