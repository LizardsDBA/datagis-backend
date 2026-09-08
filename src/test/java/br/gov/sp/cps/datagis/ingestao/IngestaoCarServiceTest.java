package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.ObjectMapper;

/** Testa o service com um GeoJSON de amostra. Não bate no WFS real. */
@ExtendWith(MockitoExtension.class)
class IngestaoCarServiceTest {

    @Mock
    private CarWfsClient wfsClient;
    @Mock
    private LoteIngestaoRepository loteIngestaoRepository;
    @Mock
    private RegistroBrutoRepository registroBrutoRepository;

    private IngestaoCarService service;
    private String corpoAmostra;

    @BeforeEach
    void configurar() throws IOException {
        corpoAmostra = Files.readString(
                new ClassPathResource("fixtures/car-pr-amostra.json").getFile().toPath(),
                StandardCharsets.UTF_8);

        service = new IngestaoCarService(
                wfsClient, new GeoJsonWktMapper(),
                loteIngestaoRepository, registroBrutoRepository, new ObjectMapper());
    }

    @Test
    void deveReconhecerFontePelaUrlDoWfsDoCar() {
        FonteDado car = FonteDado.builder()
                .urlOrigem("https://geoserver.car.gov.br/geoserver/sicar/wfs?service=WFS...")
                .build();
        FonteDado outra = FonteDado.builder().urlOrigem("https://outra-fonte.gov.br/api").build();
        FonteDado semUrl = FonteDado.builder().build();

        assertThat(service.suportar(car)).isTrue();
        assertThat(service.suportar(outra)).isFalse();
        assertThat(service.suportar(semUrl)).isFalse();
    }

    @Test
    void deveGravarUmRegistroBrutoPorFeatureDaAmostra() {
        FonteDado fonte = FonteDado.builder()
                .id(1L)
                .nome("CAR - Imóveis (PR)")
                .urlOrigem("https://geoserver.car.gov.br/geoserver/sicar/wfs")
                .build();
        when(wfsClient.buscarCorpoBruto()).thenReturn(corpoAmostra);
        when(loteIngestaoRepository.save(any(LoteIngestao.class))).thenAnswer(chamada -> {
            LoteIngestao lote = chamada.getArgument(0);
            if (lote.getId() == null) {
                lote.setId(10L);
            }
            return lote;
        });
        when(registroBrutoRepository.save(any(RegistroBruto.class)))
                .thenAnswer(chamada -> chamada.getArgument(0));

        LoteIngestao resultado = service.executar(fonte);

        assertThat(resultado.getId()).isEqualTo(10L);
        assertThat(resultado.getStatus()).isEqualTo("CONCLUIDO");
        assertThat(resultado.getQuantidadeRegistros()).isEqualTo(2);
        assertThat(resultado.getHashConteudo()).hasSize(64); // SHA-256 em hex

        ArgumentCaptor<RegistroBruto> captor = ArgumentCaptor.forClass(RegistroBruto.class);
        verify(registroBrutoRepository, times(2)).save(captor.capture());

        RegistroBruto primeiro = captor.getAllValues().get(0);
        assertThat(primeiro.getLoteIngestaoId()).isEqualTo(10L);
        assertThat(primeiro.getFonteDadoId()).isEqualTo(1L);
        assertThat(primeiro.getNumeroLinha()).isEqualTo(0);
        assertThat(primeiro.getPayload()).contains("PR-4106902-00B2EA81503E46B8BD95A7E35BBB8472");
        assertThat(primeiro.getGeometriaWkt()).startsWith("MULTIPOLYGON");

        RegistroBruto segundo = captor.getAllValues().get(1);
        assertThat(segundo.getNumeroLinha()).isEqualTo(1);
        assertThat(segundo.getPayload()).contains("Maringá");
    }

    @Test
    void naoDeveGravarRegistrosQuandoHashJaFoiIngeridoNoUltimoLote() {
        FonteDado fonte = FonteDado.builder()
                .id(1L)
                .nome("CAR - Imóveis (PR)")
                .urlOrigem("https://geoserver.car.gov.br/geoserver/sicar/wfs")
                .build();
        // O service so hasheia as "features", entao o teste precisa calcular o
        // hash do mesmo jeito.
        ObjectMapper mapper = new ObjectMapper();
        String featuresJson = mapper.writeValueAsString(mapper.readTree(corpoAmostra).path("features"));
        String hashDoCorpo = sha256HexParaTeste(featuresJson);
        LoteIngestao loteAnterior = LoteIngestao.builder()
                .id(5L)
                .hashConteudo(hashDoCorpo)
                .status("CONCLUIDO")
                .build();

        when(wfsClient.buscarCorpoBruto()).thenReturn(corpoAmostra);
        when(loteIngestaoRepository.findFirstByFonteDadoIdOrderByIdDesc(1L))
                .thenReturn(Optional.of(loteAnterior));
        when(loteIngestaoRepository.save(any(LoteIngestao.class))).thenAnswer(chamada -> {
            LoteIngestao lote = chamada.getArgument(0);
            lote.setId(11L);
            return lote;
        });

        LoteIngestao resultado = service.executar(fonte);

        assertThat(resultado.getStatus()).isEqualTo("SEM_MUDANCA");
        assertThat(resultado.getQuantidadeRegistros()).isEqualTo(0);
        verify(registroBrutoRepository, times(0)).save(any(RegistroBruto.class));
    }

    private static String sha256HexParaTeste(String texto) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(texto.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
