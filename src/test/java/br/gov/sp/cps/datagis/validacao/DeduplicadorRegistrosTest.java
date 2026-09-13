package br.gov.sp.cps.datagis.validacao;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeduplicadorRegistrosTest {

    private final DeduplicadorRegistros deduplicador = new DeduplicadorRegistros();

    @Test
    @DisplayName("Deve manter todos os registros quando não houver duplicatas de chave natural")
    void deveManterRegistrosSemDuplicatas() {
        RegistroBruto r1 = criarRegistro(1L, 10L, "{\"cod_imovel\": \"PR-001\"}");
        RegistroBruto r2 = criarRegistro(2L, 10L, "{\"cod_imovel\": \"PR-002\"}");
        RegistroBruto r3 = criarRegistro(3L, 10L, "{\"cod_imovel\": \"PR-003\"}");

        var resultado = deduplicador.deduplicar(List.of(r1, r2, r3), "cod_imovel");

        assertThat(resultado.totalRecebidos()).isEqualTo(3);
        assertThat(resultado.totalPromovidos()).isEqualTo(3);
        assertThat(resultado.totalDescartados()).isEqualTo(0);
        assertThat(resultado.houveDuplicatas()).isFalse();
        assertThat(resultado.registrosPromovidos()).containsExactly(r1, r2, r3);
    }

    @Test
    @DisplayName("Deve selecionar o registro do lote mais recente quando houver duplicatas entre lotes diferentes")
    void deveSelecionarLoteMaisRecente() {
        // r1 do lote 10 vs r2 do lote 20 (ambos com mesma chave PR-001)
        RegistroBruto r1 = criarRegistro(1L, 10L, "{\"cod_imovel\": \"PR-001\", \"area\": 20.0}");
        RegistroBruto r2 = criarRegistro(2L, 20L, "{\"cod_imovel\": \"PR-001\", \"area\": 25.0}");

        var resultado = deduplicador.deduplicar(List.of(r1, r2), "cod_imovel");

        assertThat(resultado.totalRecebidos()).isEqualTo(2);
        assertThat(resultado.totalPromovidos()).isEqualTo(1);
        assertThat(resultado.totalDescartados()).isEqualTo(1);
        assertThat(resultado.registrosPromovidos()).containsExactly(r2);

        var descartada = resultado.duplicatasDescartadas().get(0);
        assertThat(descartada.chaveNatural()).isEqualTo("PR-001");
        assertThat(descartada.registroDescartado()).isEqualTo(r1);
        assertThat(descartada.registroVencedor()).isEqualTo(r2);
    }

    @Test
    @DisplayName("Deve selecionar o registro mais recente dentro do mesmo lote (maior id / data)")
    void deveSelecionarMaisRecenteMesmoLote() {
        RegistroBruto r1 = criarRegistroComData(1L, 10L, "{\"numero_termo\": \"TAD-100\"}", LocalDateTime.of(2026, 9, 1, 10, 0));
        RegistroBruto r2 = criarRegistroComData(2L, 10L, "{\"numero_termo\": \"TAD-100\"}", LocalDateTime.of(2026, 9, 1, 10, 30));

        var resultado = deduplicador.deduplicar(List.of(r1, r2), "numero_termo");

        assertThat(resultado.totalPromovidos()).isEqualTo(1);
        assertThat(resultado.registrosPromovidos()).containsExactly(r2);
    }

    @Test
    @DisplayName("Deve tratar múltiplas duplicatas (3+ ocorrências) selecionando a mais nova e descartando o restante")
    void deveTratarMultiplasDuplicatas() {
        RegistroBruto r1 = criarRegistro(1L, 1L, "{\"cod\": \"KEY-1\"}");
        RegistroBruto r2 = criarRegistro(2L, 2L, "{\"cod\": \"KEY-1\"}");
        RegistroBruto r3 = criarRegistro(3L, 3L, "{\"cod\": \"KEY-1\"}");

        var resultado = deduplicador.deduplicar(List.of(r1, r2, r3), "cod");

        assertThat(resultado.totalPromovidos()).isEqualTo(1);
        assertThat(resultado.registrosPromovidos()).containsExactly(r3);
        assertThat(resultado.totalDescartados()).isEqualTo(2);
    }

    @Test
    @DisplayName("Regra do CAR: registro AT (Ativo) deve ter precedência sobre CA (Cancelado) dentro do mesmo lote")
    void devePriorizarAtivoNoCar() {
        // r1 é Ativo (id menor) e r2 é Cancelado histórico (id maior) no mesmo lote
        RegistroBruto r1 = criarRegistro(10L, 1L, "{\"cod_imovel\": \"PR-CAR-01\", \"situacao_imovel\": \"AT\"}");
        RegistroBruto r2 = criarRegistro(20L, 1L, "{\"cod_imovel\": \"PR-CAR-01\", \"situacao_imovel\": \"CA\"}");

        var resultado = deduplicador.deduplicar(List.of(r2, r1), "cod_imovel");

        assertThat(resultado.totalPromovidos()).isEqualTo(1);
        assertThat(resultado.registrosPromovidos()).containsExactly(r1);
        assertThat(resultado.duplicatasDescartadas().get(0).registroDescartado()).isEqualTo(r2);
    }

    @Test
    @DisplayName("Deve preservar registros com chaves ausentes ou nulas sem colisão")
    void devePreservarRegistrosSemChave() {
        RegistroBruto r1 = criarRegistro(1L, 10L, "{}");
        RegistroBruto r2 = criarRegistro(2L, 10L, "{\"outro\": 123}");

        var resultado = deduplicador.deduplicar(List.of(r1, r2), "cod_imovel");

        assertThat(resultado.totalPromovidos()).isEqualTo(2);
        assertThat(resultado.totalDescartados()).isEqualTo(0);
    }

    @Test
    @DisplayName("Deve tratar coleções nulas ou vazias de forma segura")
    void deveTratarColecaoVazia() {
        assertThat(deduplicador.deduplicar(null, "chave").totalPromovidos()).isEqualTo(0);
        assertThat(deduplicador.deduplicar(List.of(), "chave").totalPromovidos()).isEqualTo(0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private RegistroBruto criarRegistro(Long id, Long loteId, String payload) {
        return criarRegistroComData(id, loteId, payload, LocalDateTime.now());
    }

    private RegistroBruto criarRegistroComData(Long id, Long loteId, String payload, LocalDateTime data) {
        return RegistroBruto.builder()
                .id(id)
                .loteIngestaoId(loteId)
                .fonteDadoId(1L)
                .payload(payload)
                .dataIngestao(data)
                .build();
    }
}
