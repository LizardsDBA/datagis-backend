package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
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
import br.gov.sp.cps.datagis.quarentena.ResultadoRevalidacaoQuarentena;
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
    @DisplayName("Revalidar registro individual de quarentena atualiza status mas NÃO sobe sozinho para a Silver se houver pendências")
    void deveRevalidarRegistroSemPersistirSozinhoNaSilver() {
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
        when(orquestradorValidacao.validarRegistroIndividual(eq(1L), any(RegistroBruto.class)))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(true, apr, null));

        // Simula que ainda existe 1 registro pendente no lote
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(loteId))).thenReturn(1L);

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest("Corrigido", "{\"cod_imovel\":\"PR-99-OK\"}", 7L);

        ResultadoRevalidacaoQuarentena resultado = transicaoService.promoverRegistroQuarentenaParaSilver("imovel_rural", quarentenaId, req);

        assertThat(resultado.sucesso()).isTrue();
        // REGRA ATÔMICA: NÃO deve persistir avulso na Silver
        verify(persistidorSilverService, never()).persistirAprovados(anyString(), anyList());
        // Deve atualizar status do registro na quarentena para CORRIGIDO_REPROCESSADO
        verify(jdbcTemplate).update(contains("status_analise_id"), eq(7L), eq(quarentenaId));
        // Deve atualizar o payload no registro bruto via JDBC
        verify(jdbcTemplate).update(contains("UPDATE BRONZE.registro_bruto"), eq("{\"cod_imovel\":\"PR-99-OK\"}"), eq(regBrutoId));
    }

    @Test
    @DisplayName("Ao revalidar o último registro pendente da quarentena, lote inteiro é liberado para a Silver")
    void deveLiberarLoteQuandoUltimoRegistroForAprovado() {
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
        when(orquestradorValidacao.validarRegistroIndividual(eq(1L), any(RegistroBruto.class)))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(true, apr, null));

        // Simula que NÃO existem mais registros pendentes no lote (0 pendentes)
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(loteId))).thenReturn(0L);

        // Mocks para liberarLoteParaSilver que será chamado automaticamente
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR Teste").entidadeSilver("imovel_rural").build();
        LoteIngestao lote = LoteIngestao.builder().id(loteId).fonteDado(fonte).status("RETIDO_QUARENTENA").build();
        when(loteIngestaoRepository.findById(loteId)).thenReturn(Optional.of(lote));
        when(registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId)).thenReturn(List.of(reg));
        when(orquestradorValidacao.orquestrarLote(eq(1L), anyList(), eq("cod_imovel")))
                .thenReturn(new ResultadoOrquestracao(List.of(apr), List.of(), List.of(), 1, 1, 0, 0));

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest("Corrigido", "{\"cod_imovel\":\"PR-99-OK\"}", 7L);

        ResultadoRevalidacaoQuarentena resultado = transicaoService.promoverRegistroQuarentenaParaSilver("imovel_rural", quarentenaId, req);

        assertThat(resultado.sucesso()).isTrue();
        // Lote inteiro liberado: agora sim os registros do lote sobem para a Silver!
        verify(persistidorSilverService).persistirAprovados(eq("imovel_rural"), eq(List.of(apr)));
        assertThat(lote.getStatus()).isEqualTo(TransicaoBronzeSilverService.STATUS_PROMOVIDO_SILVER);
    }

    @Test
    @DisplayName("Liberar lote deve excluir registros marcados como DESCARTADO na quarentena")
    void deveExcluirRegistrosDescartadosAoLiberarLote() {
        Long loteId = 7L;
        FonteDado fonte = FonteDado.builder().id(1L).nome("CAR Teste").entidadeSilver("imovel_rural").build();
        LoteIngestao lote = LoteIngestao.builder().id(loteId).fonteDado(fonte).status("RETIDO_QUARENTENA").build();

        RegistroBruto regValido = RegistroBruto.builder().id(701L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-701\"}").build();
        RegistroBruto regDescartado = RegistroBruto.builder().id(702L).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-702\"}").build();

        when(loteIngestaoRepository.findById(loteId)).thenReturn(Optional.of(lote));
        when(registroBrutoRepository.findByLoteIngestaoIdOrderByIdAsc(loteId)).thenReturn(List.of(regValido, regDescartado));

        // Simula que o registro 702L foi descartado na quarentena
        when(jdbcTemplate.query(contains("DESCARTADO"), any(org.springframework.jdbc.core.RowMapper.class), eq(loteId)))
                .thenReturn(List.of(702L));

        RegistroAprovadoSilver aprValido = new RegistroAprovadoSilver(regValido, Map.of("cod_imovel", "PR-701"), null);
        when(orquestradorValidacao.orquestrarLote(eq(1L), eq(List.of(regValido)), eq("cod_imovel")))
                .thenReturn(new ResultadoOrquestracao(List.of(aprValido), List.of(), List.of(), 1, 1, 0, 0));

        boolean sucesso = transicaoService.liberarLoteParaSilver(loteId, 10L);

        assertThat(sucesso).isTrue();
        // Apenas o registro válido (701L) é persistido na Silver, o 702L foi excluído
        verify(persistidorSilverService).persistirAprovados(eq("imovel_rural"), eq(List.of(aprValido)));
    }

    @Test
    @DisplayName("Quando registro com erro de domínio é aprovado, deve reavaliar e promover outros registros de domínio da quarentena")
    void quandoRegistroComErroDominioForAprovado_deveTentarPromoverOutrosRegistrosQuarentenaComMesmoErro() {
        Long quarentenaId = 10L;
        Long loteId = 5L;
        Long regBrutoId = 501L;

        // Registro inspecionado com erro VALOR_FORA_DO_DOMINIO
        Map<String, Object> rowInspecionado = Map.of(
                "id", quarentenaId,
                "fonte_dado_id", 1L,
                "lote_ingestao_id", loteId,
                "registro_bruto_id", regBrutoId,
                "payload_original", "{\"cod_imovel\":\"PR-DOM-1\"}",
                "tipo_erro_codigo", "VALOR_FORA_DO_DOMINIO"
        );
        when(jdbcTemplate.queryForList(contains("WHERE q.id = ?"), eq(quarentenaId)))
                .thenReturn(List.of(rowInspecionado));

        RegistroBruto regInspecionado = RegistroBruto.builder().id(regBrutoId).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-DOM-1\"}").build();
        when(registroBrutoRepository.findById(regBrutoId)).thenReturn(Optional.of(regInspecionado));

        RegistroAprovadoSilver aprInspecionado = new RegistroAprovadoSilver(regInspecionado, Map.of("cod_imovel", "PR-DOM-1"), null);
        when(orquestradorValidacao.validarRegistroIndividual(eq(1L), any(RegistroBruto.class)))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(true, aprInspecionado, null));

        // Simula outro registro candidato com erro VALOR_FORA_DO_DOMINIO retido em q_imovel_rural
        Map<String, Object> candidato = Map.of(
                "id", 20L,
                "fonte_dado_id", 1L,
                "lote_ingestao_id", loteId,
                "registro_bruto_id", 502L,
                "payload_original", "{\"cod_imovel\":\"PR-DOM-2\"}"
        );
        when(jdbcTemplate.queryForList(contains("VALOR_FORA_DO_DOMINIO"), eq(quarentenaId)))
                .thenReturn(List.of(candidato), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(loteId))).thenReturn(1L);

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest("Revalidado", null, 99L);

        ResultadoRevalidacaoQuarentena resultado =
                transicaoService.promoverRegistroQuarentenaParaSilver("imovel_rural", quarentenaId, req);

        assertThat(resultado.sucesso()).isTrue();
        assertThat(resultado.totalColetivoAprovados()).isEqualTo(1);
        // Atualizou o status do registro inspecionado (id 10)
        verify(jdbcTemplate).update(contains("status_analise_id"), eq(99L), eq(quarentenaId));
        // E também atualizou o status do outro registro de domínio (id 20)
        verify(jdbcTemplate).update(contains("status_analise_id"), eq(99L), eq(20L));
    }

    @Test
    @DisplayName("Quando registro inicial falhar na validação, lança exceção e NÃO tenta revalidar os outros")
    void quandoRegistroComErroDominioFalhar_naoDeveTentarPromoverOutrosRegistros() {
        Long quarentenaId = 11L;
        Long loteId = 5L;
        Long regBrutoId = 503L;

        Map<String, Object> rowInspecionado = Map.of(
                "id", quarentenaId,
                "fonte_dado_id", 1L,
                "lote_ingestao_id", loteId,
                "registro_bruto_id", regBrutoId,
                "payload_original", "{\"cod_imovel\":\"PR-DOM-FAIL\"}",
                "campo_problema", "tipo_imovel",
                "tipo_erro_codigo", "VALOR_FORA_DO_DOMINIO"
        );
        when(jdbcTemplate.queryForList(contains("WHERE q.id = ?"), eq(quarentenaId)))
                .thenReturn(List.of(rowInspecionado));

        RegistroBruto reg = RegistroBruto.builder().id(regBrutoId).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-DOM-FAIL\"}").build();
        when(registroBrutoRepository.findById(regBrutoId)).thenReturn(Optional.of(reg));

        RegistroRejeitadoQuarentena rej = new RegistroRejeitadoQuarentena(reg, "VALOR_FORA_DO_DOMINIO", "tipo_imovel", "INVALIDO", "Valor ainda não existe no domínio");
        when(orquestradorValidacao.validarRegistroIndividual(eq(1L), any(RegistroBruto.class)))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(false, null, rej));

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest("Revalidado", null, 99L);

        assertThrows(IllegalArgumentException.class, () ->
                transicaoService.promoverRegistroQuarentenaParaSilver("imovel_rural", quarentenaId, req)
        );

        // NENHUM update para CORRIGIDO_REPROCESSADO deve ter sido executado
        verify(jdbcTemplate, never()).update(contains("status_analise_id = (SELECT id FROM QUARENTENA.status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO')"), any(), any());
    }

    @Test
    @DisplayName("Quando registro com erro de domínio transitar para novo erro (CPF), sincroniza novo erro e DISPARA revalidação em cascata para os outros")
    void quandoRegistroComErroDominioTransitarParaNovoErro_deveDispararRevalidacaoEmCascataParaOutrosRegistros() {
        Long quarentenaId = 15L;
        Long loteId = 5L;
        Long regBrutoId = 505L;

        // Registro inspecionado que originalmente possuía erro VALOR_FORA_DO_DOMINIO no campo tipo_imovel
        Map<String, Object> rowInspecionado = Map.of(
                "id", quarentenaId,
                "fonte_dado_id", 1L,
                "lote_ingestao_id", loteId,
                "registro_bruto_id", regBrutoId,
                "payload_original", "{\"cpf\":\"00000000000\",\"tipo_imovel\":\"NOVO_DOMINIO\"}",
                "campo_problema", "tipo_imovel",
                "tipo_erro_codigo", "VALOR_FORA_DO_DOMINIO"
        );
        when(jdbcTemplate.queryForList(contains("WHERE q.id = ?"), eq(quarentenaId)))
                .thenReturn(List.of(rowInspecionado));

        RegistroBruto reg = RegistroBruto.builder().id(regBrutoId).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cpf\":\"00000000000\",\"tipo_imovel\":\"NOVO_DOMINIO\"}").build();
        when(registroBrutoRepository.findById(regBrutoId)).thenReturn(Optional.of(reg));

        // Registro inspecionado supera o domínio, mas agora cai em CPF_CNPJ_INVALIDO
        RegistroRejeitadoQuarentena rejNovoErro = new RegistroRejeitadoQuarentena(reg, "CPF_CNPJ_INVALIDO", "cpf", "00000000000", "CPF inválido");
        when(orquestradorValidacao.validarRegistroIndividual(eq(1L), any(RegistroBruto.class)))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(false, null, rejNovoErro));

        // Simula outro registro candidato com erro VALOR_FORA_DO_DOMINIO na quarentena
        Map<String, Object> candidatoOutro = Map.of(
                "id", 25L,
                "fonte_dado_id", 1L,
                "lote_ingestao_id", 8L,
                "registro_bruto_id", 606L,
                "payload_original", "{\"cpf\":\"12345678909\",\"tipo_imovel\":\"NOVO_DOMINIO\"}"
        );
        when(jdbcTemplate.queryForList(contains("VALOR_FORA_DO_DOMINIO"), eq(quarentenaId)))
                .thenReturn(List.of(candidatoOutro), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        // Para o registro candidato (id 25), a validação agora é 100% válida!
        when(orquestradorValidacao.validarRegistroIndividual(eq(1L), argThat(r -> r != null && Long.valueOf(606L).equals(r.getId()))))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(true, null, null));

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest("Revalidado", null, 99L);

        // Deve lançar IllegalArgumentException para o registro inspecionado (pois ainda tem erro de CPF)
        assertThrows(IllegalArgumentException.class, () ->
                transicaoService.promoverRegistroQuarentenaParaSilver("imovel_rural", quarentenaId, req)
        );

        // 1. Atualizou o registro inspecionado para o novo erro (CPF_CNPJ_INVALIDO) com status PENDENTE
        verify(jdbcTemplate).update(contains("tipo_erro_id = (SELECT id FROM"), eq("CPF_CNPJ_INVALIDO"), eq("cpf"), eq("00000000000"), eq("CPF inválido"), eq(quarentenaId));

        // 2. DISPAROU A CASCATA: consultou candidatos de domínio e aprovou o outro registro (id 25) para CORRIGIDO_REPROCESSADO
        verify(jdbcTemplate).update(contains("status_analise_id = (SELECT id FROM QUARENTENA.status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO')"), eq(99L), eq(25L));
    }

    @Test
    @DisplayName("Quando registro com erro de domínio transitar para OUTRO campo de domínio (biomas), sincroniza e DISPARA cascata para outros registros")
    void quandoRegistroComErroDominioTransitarParaOutroCampoDominio_deveDispararRevalidacaoEmCascata() {
        Long quarentenaId = 16L;
        Long loteId = 5L;
        Long regBrutoId = 506L;

        // Registro inspecionado originalmente com erro de domínio em categoria_area_protegida
        Map<String, Object> rowInspecionado = Map.of(
                "id", quarentenaId,
                "fonte_dado_id", 2L,
                "lote_ingestao_id", loteId,
                "registro_bruto_id", regBrutoId,
                "payload_original", "{\"categoria_area_protegida\":\"NOVA_CAT\",\"biomas\":\"SISTEMA COSTEIRO-MARINHO\"}",
                "campo_problema", "categoria_area_protegida",
                "tipo_erro_codigo", "VALOR_FORA_DO_DOMINIO"
        );
        when(jdbcTemplate.queryForList(contains("WHERE q.id = ?"), eq(quarentenaId)))
                .thenReturn(List.of(rowInspecionado));

        RegistroBruto reg = RegistroBruto.builder().id(regBrutoId).loteIngestaoId(loteId).fonteDadoId(2L).payload("{\"categoria_area_protegida\":\"NOVA_CAT\",\"biomas\":\"SISTEMA COSTEIRO-MARINHO\"}").build();
        when(registroBrutoRepository.findById(regBrutoId)).thenReturn(Optional.of(reg));

        // Registro inspecionado supera categoria_area_protegida, mas cai em VALOR_FORA_DO_DOMINIO no campo biomas!
        RegistroRejeitadoQuarentena rejOutroCampo = new RegistroRejeitadoQuarentena(reg, "VALOR_FORA_DO_DOMINIO", "biomas", "SISTEMA COSTEIRO-MARINHO", "Valor não encontrado na tabela bioma");
        when(orquestradorValidacao.validarRegistroIndividual(eq(2L), any(RegistroBruto.class)))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(false, null, rejOutroCampo));

        // Outro registro candidato que só precisava de NOVA_CAT
        Map<String, Object> outroCandidato = Map.of(
                "id", 30L,
                "fonte_dado_id", 2L,
                "lote_ingestao_id", 9L,
                "registro_bruto_id", 607L,
                "payload_original", "{\"categoria_area_protegida\":\"NOVA_CAT\",\"biomas\":\"Mata Atlântica\"}"
        );
        when(jdbcTemplate.queryForList(contains("VALOR_FORA_DO_DOMINIO"), eq(quarentenaId)))
                .thenReturn(List.of(outroCandidato), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

        when(orquestradorValidacao.validarRegistroIndividual(eq(2L), argThat(r -> r != null && Long.valueOf(607L).equals(r.getId()))))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(true, null, null));

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest("Revalidado", null, 99L);

        assertThrows(IllegalArgumentException.class, () ->
                transicaoService.promoverRegistroQuarentenaParaSilver("area_protegida", quarentenaId, req)
        );

        // 1. Atualizou o registro 16 com o novo campo problema biomas
        verify(jdbcTemplate).update(contains("tipo_erro_id = (SELECT id FROM"), eq("VALOR_FORA_DO_DOMINIO"), eq("biomas"), eq("SISTEMA COSTEIRO-MARINHO"), eq("Valor não encontrado na tabela bioma"), eq(quarentenaId));

        // 2. DISPAROU A CASCATA: o outro candidato (id 30) foi aprovado para CORRIGIDO_REPROCESSADO
        verify(jdbcTemplate).update(contains("status_analise_id = (SELECT id FROM QUARENTENA.status_analise_quarentena WHERE codigo = 'CORRIGIDO_REPROCESSADO')"), eq(99L), eq(30L));
    }

    @Test
    @DisplayName("Deve revalidar com sucesso registro com geometriaCorrigida mantendo BRONZE.registro_bruto intacto")
    void deveRevalidarComSucessoRegistroComGeometriaCorrigidaMantendoBronzeIntacto() {
        Long quarentenaId = 55L;
        Long loteId = 12L;
        Long regBrutoId = 777L;
        String wktOriginal = "POLYGON ((-51 0, -51 1))";
        String wktCorrigido = "POLYGON ((-51.4 -24.2, -51.3 -24.2, -51.3 -24.1, -51.4 -24.1, -51.4 -24.2))";

        Map<String, Object> row = Map.of(
                "id", quarentenaId,
                "fonte_dado_id", 1L,
                "lote_ingestao_id", loteId,
                "registro_bruto_id", regBrutoId,
                "geometria_wkt", wktOriginal,
                "payload_original", "{\"cod_imovel\":\"PR-GEO-01\"}",
                "tipo_erro_codigo", "GEOMETRIA_MALFORMADA"
        );
        when(jdbcTemplate.queryForList(anyString(), eq(quarentenaId))).thenReturn(List.of(row));

        RegistroBruto reg = RegistroBruto.builder()
                .id(regBrutoId)
                .loteIngestaoId(loteId)
                .fonteDadoId(1L)
                .payload("{\"cod_imovel\":\"PR-GEO-01\"}")
                .geometriaWkt(wktOriginal)
                .build();
        when(registroBrutoRepository.findById(regBrutoId)).thenReturn(Optional.of(reg));

        RegistroAprovadoSilver apr = new RegistroAprovadoSilver(reg, Map.of("cod_imovel", "PR-GEO-01"), wktCorrigido);
        when(orquestradorValidacao.validarRegistroIndividual(eq(1L), any(RegistroBruto.class)))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(true, apr, null));

        // Simula que ainda há outros registros no lote
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), eq(loteId))).thenReturn(2L);

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest(null, null, 15L, wktCorrigido);

        ResultadoRevalidacaoQuarentena res = transicaoService.promoverRegistroQuarentenaParaSilver("imovel_rural", quarentenaId, req);

        assertThat(res.sucesso()).isTrue();
        // Deve atualizar geometria_wkt na quarentena
        verify(jdbcTemplate).update(contains("UPDATE QUARENTENA.q_imovel_rural SET geometria_wkt = ? WHERE id = ?"), eq(wktCorrigido), eq(quarentenaId));
        // NÃO deve atualizar geometria no BRONZE.registro_bruto (Bronze permanece imutável e original)
        verify(jdbcTemplate, never()).update(contains("UPDATE BRONZE.registro_bruto SET geometria_wkt"), any(), any());
        // Deve atualizar status na quarentena
        verify(jdbcTemplate).update(contains("status_analise_id"), eq(15L), eq(quarentenaId));
    }

    @Test
    @DisplayName("Deve falhar revalidação quando geometriaCorrigida ainda for inválida")
    void deveFalharRevalidacaoQuandoGeometriaCorrigidaAindaContemInconsistencia() {
        Long quarentenaId = 56L;
        Long loteId = 12L;
        Long regBrutoId = 778L;

        Map<String, Object> row = Map.of(
                "id", quarentenaId,
                "fonte_dado_id", 1L,
                "lote_ingestao_id", loteId,
                "registro_bruto_id", regBrutoId,
                "geometria_wkt", "POLYGON ((0 0, 1 1))",
                "payload_original", "{\"cod_imovel\":\"PR-GEO-02\"}",
                "tipo_erro_codigo", "GEOMETRIA_INVALIDA"
        );
        when(jdbcTemplate.queryForList(anyString(), eq(quarentenaId))).thenReturn(List.of(row));

        RegistroBruto reg = RegistroBruto.builder().id(regBrutoId).loteIngestaoId(loteId).fonteDadoId(1L).payload("{\"cod_imovel\":\"PR-GEO-02\"}").build();
        when(registroBrutoRepository.findById(regBrutoId)).thenReturn(Optional.of(reg));

        RegistroRejeitadoQuarentena rej = new RegistroRejeitadoQuarentena(reg, "GEOMETRIA_INVALIDA", "geometria", "POLYGON ((...))", "Polígono com auto-interseção");
        when(orquestradorValidacao.validarRegistroIndividual(eq(1L), any(RegistroBruto.class)))
                .thenReturn(new OrquestradorValidacao.ResultadoValidacaoRegistro(false, null, rej));

        br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest req =
                new br.gov.sp.cps.datagis.quarentena.AcaoQuarentenaRequest(null, null, 15L, "POLYGON ((0 0, 0 2, 2 0, 2 2, 0 0))");

        assertThrows(IllegalArgumentException.class, () ->
                transicaoService.promoverRegistroQuarentenaParaSilver("imovel_rural", quarentenaId, req)
        );

        // NENHUM update executado
        verify(jdbcTemplate, never()).update(contains("status_analise_id"), any(), any());
        verify(jdbcTemplate, never()).update(contains("UPDATE QUARENTENA.q_imovel_rural SET geometria_wkt"), any(), any());
    }
}
