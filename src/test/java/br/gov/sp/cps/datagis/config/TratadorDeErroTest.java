package br.gov.sp.cps.datagis.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Sem isso, uma falha de ingestão (fonte externa fora do ar, HTTP 500 do lado de lá,
 * etc.) virava um 500 cru do Spring sem nenhum detalhe - a tela só mostrava "O servidor
 * respondeu HTTP 500", sem dizer qual fonte nem por quê (achado tentando diagnosticar a
 * falha real da fonte "Embargos ICMBio").
 */
class TratadorDeErroTest {

    private final TratadorDeErro tratador = new TratadorDeErro();

    @Test
    void argumentoInvalidoViraQuatrocentos() {
        ResponseEntity<?> resposta = tratador.tratarArgumentoInvalido(new IllegalArgumentException("tipo inválido"));
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resposta.getBody()).isEqualTo(java.util.Map.of("erro", "tipo inválido"));
    }

    @Test
    void falhaDeIngestaoViraQuinhentosEDois() {
        ResponseEntity<?> resposta = tratador.tratarFalhaDeIngestao(new IllegalStateException("fonte fora do ar"));
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(resposta.getBody()).isEqualTo(java.util.Map.of("erro", "fonte fora do ar"));
    }

    @Test
    void falhaDeIngestaoIncluiMensagemDaCausaQuandoExistir() {
        RuntimeException causa = new RuntimeException("curl (22): HTTP 500");
        ResponseEntity<?> resposta = tratador.tratarFalhaDeIngestao(new IllegalStateException("falhou ao buscar página", causa));

        @SuppressWarnings("unchecked")
        java.util.Map<String, String> corpo = (java.util.Map<String, String>) resposta.getBody();
        assertThat(corpo.get("erro")).contains("falhou ao buscar página").contains("curl (22): HTTP 500");
    }
}
