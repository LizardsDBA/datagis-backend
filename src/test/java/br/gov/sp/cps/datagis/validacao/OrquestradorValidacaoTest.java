package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import br.gov.sp.cps.datagis.controle.MapeamentoColuna;
import br.gov.sp.cps.datagis.controle.MapeamentoColunaRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OrquestradorValidacaoTest {

    @Mock
    private MapeamentoColunaRepository mapeamentoRepository;

    private OrquestradorValidacao orquestrador;

    private final SanitizadorEntrada sanitizador = new SanitizadorEntrada();
    private final ValidadorCpfCnpj validadorCpfCnpj = new ValidadorCpfCnpj();
    private final ValidadorFaixaMinima validadorFaixaMinima = new ValidadorFaixaMinima();
    private final ValidadorGeometria validadorGeometria = new ValidadorGeometria();
    private final ValidadorRegressao validadorRegressao = new ValidadorRegressao();
    private final DeduplicadorRegistros deduplicador = new DeduplicadorRegistros();

    @BeforeEach
    void setUp() {
        List<ValidadorCampo> validadores = List.of(
                sanitizador,
                validadorCpfCnpj,
                validadorFaixaMinima,
                validadorGeometria,
                validadorRegressao
        );

        orquestrador = new OrquestradorValidacao(
                validadores,
                sanitizador,
                deduplicador,
                mapeamentoRepository,
                new ObjectMapper()
        );
    }

    @Test
    @DisplayName("Deve aprovar registros quando todos os campos forem válidos")
    void deveAprovarRegistrosValidos() {
        Long fonteId = 1L;

        MapeamentoColuna mapCod = MapeamentoColuna.builder()
                .colunaOrigem("cod_imovel")
                .atributoDestino("cod_imovel")
                .destinoTipo("COLUNA")
                .obrigatorio(true)
                .build();

        MapeamentoColuna mapArea = MapeamentoColuna.builder()
                .colunaOrigem("area")
                .atributoDestino("area_ha")
                .destinoTipo("COLUNA")
                .tipoDado("numero")
                .valorMinimo(0.0)
                .obrigatorio(true)
                .build();

        when(mapeamentoRepository.findByFonteDadoId(fonteId)).thenReturn(List.of(mapCod, mapArea));

        RegistroBruto reg1 = criarRegistro(1L, 10L, "{\"cod_imovel\": \"PR-001\", \"area\": 25.5}");
        RegistroBruto reg2 = criarRegistro(2L, 10L, "{\"cod_imovel\": \"PR-002\", \"area\": 100.0}");

        ResultadoOrquestracao resultado = orquestrador.orquestrarLote(fonteId, List.of(reg1, reg2), "cod_imovel");

        assertThat(resultado.totalRecebidos()).isEqualTo(2);
        assertThat(resultado.totalAprovados()).isEqualTo(2);
        assertThat(resultado.totalRejeitados()).isEqualTo(0);
        assertThat(resultado.totalDuplicatas()).isEqualTo(0);
        assertThat(resultado.temInconsistencias()).isFalse();

        RegistroAprovadoSilver aprovado1 = resultado.aprovados().get(0);
        assertThat(aprovado1.atributosTransformados()).containsEntry("cod_imovel", "PR-001");
        assertThat(aprovado1.atributosTransformados()).containsEntry("area_ha", 25.5);
    }

    @Test
    @DisplayName("Deve rejeitar para Quarentena quando campo obrigatório for ausente ou falso nulo")
    void deveRejeitarCampoObrigatorioAusente() {
        Long fonteId = 1L;

        MapeamentoColuna mapCod = MapeamentoColuna.builder()
                .colunaOrigem("cod_imovel")
                .atributoDestino("cod_imovel")
                .destinoTipo("COLUNA")
                .obrigatorio(true)
                .build();

        when(mapeamentoRepository.findByFonteDadoId(fonteId)).thenReturn(List.of(mapCod));

        RegistroBruto regInvalido = criarRegistro(1L, 10L, "{\"cod_imovel\": \"N/A\"}");

        ResultadoOrquestracao resultado = orquestrador.orquestrarLote(fonteId, List.of(regInvalido), "cod_imovel");

        assertThat(resultado.totalAprovados()).isEqualTo(0);
        assertThat(resultado.totalRejeitados()).isEqualTo(1);
        assertThat(resultado.temInconsistencias()).isTrue();

        RegistroRejeitadoQuarentena rejeitado = resultado.rejeitados().get(0);
        assertThat(rejeitado.tipoErro()).isEqualTo("CAMPO_OBRIGATORIO_AUSENTE");
        assertThat(rejeitado.campoProblema()).isEqualTo("cod_imovel");
    }

    @Test
    @DisplayName("Deve rejeitar para Quarentena quando CPF/CNPJ for inválido")
    void deveRejeitarCpfInvalido() {
        Long fonteId = 1L;

        MapeamentoColuna mapCpf = MapeamentoColuna.builder()
                .colunaOrigem("cpf_autuado")
                .atributoDestino("cpf_cnpj")
                .destinoTipo("COLUNA")
                .validarCpfCnpj(true)
                .obrigatorio(true)
                .build();

        when(mapeamentoRepository.findByFonteDadoId(fonteId)).thenReturn(List.of(mapCpf));

        RegistroBruto reg = criarRegistro(1L, 10L, "{\"cpf_autuado\": \"111.111.111-11\"}");

        ResultadoOrquestracao resultado = orquestrador.orquestrarLote(fonteId, List.of(reg), "cpf_autuado");

        assertThat(resultado.totalRejeitados()).isEqualTo(1);
        assertThat(resultado.rejeitados().get(0).tipoErro()).isEqualTo("CPF_CNPJ_INVALIDO");
    }

    @Test
    @DisplayName("Deve deduplicar chaves repetidas e promover apenas o snapshot mais recente")
    void deveIntegrarDeduplicacaoCorretamente() {
        Long fonteId = 1L;

        MapeamentoColuna mapCod = MapeamentoColuna.builder()
                .colunaOrigem("cod_imovel")
                .atributoDestino("cod_imovel")
                .destinoTipo("COLUNA")
                .obrigatorio(true)
                .build();

        when(mapeamentoRepository.findByFonteDadoId(fonteId)).thenReturn(List.of(mapCod));

        RegistroBruto regAntigo = criarRegistro(1L, 10L, "{\"cod_imovel\": \"PR-001\", \"versao\": 1}");
        RegistroBruto regNovo = criarRegistro(2L, 20L, "{\"cod_imovel\": \"PR-001\", \"versao\": 2}");

        ResultadoOrquestracao resultado = orquestrador.orquestrarLote(fonteId, List.of(regAntigo, regNovo), "cod_imovel");

        assertThat(resultado.totalRecebidos()).isEqualTo(2);
        assertThat(resultado.totalAprovados()).isEqualTo(1);
        assertThat(resultado.totalDuplicatas()).isEqualTo(1);
        assertThat(resultado.aprovados().get(0).registroBruto()).isEqualTo(regNovo);
        assertThat(resultado.duplicatasDescartadas().get(0).registroDescartado()).isEqualTo(regAntigo);
    }

    @Test
    @DisplayName("Deve detectar regressão de valor contra o snapshot anterior")
    void deveDetectarRegressao() {
        Long fonteId = 1L;

        MapeamentoColuna mapArea = MapeamentoColuna.builder()
                .colunaOrigem("area")
                .atributoDestino("area")
                .destinoTipo("COLUNA")
                .impedirRegressao(true)
                .build();

        when(mapeamentoRepository.findByFonteDadoId(fonteId)).thenReturn(List.of(mapArea));

        RegistroBruto regAtual = criarRegistro(1L, 20L, "{\"cod_imovel\": \"PR-001\", \"area\": null}");
        Map<String, Map<String, Object>> snapshotAnterior = Map.of(
                "PR-001", Map.of("area", 150.0)
        );

        ResultadoOrquestracao resultado = orquestrador.orquestrarLote(
                fonteId,
                List.of(regAtual),
                "cod_imovel",
                snapshotAnterior
        );

        assertThat(resultado.totalRejeitados()).isEqualTo(1);
        assertThat(resultado.rejeitados().get(0).tipoErro()).isEqualTo("REGRESSAO_DE_VALOR");
    }

    @Test
    @DisplayName("Não deve inferir município ou auto de infração como tabela de domínio (lookup)")
    void naoDeveInferirMunicipioEAutoInfracaoComoDominio() {
        assertThat(OrquestradorValidacao.inferirTabelaReferenciaCanonico("municipio")).isNull();
        assertThat(OrquestradorValidacao.inferirTabelaReferenciaCanonico("cod_municipio_ibge")).isNull();
        assertThat(OrquestradorValidacao.inferirTabelaReferenciaCanonico("num_auto_infracao")).isNull();
        assertThat(OrquestradorValidacao.inferirTabelaReferenciaCanonico("auto_infracao")).isNull();
    }

    @Test
    @DisplayName("Deve inferir município como referência à entidade Silver (integridade cruzada)")
    void deveInferirMunicipioComoIntegridadeCruzada() {
        assertThat(OrquestradorValidacao.inferirReferenciaEntidadeSilverCanonico("municipio")).isEqualTo("municipio");
        assertThat(OrquestradorValidacao.inferirReferenciaEntidadeSilverCanonico("cod_municipio_ibge")).isEqualTo("municipio");
        // num_auto_infracao NAO infere mais auto_infracao (LIZARDS-50) - o campo do
        // Embargo nao e o mesmo identificador do Auto de Infracao de verdade
        // (testado contra o servico real do IAT/PR: formatos incompativeis). A
        // config explicita em controle.mapeamento_coluna (V071) e quem decide isso
        // agora, nao mais uma inferencia por nome de atributo.
        assertThat(OrquestradorValidacao.inferirReferenciaEntidadeSilverCanonico("num_auto_infracao")).isNull();
        assertThat(OrquestradorValidacao.inferirReferenciaEntidadeSilverCanonico("auto_infracao")).isNull();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RegistroBruto criarRegistro(Long id, Long loteId, String payload) {
        return RegistroBruto.builder()
                .id(id)
                .loteIngestaoId(loteId)
                .fonteDadoId(1L)
                .payload(payload)
                .dataIngestao(LocalDateTime.now())
                .build();
    }
}
