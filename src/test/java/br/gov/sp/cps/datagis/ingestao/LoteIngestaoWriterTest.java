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
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
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
    @Mock
    private EntityManager entityManager;

    private LoteIngestaoWriter writer;

    @Test
    void deveGravarLoteConcluidoComTodosOsRegistros() {
        writer = new LoteIngestaoWriter(loteIngestaoRepository, registroBrutoRepository, entityManager);
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
        writer = new LoteIngestaoWriter(loteIngestaoRepository, registroBrutoRepository, entityManager);
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

    /**
     * {@code gravarStreaming} (usado pelo Shapefile — LIZARDS-3 doc/upload-shapefile-
     * car.md §3.4): mesmo resultado do caminho baseado em {@code List}, mas
     * consumindo os registros um a um via {@link FornecedorRegistros}, sem nunca
     * existir uma lista com todos eles em memória.
     */
    @Test
    void gravarStreamingDeveConcluirComTodosOsRegistrosQuandoMudou() {
        writer = new LoteIngestaoWriter(loteIngestaoRepository, registroBrutoRepository, entityManager);
        FonteDado fonte = FonteDado.builder().id(1L).caminhoArquivoUpload("/tmp/x.zip").build();
        when(loteIngestaoRepository.findFirstByFonteDadoIdOrderByIdDesc(1L)).thenReturn(Optional.empty());
        when(loteIngestaoRepository.save(any(LoteIngestao.class))).thenAnswer(chamada -> {
            LoteIngestao lote = chamada.getArgument(0);
            if (lote.getId() == null) {
                lote.setId(10L);
            }
            return lote;
        });
        when(registroBrutoRepository.save(any(RegistroBruto.class))).thenAnswer(chamada -> chamada.getArgument(0));

        FornecedorRegistros fornecedor = consumidor -> {
            consumidor.accept(new RegistroPreparado("{\"a\":1}", "POINT(1 2)"));
            consumidor.accept(new RegistroPreparado("{\"a\":2}", null));
        };

        LoteIngestao resultado = writer.gravarStreaming(fonte, fornecedor);

        assertThat(resultado.getStatus()).isEqualTo("CONCLUIDO");
        assertThat(resultado.getQuantidadeRegistros()).isEqualTo(2);
        assertThat(resultado.getHashConteudo()).hasSize(64);
        verify(registroBrutoRepository, times(2)).save(any(RegistroBruto.class));
        verify(registroBrutoRepository, times(0)).deleteByLoteIngestaoId(any());
    }

    @Test
    void gravarStreamingDeveApagarRegistrosGravadosQuandoDescobreSemMudancaSoNoFim() {
        writer = new LoteIngestaoWriter(loteIngestaoRepository, registroBrutoRepository, entityManager);
        FonteDado fonte = FonteDado.builder().id(1L).caminhoArquivoUpload("/tmp/x.zip").build();

        List<LoteIngestao> salvos = new ArrayList<>();
        when(loteIngestaoRepository.save(any(LoteIngestao.class))).thenAnswer(chamada -> {
            LoteIngestao lote = chamada.getArgument(0);
            if (lote.getId() == null) {
                lote.setId(20L);
            }
            salvos.add(lote);
            return lote;
        });
        // Primeira chamada só pra descobrir o hash de um fornecedor com o mesmo
        // conteúdo e plantar num "lote anterior" com esse hash já gravado.
        FornecedorRegistros fornecedor = consumidor -> consumidor.accept(new RegistroPreparado("{\"a\":1}", null));
        when(loteIngestaoRepository.findFirstByFonteDadoIdOrderByIdDesc(1L)).thenReturn(Optional.empty());
        writer.gravarStreaming(fonte, fornecedor);
        String hashJaConhecido = salvos.get(salvos.size() - 1).getHashConteudo();

        LoteIngestao loteAnterior = LoteIngestao.builder().id(20L).hashConteudo(hashJaConhecido).build();
        when(loteIngestaoRepository.findFirstByFonteDadoIdOrderByIdDesc(1L)).thenReturn(Optional.of(loteAnterior));

        LoteIngestao resultado = writer.gravarStreaming(fonte, fornecedor);

        assertThat(resultado.getStatus()).isEqualTo("SEM_MUDANCA");
        assertThat(resultado.getQuantidadeRegistros()).isEqualTo(0);
        // Grava (não sabia ainda que era igual) e só depois apaga em bloco.
        verify(registroBrutoRepository, times(2)).save(any(RegistroBruto.class));
        verify(registroBrutoRepository).deleteByLoteIngestaoId(20L);
    }
}
