package br.gov.sp.cps.datagis.ingestao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.armazenamento.ArmazenamentoArquivoBruto;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Achado ao vivo: IngestaoGeoJsonExecutor estourou memória (OutOfMemoryError)
 * processando uma fonte grande demais, e isso só aparecia no log do container - a
 * tela nunca soube que a execução tinha falhado nem por quê. Estes testes provam que
 * {@link IngestaoExecucaoService#executar} sempre registra tempo gasto e resultado na
 * própria {@link FonteDado}, tanto no sucesso quanto no erro (incluindo Error, não só
 * Exception).
 */
@ExtendWith(MockitoExtension.class)
class IngestaoExecucaoServiceTest {

    @Mock
    private FonteDadoRepository fonteDadoRepository;
    @Mock
    private ArmazenamentoArquivoBruto armazenamento;
    @Mock
    private IngestaoExecutor executor;
    @Mock
    private br.gov.sp.cps.datagis.controle.ValidacaoMapeamentoFonteService validacaoMapeamentoService;

    @Test
    void deveRegistrarSucessoComDuracaoESemMensagemDeErro() {
        FonteDado fonte = FonteDado.builder().id(1L).nome("Fonte X").build();
        when(fonteDadoRepository.findById(1L)).thenReturn(Optional.of(fonte));
        when(executor.suportar(fonte)).thenReturn(true);
        LoteIngestao loteEsperado = LoteIngestao.builder().id(9L).status("CONCLUIDO").build();
        when(executor.executar(fonte)).thenReturn(loteEsperado);
        when(fonteDadoRepository.save(any(FonteDado.class))).thenAnswer(chamada -> chamada.getArgument(0));

        IngestaoExecucaoService service =
                new IngestaoExecucaoService(fonteDadoRepository, List.of(executor), armazenamento, new ProgressoExecucaoRegistry(), validacaoMapeamentoService);

        LoteIngestao resultado = service.executar(1L);

        assertThat(resultado).isSameAs(loteEsperado);
        assertThat(fonte.getUltimaExecucaoEm()).isNotNull();
        assertThat(fonte.getUltimaDuracaoMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(fonte.getUltimoStatusExecucao()).isEqualTo("SUCESSO");
        assertThat(fonte.getUltimoErroMensagem()).isNull();
    }

    @Test
    void deveRegistrarErroComMensagemQuandoExecutorLancaExcecao() {
        FonteDado fonte = FonteDado.builder().id(2L).nome("Fonte Y").build();
        when(fonteDadoRepository.findById(2L)).thenReturn(Optional.of(fonte));
        when(executor.suportar(fonte)).thenReturn(true);
        when(executor.executar(fonte)).thenThrow(new IllegalStateException("servidor de origem respondeu 500"));
        when(fonteDadoRepository.save(any(FonteDado.class))).thenAnswer(chamada -> chamada.getArgument(0));

        IngestaoExecucaoService service =
                new IngestaoExecucaoService(fonteDadoRepository, List.of(executor), armazenamento, new ProgressoExecucaoRegistry(), validacaoMapeamentoService);

        assertThatThrownBy(() -> service.executar(2L)).isInstanceOf(IllegalStateException.class);

        assertThat(fonte.getUltimaDuracaoMs()).isNotNull();
        assertThat(fonte.getUltimoStatusExecucao()).isEqualTo("ERRO");
        assertThat(fonte.getUltimoErroMensagem()).isEqualTo("IllegalStateException: servidor de origem respondeu 500");
    }

    /**
     * Reproduz o caso real: OutOfMemoryError é um Error, não uma Exception - sem
     * capturar Throwable explicitamente, esse tipo de falha passava batido e nunca
     * era registrado em lugar nenhum além do log bruto do container.
     */
    @Test
    void deveRegistrarErroMesmoQuandoForOutOfMemoryError() {
        FonteDado fonte = FonteDado.builder().id(3L).nome("Fonte Grande").build();
        when(fonteDadoRepository.findById(3L)).thenReturn(Optional.of(fonte));
        when(executor.suportar(fonte)).thenReturn(true);
        when(executor.executar(fonte)).thenThrow(new OutOfMemoryError("Java heap space"));
        when(fonteDadoRepository.save(any(FonteDado.class))).thenAnswer(chamada -> chamada.getArgument(0));

        IngestaoExecucaoService service =
                new IngestaoExecucaoService(fonteDadoRepository, List.of(executor), armazenamento, new ProgressoExecucaoRegistry(), validacaoMapeamentoService);

        assertThatThrownBy(() -> service.executar(3L)).isInstanceOf(OutOfMemoryError.class);

        assertThat(fonte.getUltimoStatusExecucao()).isEqualTo("ERRO");
        assertThat(fonte.getUltimoErroMensagem()).isEqualTo("OutOfMemoryError: Java heap space");
    }

    @Test
    void deveFalharComMensagemClaraQuandoNenhumExecutorSuportarAFonte() {
        FonteDado fonte = FonteDado.builder().id(4L).nome("Fonte Sem Executor").build();
        when(fonteDadoRepository.findById(4L)).thenReturn(Optional.of(fonte));
        when(executor.suportar(fonte)).thenReturn(false);

        IngestaoExecucaoService service =
                new IngestaoExecucaoService(fonteDadoRepository, List.of(executor), armazenamento, new ProgressoExecucaoRegistry(), validacaoMapeamentoService);

        assertThatThrownBy(() -> service.executar(4L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Fonte Sem Executor");
    }

    @Test
    void deveFalharImediatamenteSemExecutarQuandoMapeamentoEstiverIncompleto() {
        FonteDado fonte = FonteDado.builder().id(5L).nome("Fonte Incompleta").build();
        when(fonteDadoRepository.findById(5L)).thenReturn(Optional.of(fonte));
        org.mockito.Mockito.doThrow(new IllegalArgumentException("Mapeamento incompleto: faltam 2 colunas"))
                .when(validacaoMapeamentoService).validarParaIngestao(fonte);

        IngestaoExecucaoService service =
                new IngestaoExecucaoService(fonteDadoRepository, List.of(executor), armazenamento, new ProgressoExecucaoRegistry(), validacaoMapeamentoService);

        assertThatThrownBy(() -> service.executar(5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Mapeamento incompleto");

        org.mockito.Mockito.verifyNoInteractions(executor);
    }
}
