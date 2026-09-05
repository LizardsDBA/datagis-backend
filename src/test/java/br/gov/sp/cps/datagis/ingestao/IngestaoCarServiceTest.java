package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
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

/**
 * Testa a orquestração da fatia 1 (CLAUDE.md §5.2) contra um GeoJSON de amostra —
 * não bate no WFS real (só o {@link CarWfsClient} é mockado).
 */
@ExtendWith(MockitoExtension.class)
class IngestaoCarServiceTest {

    @Mock
    private CarWfsClient wfsClient;
    @Mock
    private FonteDadoRepository fonteDadoRepository;
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
                wfsClient, new GeoJsonWktMapper(), fonteDadoRepository,
                loteIngestaoRepository, registroBrutoRepository, new ObjectMapper());
    }

    @Test
    void deveGravarUmRegistroBrutoPorFeatureDaAmostra() {
        FonteDado fonte = FonteDado.builder()
                .id(1L)
                .nome("CAR - Imóveis (PR)")
                .urlOrigem("https://geoserver.car.gov.br/geoserver/sicar/wfs")
                .build();
        when(fonteDadoRepository.findByNome("CAR - Imóveis (PR)")).thenReturn(Optional.of(fonte));
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

        LoteIngestao resultado = service.ingerir();

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
}
