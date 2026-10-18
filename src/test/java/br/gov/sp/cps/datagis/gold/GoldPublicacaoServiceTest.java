package br.gov.sp.cps.datagis.gold;

import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoldPublicacaoServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private SimplificadorGeometriaMapa simplificador;

    @Mock
    private LoteIngestaoRepository loteIngestaoRepository;

    @InjectMocks
    private GoldPublicacaoService service;

    @Test
    @DisplayName("Deve lançar exceção se lote não for encontrado")
    void publicar_LoteInexistente_DeveLancarExcecao() {
        when(loteIngestaoRepository.findByIdComFonte(999L)).thenReturn(Optional.empty());

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            service.publicar(999L);
        });

        assertEquals("Lote #999 não encontrado.", ex.getMessage());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Não deve permitir migrar lote que já está como PROMOVIDO_GOLD")
    void publicar_LoteJaPromovidoGold_DeveLancarExcecao() {
        LoteIngestao lote = new LoteIngestao();
        lote.setId(10L);
        lote.setStatus(GoldPublicacaoService.STATUS_PROMOVIDO_GOLD);

        when(loteIngestaoRepository.findByIdComFonte(10L)).thenReturn(Optional.of(lote));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            service.publicar(10L);
        });

        assertEquals("O lote #10 já foi migrado para a Gold e não pode ser migrado novamente.", ex.getMessage());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Não deve permitir migrar lote com status diferente de PROMOVIDO_SILVER")
    void publicar_LoteComStatusInvalido_DeveLancarExcecao() {
        LoteIngestao lote = new LoteIngestao();
        lote.setId(20L);
        lote.setStatus("PROCESSANDO");

        when(loteIngestaoRepository.findByIdComFonte(20L)).thenReturn(Optional.of(lote));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> {
            service.publicar(20L);
        });

        assertEquals("O lote #20 está com status PROCESSANDO. Apenas lotes com status PROMOVIDO_SILVER podem ser migrados para a Gold.", ex.getMessage());
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Lote com status PROMOVIDO_SILVER tem mapeamento correto de entidadeSilver para camada e não calcula CAR")
    void mapearEntidadeSilverParaCodigoCamada_MapeiaCorretamente() {
        LoteIngestao loteDesmatamento = new LoteIngestao();
        loteDesmatamento.setId(30L);
        loteDesmatamento.setStatus("PROMOVIDO_SILVER");
        FonteDado fonteDesmatamento = new FonteDado();
        fonteDesmatamento.setEntidadeSilver("desmatamento");
        fonteDesmatamento.setNome("PRODES Desmatamento");
        loteDesmatamento.setFonteDado(fonteDesmatamento);

        when(loteIngestaoRepository.findByIdComFonte(30L)).thenReturn(Optional.of(loteDesmatamento));

        when(jdbcTemplate.queryForObject(
                eq("SELECT id FROM gold.mapa_camada WHERE codigo = ? AND ativo = 1"),
                eq(Long.class), eq("DESMATAMENTO"))).thenReturn(3L);

        when(jdbcTemplate.queryForObject(
                anyString(),
                eq(Long.class), eq(3L), eq(3L))).thenReturn(1L);

        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM silver.desmatamento WHERE geometria IS NOT NULL"),
                eq(Long.class))).thenReturn(0L);

        when(jdbcTemplate.queryForObject(
                eq("SELECT id FROM gold.publicacao_camada_mapa WHERE mapa_camada_id = ? AND versao_publicacao = ?"),
                eq(Long.class), eq(3L), eq(1L))).thenReturn(100L);

        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());

        var resultado = service.publicar(30L);

        assertEquals(0, resultado.totalImoveis());
        assertEquals(0, resultado.imoveisPublicados());
        assertEquals(1, resultado.camadasPublicadas().size());
        assertEquals("DESMATAMENTO", resultado.camadasPublicadas().get(0));
        assertEquals(GoldPublicacaoService.STATUS_PROMOVIDO_GOLD, loteDesmatamento.getStatus());
    }
}
