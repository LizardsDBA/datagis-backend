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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Testa o ciclo de vida comum a qualquer formato — hash, lote, registros. */
@ExtendWith(MockitoExtension.class)
class LoteIngestaoWriterTest {

    @Mock
    private LoteIngestaoRepository loteIngestaoRepository;
    @Mock
    private RegistroBrutoRepository registroBrutoRepository;

    private LoteIngestaoWriter writer;

    @Test
    void deveGravarLoteConcluidoComTodosOsRegistros() {
        writer = new LoteIngestaoWriter(loteIngestaoRepository, registroBrutoRepository);
        FonteDado fonte = FonteDado.builder().id(1L).urlOrigem("https://x").build();
        when(loteIngestaoRepository.findFirstByFonteDadoIdOrderByIdDesc(1L)).thenReturn(Optional.empty());
        when(loteIngestaoRepository.save(any(LoteIngestao.class))).thenAnswer(chamada -> {
            LoteIngestao lote = chamada.getArgument(0);
            if (lote.getId() == null) {
                lote.setId(10L);
            }
            return lote;
        });
        when(registroBrutoRepository.save(any(RegistroBruto.class))).thenAnswer(chamada -> chamada.getArgument(0));

        List<RegistroPreparado> registros = List.of(
                new RegistroPreparado("{\"a\":1}", "POINT(1 2)"),
                new RegistroPreparado("{\"a\":2}", null));

        LoteIngestao resultado = writer.gravar(fonte, "conteudo-de-teste", registros);

        assertThat(resultado.getStatus()).isEqualTo("CONCLUIDO");
        assertThat(resultado.getQuantidadeRegistros()).isEqualTo(2);
        assertThat(resultado.getHashConteudo()).hasSize(64);
        verify(registroBrutoRepository, times(2)).save(any(RegistroBruto.class));
    }

    @Test
    void naoDeveGravarRegistrosQuandoHashJaFoiIngeridoNoUltimoLote() {
        writer = new LoteIngestaoWriter(loteIngestaoRepository, registroBrutoRepository);
        FonteDado fonte = FonteDado.builder().id(1L).urlOrigem("https://x").build();

        // Mesmo hash que "conteudo-de-teste" vai gerar - o teste so precisa que bata.
        LoteIngestao loteAnterior = LoteIngestao.builder().id(5L).status("CONCLUIDO").build();
        when(loteIngestaoRepository.save(any(LoteIngestao.class))).thenAnswer(chamada -> {
            LoteIngestao lote = chamada.getArgument(0);
            lote.setId(11L);
            loteAnterior.setHashConteudo(lote.getHashConteudo());
            return lote;
        });
        when(loteIngestaoRepository.findFirstByFonteDadoIdOrderByIdDesc(1L))
                .thenReturn(Optional.of(loteAnterior));

        // Primeira chamada so pra descobrir o hash de "conteudo-de-teste" e plantar no
        // lote anterior (simulando que ja rodou uma vez com o mesmo conteudo).
        writer.gravar(fonte, "conteudo-de-teste", List.of());

        LoteIngestao resultado = writer.gravar(fonte, "conteudo-de-teste", List.of(new RegistroPreparado("{}", null)));

        assertThat(resultado.getStatus()).isEqualTo("SEM_MUDANCA");
        assertThat(resultado.getQuantidadeRegistros()).isEqualTo(0);
    }
}
