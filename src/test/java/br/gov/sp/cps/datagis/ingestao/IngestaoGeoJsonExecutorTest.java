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

    /**
     * Reproduz o problema real dos Embargos IBAMA (IAT/PR): o servidor ArcGIS corta em
     * ~2000 registros por página por padrão. Aqui simulamos uma página cheia (exatamente
     * TAMANHO_PAGINA) seguida de uma página menor, e conferimos que TODOS os registros
     * das duas páginas chegam no writer — não só a primeira.
     */
    @Test
    void devePaginarFonteArcgisAteReceberTodosOsRegistros() {
        String url = "https://geo.iat.pr.gov.br/server/rest/services/Embargos/FeatureServer/9/query?f=geojson";
        FonteDado fonte = FonteDado.builder().id(2L).urlOrigem(url).build();

        int tamanhoPagina = PaginadorUrlGeoJson.TAMANHO_PAGINA;
        when(httpFetchClient.buscarCorpoBruto(org.mockito.ArgumentMatchers.contains("resultOffset=0")))
                .thenReturn(featureCollectionDePontos(tamanhoPagina));
        when(httpFetchClient.buscarCorpoBruto(org.mockito.ArgumentMatchers.contains("resultOffset=" + tamanhoPagina)))
                .thenReturn(featureCollectionDePontos(113));

        LoteIngestao loteEsperado = LoteIngestao.builder().id(11L).status("CONCLUIDO").build();
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(loteEsperado);

        LoteIngestao resultado = executor.executar(fonte);

        assertThat(resultado).isSameAs(loteEsperado);
        verify(httpFetchClient).buscarCorpoBruto(org.mockito.ArgumentMatchers.contains("resultOffset=0"));
        verify(httpFetchClient).buscarCorpoBruto(org.mockito.ArgumentMatchers.contains("resultOffset=" + tamanhoPagina));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(tamanhoPagina + 113);
    }

    /**
     * Reproduz o problema real do "IAT/PR - Embargos ICMBio": falhou com HTTP 500 e,
     * minutos depois, a mesma URL exata funcionou de primeira — instabilidade
     * passageira do servidor, sem nenhuma relação com tamanho de página. A retentativa
     * no MESMO tamanho precisa resolver isso sem precisar reduzir nada.
     */
    @Test
    void deveRepetirNoMesmoTamanhoQuandoFalhaForPassageira() {
        String url = "https://geopr.iat.pr.gov.br/server/rest/services/Embargos/FeatureServer/5/query?f=geojson";
        FonteDado fonte = FonteDado.builder().id(6L).urlOrigem(url).build();

        when(httpFetchClient.buscarCorpoBruto(anyString()))
                .thenThrow(new IllegalStateException("curl falhou (exit 22): HTTP 500"))
                .thenReturn(featureCollectionDePontos(443));
        when(writer.gravar(eq(fonte), anyString(), any()))
                .thenReturn(LoteIngestao.builder().id(15L).status("CONCLUIDO").build());

        executor.executar(fonte);

        // As duas chamadas pedem o MESMO tamanho de página - não reduziu, só repetiu.
        verify(httpFetchClient, org.mockito.Mockito.times(2))
                .buscarCorpoBruto(org.mockito.ArgumentMatchers.contains(
                        "resultRecordCount=" + PaginadorUrlGeoJson.TAMANHO_PAGINA));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(443);
    }

    /**
     * Reproduz o problema real da Malha Municipal (IBGE/IAT-PR): o servidor devolve
     * HTTP 500 tentando converter demais pra GeoJSON de uma vez só, mas funciona com
     * uma página menor. O executor precisa reduzir e tentar de novo sozinho, sem
     * precisar de configuração nenhuma por fonte.
     */
    @Test
    void deveReduzirTamanhoDePaginaQuandoServidorFalharENaoDesistir() {
        String url = "https://geo.iat.pr.gov.br/server/rest/services/Municipios/FeatureServer/0/query?f=geojson";
        FonteDado fonte = FonteDado.builder().id(4L).urlOrigem(url).build();

        when(httpFetchClient.buscarCorpoBruto(org.mockito.ArgumentMatchers.contains("resultRecordCount=1000")))
                .thenThrow(new IllegalStateException("curl falhou (exit 22): HTTP 500"));
        when(httpFetchClient.buscarCorpoBruto(org.mockito.ArgumentMatchers.contains("resultRecordCount=500")))
                .thenReturn(featureCollectionDePontos(399));
        when(writer.gravar(eq(fonte), anyString(), any()))
                .thenReturn(LoteIngestao.builder().id(13L).status("CONCLUIDO").build());

        executor.executar(fonte);

        // 2x no tamanho cheio (retentativa no mesmo tamanho antes de reduzir - cobre
        // instabilidade passageira do servidor), depois reduz e acerta de primeira.
        verify(httpFetchClient, org.mockito.Mockito.times(2))
                .buscarCorpoBruto(org.mockito.ArgumentMatchers.contains("resultRecordCount=1000"));
        verify(httpFetchClient).buscarCorpoBruto(org.mockito.ArgumentMatchers.contains("resultRecordCount=500"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(399);
    }

    /**
     * Achado ao vivo nos Autos de Infração do IBAMA (IAT/PR): muita autuação não tem
     * coordenada (só atributos, {@code "geometry": null}) - o registro precisa ser
     * gravado do mesmo jeito, só sem geometria.
     */
    @Test
    void devePrepararRegistroSemGeometriaQuandoFeatureNaoTiverCoordenada() {
        FonteDado fonte = FonteDado.builder().id(5L).urlOrigem("https://exemplo.gov.br/sem-geo.geojson").build();
        String corpo = "{\"type\":\"FeatureCollection\",\"features\":["
                + "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{\"num_auto_infracao\":\"3938\"}}]}";
        when(httpFetchClient.buscarCorpoBruto(anyString())).thenReturn(corpo);
        when(writer.gravar(eq(fonte), anyString(), any()))
                .thenReturn(LoteIngestao.builder().id(14L).status("CONCLUIDO").build());

        executor.executar(fonte);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0).geometriaWkt()).isNull();
        assertThat(captor.getValue().get(0).payloadJson()).contains("3938");
    }

    /** Fonte sem família de paginação reconhecida continua com uma chamada só, como sempre foi. */
    @Test
    void naoPaginaFonteDeFamiliaDesconhecida() {
        String url = "https://exemplo.gov.br/dados/arquivo.geojson";
        FonteDado fonte = FonteDado.builder().id(3L).urlOrigem(url).build();
        when(httpFetchClient.buscarCorpoBruto(url)).thenReturn(featureCollectionDePontos(5));
        when(writer.gravar(eq(fonte), anyString(), any()))
                .thenReturn(LoteIngestao.builder().id(12L).status("CONCLUIDO").build());

        executor.executar(fonte);

        verify(httpFetchClient, org.mockito.Mockito.times(1)).buscarCorpoBruto(anyString());
    }

    private String featureCollectionDePontos(int quantidade) {
        StringBuilder features = new StringBuilder();
        for (int i = 0; i < quantidade; i++) {
            if (i > 0) {
                features.append(',');
            }
            features.append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[-49.0,-25.0]},")
                    .append("\"properties\":{\"id\":").append(i).append("}}");
        }
        return "{\"type\":\"FeatureCollection\",\"features\":[" + features + "]}";
    }
}
