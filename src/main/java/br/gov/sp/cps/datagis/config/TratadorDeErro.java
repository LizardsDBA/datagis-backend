package br.gov.sp.cps.datagis.config;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Transforma erro de regra de negocio (IllegalArgumentException) em 400, e falha de
 * ingestao (IllegalStateException - fonte externa fora do ar, resposta invalida, etc.)
 * em 502, sempre com a mensagem real no corpo. Sem isso, o Spring devolvia um 500 cru
 * sem detalhe nenhum - a tela so conseguia mostrar "O servidor respondeu HTTP 500"
 * generico, escondendo qual fonte falhou e por que (achado ao vivo tentando diagnosticar
 * a falha da fonte "Embargos ICMBio").
 */
@RestControllerAdvice
public class TratadorDeErro {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> tratarArgumentoInvalido(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("erro", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> tratarFalhaDeIngestao(IllegalStateException e) {
        String mensagem = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            mensagem = mensagem + " — causa: " + e.getCause().getMessage();
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("erro", mensagem));
    }
}
