package br.gov.sp.cps.datagis.config;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Lançada quando uma requisição tenta disparar um processamento pesado
 * (ingestão, transição Silver ou migração Gold) enquanto já existe outra
 * execução ativa para o mesmo recurso.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class OperacaoEmAndamentoException extends RuntimeException {

    public OperacaoEmAndamentoException(String mensagem) {
        super(mensagem);
    }
}
