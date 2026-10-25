package br.gov.sp.cps.datagis.gold;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code paralelismo} é {@code int} (não mockável) - por isso {@code service} é
 * construído na mão em cada teste, não via {@code @InjectMocks} (que zeraria o
 * parâmetro e quebraria {@code Executors.newFixedThreadPool(0)}).
 * docs/publicacao-gold-paralela.md §6.1.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoldPublicacaoServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SimplificadorGeometriaMapa simplificador;

    private GoldPublicacaoService service;

    @BeforeEach
    void init() {
        service = new GoldPublicacaoService(jdbcTemplate, simplificador, 1);
        when(jdbcTemplate.queryForObject(
                eq("SELECT MAX(id) FROM gold.publicacao_indicadores"),
                eq(Long.class))).thenReturn(1L);
    }

    @Test
    @DisplayName("publicar() deve orquestrar publicacao consolidada e atualizar status dos lotes para PROMOVIDO_GOLD")
    void publicar_DeveOrquestrarPublicacaoConsolidadaEAtualizarLotes() {
        when(jdbcTemplate.queryForList(
                eq("SELECT cod_imovel FROM silver.imovel_rural"),
                eq(String.class))).thenReturn(Collections.emptyList());

        var resultado = service.publicar();

        assertNotNull(resultado);
        assertEquals(0, resultado.totalImoveis());
        assertEquals(0, resultado.imoveisPublicados());

        verify(jdbcTemplate).update(
                eq("UPDATE controle.lote_ingestao SET status = ? WHERE status = 'PROMOVIDO_SILVER'"),
                eq(GoldPublicacaoService.STATUS_PROMOVIDO_GOLD));
    }

    @Test
    @DisplayName("publicarTudo() deve registrar falhas resilientes sem abortar execucao")
    void publicarTudo_ComFalhasResilientes_NaoDeveAbortarExecucao() {
        when(jdbcTemplate.queryForList(
                eq("SELECT cod_imovel FROM silver.imovel_rural"),
                eq(String.class))).thenReturn(List.of("PR-410010-0001"));

        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                .thenThrow(new RuntimeException("Simulando falha de conexao na procedure"));

        var resultado = service.publicarTudo();

        assertNotNull(resultado);
        assertEquals(1, resultado.totalImoveis());
        assertEquals(0, resultado.imoveisPublicados());
        assertEquals(1, resultado.imoveisComFalha().size());
        assertEquals("PR-410010-0001", resultado.imoveisComFalha().get(0));

        verify(jdbcTemplate).update(
                eq("UPDATE controle.lote_ingestao SET status = ? WHERE status = 'PROMOVIDO_SILVER'"),
                eq(GoldPublicacaoService.STATUS_PROMOVIDO_GOLD));
    }

    @Test
    @DisplayName("com paralelismo=1, chama recalcularImovel na mesma ordem de submissao (doc §6.1 item 1)")
    void publicarTudo_ComParalelismoUm_ChamaNaMesmaOrdemDeSubmissao() {
        List<String> codigos = List.of("IMV-A", "IMV-B", "IMV-C", "IMV-D", "IMV-E");
        when(jdbcTemplate.queryForList(
                eq("SELECT cod_imovel FROM silver.imovel_rural"), eq(String.class)))
                .thenReturn(codigos);

        var spyService = spy(new GoldPublicacaoService(jdbcTemplate, simplificador, 1));
        for (String cod : codigos) {
            doNothing().when(spyService).recalcularImovel(cod);
        }

        spyService.publicarTudo();

        InOrder ordem = inOrder(spyService);
        for (String cod : codigos) {
            ordem.verify(spyService).recalcularImovel(cod);
        }
    }

    @Test
    @DisplayName("com paralelismo=4 e 100 imoveis, cada um e processado exatamente uma vez (doc §6.1 item 2)")
    void publicarTudo_ComParalelismoQuatro_ProcessaCadaImovelExatamenteUmaVez() {
        List<String> codigos = IntStream.range(0, 100).mapToObj(i -> "IMV-" + i).toList();
        when(jdbcTemplate.queryForList(
                eq("SELECT cod_imovel FROM silver.imovel_rural"), eq(String.class)))
                .thenReturn(codigos);

        var spyService = spy(new GoldPublicacaoService(jdbcTemplate, simplificador, 4));
        doNothing().when(spyService).recalcularImovel(anyString());

        var resultado = spyService.publicarTudo();

        assertEquals(100, resultado.totalImoveis());
        assertEquals(100, resultado.imoveisPublicados());
        assertTrue(resultado.imoveisComFalha().isEmpty());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(spyService, times(100)).recalcularImovel(captor.capture());
        assertEquals(100, captor.getAllValues().size());
        assertEquals(100, new java.util.HashSet<>(captor.getAllValues()).size(),
                "nenhum imovel pode ser processado mais de uma vez");
        assertEquals(new java.util.HashSet<>(codigos), new java.util.HashSet<>(captor.getAllValues()));
    }

    @Test
    @DisplayName("com falhas espalhadas em paralelo, sucesso + falha fecham com o total (doc §6.1 item 3)")
    void publicarTudo_ComFalhasEmParalelo_ContabilizaSucessoEFalhaCorretamente() {
        List<String> codigos = IntStream.range(0, 20).mapToObj(i -> "IMV-" + i).toList();
        List<String> falhando = List.of("IMV-3", "IMV-7", "IMV-11", "IMV-15", "IMV-19");

        when(jdbcTemplate.queryForList(
                eq("SELECT cod_imovel FROM silver.imovel_rural"), eq(String.class)))
                .thenReturn(codigos);

        var spyService = spy(new GoldPublicacaoService(jdbcTemplate, simplificador, 4));
        for (String cod : codigos) {
            if (falhando.contains(cod)) {
                doThrow(new RuntimeException("falha simulada em " + cod)).when(spyService).recalcularImovel(cod);
            } else {
                doNothing().when(spyService).recalcularImovel(cod);
            }
        }

        var resultado = spyService.publicarTudo();

        assertEquals(20, resultado.totalImoveis());
        assertEquals(15, resultado.imoveisPublicados());
        assertEquals(5, resultado.imoveisComFalha().size());
        assertEquals(new java.util.HashSet<>(falhando), new java.util.HashSet<>(resultado.imoveisComFalha()));
        assertEquals(20, resultado.imoveisPublicados() + resultado.imoveisComFalha().size());

        // Cada falha grava sua linha em publicacao_indicadores_erro (comportamento de sempre).
        verify(jdbcTemplate, times(5)).update(
                argThat(sql -> sql != null && sql.contains("publicacao_indicadores_erro")),
                anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("progresso final gravado bate com o total mesmo com threads terminando fora de ordem (doc §6.1 item 4)")
    void publicarTudo_ComParalelismoQuatro_ProgressoFinalBateComOTotal() {
        List<String> codigos = IntStream.range(0, 137).mapToObj(i -> "IMV-" + i).toList();
        when(jdbcTemplate.queryForList(
                eq("SELECT cod_imovel FROM silver.imovel_rural"), eq(String.class)))
                .thenReturn(codigos);

        var spyService = spy(new GoldPublicacaoService(jdbcTemplate, simplificador, 4));
        doNothing().when(spyService).recalcularImovel(anyString());

        spyService.publicarTudo();

        ArgumentCaptor<Integer> processadosCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> sucessoCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(
                argThat(sql -> sql != null && sql.contains("total_sucesso")),
                processadosCaptor.capture(), sucessoCaptor.capture(), anyInt(), any());

        int somaProcessados = processadosCaptor.getAllValues().stream().mapToInt(Integer::intValue).sum();
        assertEquals(137, somaProcessados,
                "a soma dos incrementos de progresso tem que fechar com o total, nao importa quantas threads");

        int ultimoSucesso = sucessoCaptor.getAllValues().get(sucessoCaptor.getAllValues().size() - 1);
        assertEquals(137, ultimoSucesso, "a ultima atualizacao de progresso reflete o total final de sucesso");
    }
}
