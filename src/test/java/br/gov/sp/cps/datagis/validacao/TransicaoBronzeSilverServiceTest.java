package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.bronze.RegistroBrutoRepository;
import br.gov.sp.cps.datagis.controle.FonteDado;
import br.gov.sp.cps.datagis.controle.FonteDadoRepository;
import br.gov.sp.cps.datagis.controle.LoteIngestao;
import br.gov.sp.cps.datagis.controle.LoteIngestaoRepository;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class TransicaoBronzeSilverServiceTest {

    @Mock
    private LoteIngestaoRepository loteIngestaoRepository;

    @Mock
    private FonteDadoRepository fonteDadoRepository;

    @Mock
    private RegistroBrutoRepository registroBrutoRepository;

    @Mock
    private OrquestradorValidacao orquestradorValidacao;

    @Mock
    private PersistidorSilverService persistidorSilverService;

    @Mock
    private PersistidorQuarentenaService persistidorQuarentenaService;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TransicaoBronzeSilverService transicaoService;

    @BeforeEach
    void setUp() {
        transicaoService = new TransicaoBronzeSilverService(
                loteIngestaoRepository,
                fonteDadoRepository,
                registroBrutoRepository,
                orquestradorValidacao,
                persistidorSilverService,
                persistidorQuarentenaService,
                jdbcTemplate,
                "QUARENTENA"
        );
    }

    @Test
    @DisplayName("Regra All-or-Nothing: Lote 100% válido é promovido à Silver e status vira PROMOVIDO_SILVER")
    void devePromoverLoteParaSilverQuando100PorCentoValido() {
        Long loteId = 1L;
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR Teste").entidadeSilver("imovel_rural").build();
        LoteIngestao lote = LoteIngestao.builder().id(loteId).fonteDado(fonte).status("CONCLUIDO").quantidadeRegistros(2).build();

        RegistroBruto reg1 = RegistroBruto.builder().id(101L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-1\"}").build();
        RegistroBruto reg2 = RegistroBruto.builder().id(102L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-2\"}").build();
        List<RegistroBruto> registros = List.of(reg1, reg2);

        when(loteIngestaoRepository.findById(loteId)).thenReturn(Optional.of(lote));
        when(registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId)).thenReturn(registros);

        RegistroAprovadoSilver apr1 = new RegistroAprovadoSilver(reg1, Map.of("cod_imovel", "PR-1"), null);
        RegistroAprovadoSilver apr2 = new RegistroAprovadoSilver(reg2, Map.of("cod_imovel", "PR-2"), null);
        ResultadoOrquestracao resultado100Valido = new ResultadoOrquestracao(
                List.of(apr1, apr2),
                Collections.emptyList(),
                Collections.emptyList(),
                2, 2, 0, 0
        );

        when(orquestradorValidacao.orquestrarLote(eq(1L), eq(registros), eq("cod_imovel"), any()))
                .thenReturn(resultado100Valido);

        var resultadoTransicao = transicaoService.processarLote(loteId);

        assertThat(resultadoTransicao.statusFinal()).isEqualTo(TransicaoBronzeSilverService.STATUS_PROMOVIDO_SILVER);
        assertThat(resultadoTransicao.aprovadosSilver()).isEqualTo(2);
        assertThat(resultadoTransicao.rejeitadosQuarentena()).isZero();
        assertThat(lote.getStatus()).isEqualTo(TransicaoBronzeSilverService.STATUS_PROMOVIDO_SILVER);

        verify(persistidorSilverService).persistirAprovados(eq("imovel_rural"), anyList());
        verify(persistidorQuarentenaService, never()).persistirRejeitados(anyString(), anyList());
        verify(loteIngestaoRepository).save(lote);
    }

    @Test
    @DisplayName("Regra All-or-Nothing: Se houver 1 erro no lote, Silver recebe ZERO registros e lote fica RETIDO_QUARENTENA")
    void deveBloquearSilverEReterEmQuarentenaQuandoHouverPeloMenosUmErro() {
        Long loteId = 2L;
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR Teste").entidadeSilver("imovel_rural").build();
        LoteIngestao lote = LoteIngestao.builder().id(loteId).fonteDado(fonte).status("CONCLUIDO").quantidadeRegistros(2).build();

        RegistroBruto reg1 = RegistroBruto.builder().id(201L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-1\"}").build();
        RegistroBruto reg2 = RegistroBruto.builder().id(202L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-2\"}").build();
        List<RegistroBruto> registros = List.of(reg1, reg2);

        when(loteIngestaoRepository.findById(loteId)).thenReturn(Optional.of(lote));
        when(registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId)).thenReturn(registros);

        RegistroAprovadoSilver apr1 = new RegistroAprovadoSilver(reg1, Map.of("cod_imovel", "PR-1"), null);
        RegistroRejeitadoQuarentena rej2 = new RegistroRejeitadoQuarentena(reg2, "VALOR_FORA_DO_DOMINIO", "situacao_imovel", "XX", "Invalido");
        ResultadoOrquestracao resultadoComErro = new ResultadoOrquestracao(
                List.of(apr1),
                List.of(rej2),
                Collections.emptyList(),
                2, 1, 1, 0
        );

        when(orquestradorValidacao.orquestrarLote(eq(1L), eq(registros), eq("cod_imovel"), any()))
                .thenReturn(resultadoComErro);

        var resultadoTransicao = transicaoService.processarLote(loteId);

        assertThat(resultadoTransicao.statusFinal()).isEqualTo(TransicaoBronzeSilverService.STATUS_RETIDO_QUARENTENA);
        assertThat(resultadoTransicao.aprovadosSilver()).isZero(); // 0 na Silver!
        assertThat(resultadoTransicao.rejeitadosQuarentena()).isEqualTo(1);
        assertThat(lote.getStatus()).isEqualTo(TransicaoBronzeSilverService.STATUS_RETIDO_QUARENTENA);

        // NENHUM registro vai para a Silver
        verify(persistidorSilverService, never()).persistirAprovados(anyString(), anyList());
        // Registro inconsistente vai para a Quarentena
        verify(persistidorQuarentenaService).persistirRejeitados(eq("imovel_rural"), anyList());
        verify(loteIngestaoRepository).save(lote);
    }

    @Test
    @DisplayName("Liberação manual do analista promove lote retido para a Silver")
    void deveLiberarLoteAposAprovacaoDoAnalista() {
        Long loteId = 3L;
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR Teste").entidadeSilver("imovel_rural").build();
        LoteIngestao lote = LoteIngestao.builder().id(loteId).fonteDado(fonte).status("RETIDO_QUARENTENA").build();

        RegistroBruto reg1 = RegistroBruto.builder().id(301L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-1\"}").build();
        when(loteIngestaoRepository.findById(loteId)).thenReturn(Optional.of(lote));
        when(registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId)).thenReturn(List.of(reg1));

        RegistroAprovadoSilver apr1 = new RegistroAprovadoSilver(reg1, Map.of("cod_imovel", "PR-1"), null);
        when(orquestradorValidacao.orquestrarLote(eq(1L), anyList(), eq("cod_imovel")))
                .thenReturn(new ResultadoOrquestracao(List.of(apr1), List.of(), List.of(), 1, 1, 0, 0));

        boolean sucesso = transicaoService.liberarLoteParaSilver(loteId, 10L);

        assertThat(sucesso).isTrue();
        assertThat(lote.getStatus()).isEqualTo(TransicaoBronzeSilverService.STATUS_PROMOVIDO_SILVER);
        verify(persistidorSilverService).persistirAprovados(eq("imovel_rural"), anyList());
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), eq("CORRIGIDO_REPROCESSADO"), eq(10L), eq(loteId));
        verify(loteIngestaoRepository).save(lote);
    }

    @Test
    @DisplayName("Descarte manual pelo analista marca lote como DESCARTADO e nada sobe para a Silver")
    void deveDescartarLoteAposRecusaDoAnalista() {
        Long loteId = 4L;
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR Teste").entidadeSilver("imovel_rural").build();
        LoteIngestao lote = LoteIngestao.builder().id(loteId).fonteDado(fonte).status("RETIDO_QUARENTENA").build();

        when(loteIngestaoRepository.findById(loteId)).thenReturn(Optional.of(lote));

        boolean sucesso = transicaoService.descartarLote(loteId, "Dados corrompidos", 10L);

        assertThat(sucesso).isTrue();
        assertThat(lote.getStatus()).isEqualTo(TransicaoBronzeSilverService.STATUS_DESCARTADO);
        verify(persistidorSilverService, never()).persistirAprovados(anyString(), anyList());
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), eq("DESCARTADO"), eq(10L), eq("Dados corrompidos"), eq(loteId));
        verify(loteIngestaoRepository).save(lote);
    }

    @Test
    @DisplayName("Liberação manual de lote deve transformar e promover registros rejeitados para a Silver")
    void devePromoverRejeitadosQuandoLoteForLiberado() {
        Long loteId = 5L;
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR Teste").entidadeSilver("imovel_rural").build();
        LoteIngestao lote = LoteIngestao.builder().id(loteId).fonteDado(fonte).status("RETIDO_QUARENTENA").build();

        RegistroBruto reg1 = RegistroBruto.builder().id(501L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-1\"}").build();
        RegistroBruto reg2 = RegistroBruto.builder().id(502L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-2\"}").build();
        when(loteIngestaoRepository.findById(loteId)).thenReturn(Optional.of(lote));
        when(registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId)).thenReturn(List.of(reg1, reg2));

        RegistroAprovadoSilver apr1 = new RegistroAprovadoSilver(reg1, Map.of("cod_imovel", "PR-1"), null);
        RegistroRejeitadoQuarentena rej2 = new RegistroRejeitadoQuarentena(reg2, "VALOR_FORA_DO_DOMINIO", "tipo", "X", "Invalido");
        when(orquestradorValidacao.orquestrarLote(eq(1L), anyList(), eq("cod_imovel")))
                .thenReturn(new ResultadoOrquestracao(List.of(apr1), List.of(rej2), List.of(), 2, 1, 1, 0));

        RegistroAprovadoSilver aprRej2 = new RegistroAprovadoSilver(reg2, Map.of("cod_imovel", "PR-2"), null);
        when(orquestradorValidacao.transformarRegistro(eq(1L), eq(reg2))).thenReturn(aprRej2);

        boolean sucesso = transicaoService.liberarLoteParaSilver(loteId, 10L);

        assertThat(sucesso).isTrue();
        assertThat(lote.getStatus()).isEqualTo(TransicaoBronzeSilverService.STATUS_PROMOVIDO_SILVER);
        verify(persistidorSilverService).persistirAprovados(eq("imovel_rural"), eq(List.of(apr1, aprRej2)));
        verify(jdbcTemplate, atLeastOnce()).update(anyString(), eq("CORRIGIDO_REPROCESSADO"), eq(10L), eq(loteId));
    }

    @Test
    @DisplayName("Promover registro individual de quarentena deve transformar e persistir na Silver")
    void devePromoverRegistroIndividualQuarentena() {
        Long quarentenaId = 99L;
        Long loteId = 6L;
        Long regBrutoId = 601L;

        Map<String, Object> row = Map.of(
                "id", quarentenaId,
                "fonte_dado_id", 1L,
                "lote_ingestao_id", loteId,
                "registro_bruto_id", regBrutoId,
                "payload_original", "{\"cod_imovel\":\"PR-99\"}"
        );
        when(jdbcTemplate.queryForList(anyString(), eq(quarentenaId))).thenReturn(List.of(row));

        RegistroBruto reg = RegistroBruto.builder().id(regBrutoId).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-99\"}").build();
        when(registroBrutoRepository.findById(regBrutoId)).thenReturn(Optional.of(reg));

        RegistroAprovadoSilver apr = new RegistroAprovadoSilver(reg, Map.of("cod_imovel", "PR-99"), null);
        when(orquestradorValidacao.transformarRegistro(eq(1L), any(RegistroBruto.class))).thenReturn(apr);

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest("Corrigido", "{\"cod_imovel\":\"PR-99-OK\"}", 7L);

        boolean sucesso = transicaoService.promoverRegistroQuarentenaParaSilver("imovel_rural", quarentenaId, req);

        assertThat(sucesso).isTrue();
        verify(persistidorSilverService).persistirAprovados(eq("imovel_rural"), eq(List.of(apr)));
        verify(jdbcTemplate).update(anyString(), eq(7L), eq(quarentenaId));
    }
}
