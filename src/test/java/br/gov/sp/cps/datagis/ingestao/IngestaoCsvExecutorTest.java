package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.armazenamento.ArmazenamentoArquivoBruto;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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
    private ArmazenamentoArquivoBruto armazenamento;
    @Mock
    private LoteIngestaoWriter writer;

    private IngestaoCsvExecutor executor;

    @BeforeEach
    void configurar() {
        executor = new IngestaoCsvExecutor(
                httpFetchClient, armazenamento, writer, new ObjectMapper(), new ProgressoExecucaoRegistry());
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

    /**
     * Reproduz o bug: fonte UPLOAD_MANUAL não tem urlOrigem, então o executor não pode
     * cair no HttpFetchClient (que estourava NullPointerException tentando rodar curl
     * com URL nula) — precisa ler o arquivo enviado via ArmazenamentoArquivoBruto.
     */
    @Test
    void deveLerArquivoEnviadoQuandoFonteForUploadManual() {
        FonteDado fonte = FonteDado.builder()
                .id(4L)
                .nome("Teste virgula")
                .tipoAquisicao("UPLOAD_MANUAL")
                .caminhoArquivoUpload("uploads/teste-virgula.csv")
                .campoGeometriaLat("lat")
                .campoGeometriaLon("lon")
                .build();
        when(armazenamento.ler("uploads/teste-virgula.csv"))
                .thenReturn(new ByteArrayInputStream(CSV_AMOSTRA.getBytes(StandardCharsets.UTF_8)));
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(LoteIngestao.builder().id(1L).build());

        executor.executar(fonte);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        org.mockito.Mockito.verifyNoInteractions(httpFetchClient);
    }

    @Test
    void deveFalharComMensagemClaraSeUploadManualAindaNaoTiverArquivo() {
        FonteDado fonte = FonteDado.builder()
                .id(5L).nome("Fonte sem upload").tipoAquisicao("UPLOAD_MANUAL").build();

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executor.executar(fonte))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nenhum arquivo");
    }

    @Test
    void semSeparadorConfiguradoAssumeVirgulaPorCompatibilidade() {
        FonteDado fonte = FonteDado.builder().id(6L).urlOrigem("https://exemplo.gov.br/dados.csv").build();
        fonte.setSeparadorCsv(null); // fonte cadastrada antes desse campo existir
        when(httpFetchClient.buscarCorpoBruto(fonte.getUrlOrigem())).thenReturn(CSV_AMOSTRA);
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(LoteIngestao.builder().id(1L).build());

        executor.executar(fonte);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        assertThat(captor.getValue()).hasSize(3);
        assertThat(captor.getValue().get(0).payloadJson()).contains("\"municipio\":\"Curitiba\"");
    }

    @Test
    void deveDividirLinhasComSeparadorPontoEVirgula() {
        String csvPontoEVirgula = String.join("\n",
                "id;municipio;estado;risco_fogo",
                "b2c3d4e5-1001;CUIABA;MATO GROSSO;0,62",
                "b2c3d4e5-1002;CAMPO GRANDE;MATO GROSSO DO SUL;0,15");
        FonteDado fonte = FonteDado.builder()
                .id(7L).urlOrigem("https://exemplo.gov.br/dados.csv").separadorCsv(";").build();
        when(httpFetchClient.buscarCorpoBruto(fonte.getUrlOrigem())).thenReturn(csvPontoEVirgula);
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(LoteIngestao.builder().id(1L).build());

        executor.executar(fonte);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        List<RegistroPreparado> registros = captor.getValue();

        assertThat(registros).hasSize(2);
        assertThat(registros.get(0).payloadJson())
                .contains("\"municipio\":\"CUIABA\"")
                .contains("\"estado\":\"MATO GROSSO\"")
                .contains("\"risco_fogo\":\"0,62\"");
    }

    @Test
    void deveDividirLinhasComSeparadorPipe() {
        String csvPipe = String.join("\n",
                "id|lat|lon|risco_fogo",
                "c3d4e5f6-2001|-10.190500|-48.333600|0.44",
                "c3d4e5f6-2002|-19.916700|-43.934500|0.77");
        FonteDado fonte = FonteDado.builder()
                .id(8L).urlOrigem("https://exemplo.gov.br/dados.csv")
                .separadorCsv("|")
                .campoGeometriaLat("lat")
                .campoGeometriaLon("lon")
                .build();
        when(httpFetchClient.buscarCorpoBruto(fonte.getUrlOrigem())).thenReturn(csvPipe);
        when(writer.gravar(eq(fonte), anyString(), any())).thenReturn(LoteIngestao.builder().id(1L).build());

        executor.executar(fonte);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<RegistroPreparado>> captor = ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(writer).gravar(eq(fonte), anyString(), captor.capture());
        List<RegistroPreparado> registros = captor.getValue();

        assertThat(registros).hasSize(2);
        assertThat(registros.get(0).geometriaWkt()).contains("POINT").contains("-48.3336").contains("-10.1905");
        assertThat(registros.get(0).payloadJson()).contains("\"risco_fogo\":\"0.44\"");
    }

    /**
     * Reproduz um bug achado testando manualmente: corrigir o separadorCsv de uma
     * fonte e reenviar o MESMO arquivo (mesmos bytes brutos) precisa ser tratado como
     * mudança — sem isso, o hash batia com o do lote anterior (calculado só dos bytes,
     * não da interpretação) e a correção virava SEM_MUDANCA, sem efeito nenhum: o lote
     * antigo (com tudo numa coluna só, por causa do separador errado) continuava sendo
     * o vigente. O hash agora precisa mudar quando só o separador muda.
     */
    @Test
    void hashDeveMudarQuandoSoOSeparadorMuda() {
        String mesmoArquivo = "id;municipio\n1;CUIABA";
        FonteDado comVirgula = FonteDado.builder()
                .id(9L).urlOrigem("https://exemplo.gov.br/dados.csv").separadorCsv(",").build();
        FonteDado comPontoEVirgula = FonteDado.builder()
                .id(9L).urlOrigem("https://exemplo.gov.br/dados.csv").separadorCsv(";").build();
        when(httpFetchClient.buscarCorpoBruto(anyString())).thenReturn(mesmoArquivo);
        when(writer.gravar(any(), anyString(), any())).thenReturn(LoteIngestao.builder().id(1L).build());

        executor.executar(comVirgula);
        executor.executar(comPontoEVirgula);

        ArgumentCaptor<String> hashCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(writer, org.mockito.Mockito.times(2)).gravar(any(), hashCaptor.capture(), any());
        List<String> hashesUsados = hashCaptor.getAllValues();
        assertThat(hashesUsados.get(0)).isNotEqualTo(hashesUsados.get(1));
    }
}
