package br.gov.sp.cps.datagis.conjuntosdados;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.conjuntosdados.ComparacaoLotesDto.DiferencaRegistro;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import br.gov.sp.cps.datagis.validacao.DeduplicadorRegistros;
import br.gov.sp.cps.datagis.validacao.TransicaoBronzeSilverService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import tools.jackson.databind.ObjectMapper;

/**
 * Comparação entre lotes (LIZARDS-30): os registros são pareados pela chave natural da
 * fonte, nunca pela posição no lote — um registro inserido no meio não pode deslocar os
 * seguintes e transformá-los em "modificados".
 */
class ComparacaoLotesServiceTest {

    private static final String QUADRADO = "POLYGON ((0 0, 1 0, 1 1, 0 1, 0 0))";
    private static final String QUADRADO_MAIOR = "POLYGON ((0 0, 2 0, 2 2, 0 2, 0 0))";

    private final LoteIngestaoRepository loteRepo = mock(LoteIngestaoRepository.class);
    private final FonteDadoRepository fonteRepo = mock(FonteDadoRepository.class);
    private final RegistroBrutoRepository registroRepo = mock(RegistroBrutoRepository.class);
    private final TransicaoBronzeSilverService transicao = mock(TransicaoBronzeSilverService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final FonteDado fonte = FonteDado.builder().id(3L).nome("CAR - Imóveis (PR)").build();
    private final LoteIngestao loteAnterior = LoteIngestao.builder().id(3L).dataExecucao(LocalDateTime.of(2026, 10, 17, 14, 0)).build();
    private final LoteIngestao loteAtual = LoteIngestao.builder().id(8L).dataExecucao(LocalDateTime.of(2026, 10, 21, 23, 0)).build();
    private final AtomicLong ids = new AtomicLong(100);

    private ComparacaoLotesService service;

    @BeforeEach
    void preparar() {
        service = new ComparacaoLotesService(loteRepo, fonteRepo, registroRepo, transicao,
                new DeduplicadorRegistros(objectMapper), objectMapper);
        when(fonteRepo.findById(3L)).thenReturn(Optional.of(fonte));
        when(loteRepo.findUltimosLotesPromovidos(eq(3L), anyList(), any(Pageable.class)))
                .thenReturn(List.of(loteAtual, loteAnterior));
        when(transicao.resolverEntidadeSilver(fonte)).thenReturn("imovel_rural");
        when(transicao.resolverChaveNatural("imovel_rural", fonte)).thenReturn("cod_imovel");
    }

    private RegistroBruto imovel(String cod, double area, String wkt) {
        return RegistroBruto.builder()
                .id(ids.incrementAndGet())
                .payload("{\"cod_imovel\":\"" + cod + "\",\"area\":" + area + "}")
                .geometriaWkt(wkt)
                .build();
    }

    private void lotes(List<RegistroBruto> atual, List<RegistroBruto> anterior) {
        when(registroRepo.findByLoteIngestaoIdOrderByIdAsc(8L)).thenReturn(atual);
        when(registroRepo.findByLoteIngestaoIdOrderByIdAsc(3L)).thenReturn(anterior);
    }

    private ComparacaoLotesDto comparar(String filtro) {
        return service.compararUltimosLotes(3L, 0, 100, filtro);
    }

    @Test
    void registroInseridoNoMeioNaoDeslocaOsSeguintes() {
        List<RegistroBruto> anterior = new ArrayList<>();
        List<RegistroBruto> atual = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            anterior.add(imovel("PR-" + i, i, QUADRADO));
        }
        for (int i = 1; i <= 3; i++) {
            atual.add(imovel("PR-" + i, i, QUADRADO));
        }
        atual.add(imovel("PR-NOVO", 9, QUADRADO));
        for (int i = 4; i <= 6; i++) {
            atual.add(imovel("PR-" + i, i, QUADRADO));
        }
        lotes(atual, anterior);

        ComparacaoLotesDto r = comparar(null);

        assertThat(r.resumo().adicionados()).isEqualTo(1);
        assertThat(r.resumo().modificados()).isZero();
        assertThat(r.resumo().removidos()).isZero();
        assertThat(r.resumo().inalterados()).isEqualTo(6);
        DiferencaRegistro novo = comparar(ComparacaoLotesDto.ADICIONADO).registros().get(0);
        assertThat(novo.dadosAtual()).containsEntry("cod_imovel", "PR-NOVO");
        assertThat(novo.chave()).isEqualTo("PR-NOVO");
    }

    @Test
    void cadaItemTrazAChaveNaturalEOCampoUsado() {
        lotes(List.of(imovel("PR-1", 1, QUADRADO)), List.of(imovel("PR-1", 2, QUADRADO)));

        ComparacaoLotesDto r = comparar(null);

        assertThat(r.campoChave()).isEqualTo("cod_imovel");
        assertThat(r.registros()).singleElement().satisfies(d -> {
            assertThat(d.chave()).isEqualTo("PR-1");
            assertThat(d.situacao()).isEqualTo(ComparacaoLotesDto.MODIFICADO);
        });
    }

    @Test
    void semFiltroOQueMudouVemPrimeiroEOsInalteradosPorUltimo() {
        lotes(List.of(imovel("PR-1", 1, QUADRADO), imovel("PR-2", 2, QUADRADO), imovel("PR-3", 9, QUADRADO),
                        imovel("PR-NOVO", 4, QUADRADO)),
                List.of(imovel("PR-1", 1, QUADRADO), imovel("PR-2", 2, QUADRADO), imovel("PR-3", 3, QUADRADO),
                        imovel("PR-VELHO", 5, QUADRADO)));

        assertThat(comparar(null).registros())
                .extracting(DiferencaRegistro::chave, DiferencaRegistro::situacao)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PR-NOVO", ComparacaoLotesDto.ADICIONADO),
                        org.assertj.core.groups.Tuple.tuple("PR-VELHO", ComparacaoLotesDto.REMOVIDO),
                        org.assertj.core.groups.Tuple.tuple("PR-3", ComparacaoLotesDto.MODIFICADO),
                        org.assertj.core.groups.Tuple.tuple("PR-1", ComparacaoLotesDto.INALTERADO),
                        org.assertj.core.groups.Tuple.tuple("PR-2", ComparacaoLotesDto.INALTERADO));
    }

    @Test
    void registroSemChaveVemComChaveNula() {
        RegistroBruto semChave = RegistroBruto.builder().id(1L).payload("{\"x\":1}").build();
        lotes(List.of(semChave), List.of());

        assertThat(comparar(null).registros()).singleElement().satisfies(d -> assertThat(d.chave()).isNull());
    }

    @Test
    void campoAlteradoNoMesmoImovelViraModificadoComOCampo() {
        lotes(List.of(imovel("PR-1", 10.5, QUADRADO), imovel("PR-2", 20, QUADRADO)),
                List.of(imovel("PR-1", 10.5, QUADRADO), imovel("PR-2", 25, QUADRADO)));

        ComparacaoLotesDto r = comparar(ComparacaoLotesDto.MODIFICADO);

        assertThat(r.resumo().modificados()).isEqualTo(1);
        assertThat(r.resumo().inalterados()).isEqualTo(1);
        DiferencaRegistro mod = r.registros().get(0);
        assertThat(mod.dadosAtual()).containsEntry("cod_imovel", "PR-2");
        assertThat(mod.dadosAnterior()).containsEntry("cod_imovel", "PR-2");
        assertThat(mod.camposAlterados()).singleElement().satisfies(c -> {
            assertThat(c.campo()).isEqualTo("area");
            assertThat(c.valorAnterior()).isEqualTo(25.0);
            assertThat(c.valorAtual()).isEqualTo(20.0);
        });
    }

    @Test
    void geometriaAlteradaViraModificadoNoCampoGeometria() {
        lotes(List.of(imovel("PR-1", 1, QUADRADO_MAIOR)), List.of(imovel("PR-1", 1, QUADRADO)));

        DiferencaRegistro mod = comparar(null).registros().get(0);

        assertThat(mod.situacao()).isEqualTo(ComparacaoLotesDto.MODIFICADO);
        assertThat(mod.camposAlterados()).extracting(c -> c.campo()).containsExactly("geometria_wkt");
    }

    @Test
    void mesmaGeometriaEscritaDiferenteContinuaInalterada() {
        // mesmo quadrado, começando por outro vértice
        lotes(List.of(imovel("PR-1", 1, "POLYGON ((1 0, 1 1, 0 1, 0 0, 1 0))")), List.of(imovel("PR-1", 1, QUADRADO)));

        assertThat(comparar(null).resumo().inalterados()).isEqualTo(1);
    }

    @Test
    void imovelQueSumiuViraRemovido() {
        lotes(List.of(imovel("PR-1", 1, QUADRADO)), List.of(imovel("PR-1", 1, QUADRADO), imovel("PR-2", 2, QUADRADO)));

        ComparacaoLotesDto r = comparar(ComparacaoLotesDto.REMOVIDO);

        assertThat(r.resumo().removidos()).isEqualTo(1);
        assertThat(r.registros()).singleElement()
                .satisfies(d -> assertThat(d.dadosAnterior()).containsEntry("cod_imovel", "PR-2"));
    }

    @Test
    void registroSemChaveEPareadoPeloConteudoNuncaPelaPosicao() {
        RegistroBruto igualAntes = RegistroBruto.builder().id(1L).payload("{\"x\":1}").geometriaWkt(QUADRADO).build();
        RegistroBruto igualAgora = RegistroBruto.builder().id(2L).payload("{\"x\":1}").geometriaWkt(QUADRADO).build();
        RegistroBruto mudou = RegistroBruto.builder().id(3L).payload("{\"x\":2}").geometriaWkt(QUADRADO).build();
        RegistroBruto sumiu = RegistroBruto.builder().id(4L).payload("{\"x\":3}").geometriaWkt(QUADRADO).build();
        // ordem invertida no lote atual: pela posição daria tudo "modificado"
        lotes(List.of(mudou, igualAgora), List.of(igualAntes, sumiu));

        ComparacaoLotesDto r = comparar(null);

        assertThat(r.resumo().inalterados()).isEqualTo(1);
        assertThat(r.resumo().adicionados()).isEqualTo(1);
        assertThat(r.resumo().removidos()).isEqualTo(1);
        assertThat(r.resumo().modificados()).isZero();
    }

    @Test
    void filtroInvalidoERecusado() {
        assertThatThrownBy(() -> comparar("XYZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADICIONADO, REMOVIDO, MODIFICADO ou INALTERADO");
    }

    @Test
    void filtroAceitaMinusculas() {
        lotes(List.of(imovel("PR-1", 1, QUADRADO)), List.of());

        assertThat(comparar("adicionado").registros()).hasSize(1);
    }

    @Test
    void paginaNaoMudaOResumo() {
        List<RegistroBruto> atual = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            atual.add(imovel("PR-" + i, i, QUADRADO));
        }
        lotes(atual, List.of());

        ComparacaoLotesDto pagina2 = service.compararUltimosLotes(3L, 1, 2, null);

        assertThat(pagina2.resumo().adicionados()).isEqualTo(5);
        assertThat(pagina2.totalRegistrosFiltrados()).isEqualTo(5);
        assertThat(pagina2.registros()).extracting(d -> d.dadosAtual().get("cod_imovel"))
                .containsExactly("PR-3", "PR-4");
    }

    @Test
    void fonteComUmLoteSoAvisaQueNaoHaAnterior() {
        when(loteRepo.findUltimosLotesPromovidos(eq(3L), anyList(), any(Pageable.class))).thenReturn(List.of(loteAtual));

        ComparacaoLotesDto r = comparar(null);

        assertThat(r.loteAnteriorEncontrado()).isFalse();
        assertThat(r.mensagem()).contains("primeiro lote");
        assertThat(r.campoChave()).isNull();
    }

    @Test
    void fonteSemLotePromovidoERecusada() {
        when(loteRepo.findUltimosLotesPromovidos(eq(3L), anyList(), any(Pageable.class))).thenReturn(List.of());

        assertThatThrownBy(() -> comparar(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("não tem nenhum lote promovido");
    }

    @Test
    void fonteInexistenteERecusada() {
        assertThatThrownBy(() -> service.compararUltimosLotes(999L, 0, 10, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("999");
    }
}
