package br.gov.sp.cps.datagis.gold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoldPublicacaoServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SimplificadorGeometriaMapa simplificador;

    @InjectMocks
    private GoldPublicacaoService service;

    @Test
    @DisplayName("publicar() deve orquestrar publicacao consolidada e atualizar status dos lotes para PROMOVIDO_GOLD")
    void publicar_DeveOrquestrarPublicacaoConsolidadaEAtualizarLotes() {
        when(jdbcTemplate.queryForList(
                eq("SELECT cod_imovel FROM silver.imovel_rural"),
                eq(String.class))).thenReturn(Collections.emptyList());

        when(jdbcTemplate.queryForObject(
                eq("SELECT MAX(id) FROM gold.publicacao_indicadores"),
                eq(Long.class))).thenReturn(1L);

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

        when(jdbcTemplate.queryForObject(
                eq("SELECT MAX(id) FROM gold.publicacao_indicadores"),
                eq(Long.class))).thenReturn(2L);

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
}
