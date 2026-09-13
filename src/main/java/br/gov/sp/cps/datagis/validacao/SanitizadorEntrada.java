package br.gov.sp.cps.datagis.validacao;

import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 2.1. Sanitizador de Entrada & Falsos Nulos.
 *
 * <p>Responsável por:
 * <ul>
 *   <li>Interceptar o valor antes de qualquer outra regra da esteira;</li>
 *   <li>Converter para {@code null} real qualquer ocorrência de strings falsas nulas:
 *       {@code "NULL"}, {@code "null"}, {@code "N/A"}, {@code "n/a"}, {@code "-"},
 *       {@code ""} ou compostas apenas por espaços em branco;</li>
 *   <li>Gerar erro imediato de {@code CAMPO_OBRIGATORIO_AUSENTE} caso a coluna possua
 *       {@code obrigatorio = true} e o valor sanitizado resulte em {@code null}.</li>
 * </ul>
 */
@Component
public class SanitizadorEntrada implements ValidadorCampo {

    private static final String ERRO_CAMPO_OBRIGATORIO = "CAMPO_OBRIGATORIO_AUSENTE";

    private static final Set<String> FALSOS_NULOS = Set.of(
            "null",
            "n/a",
            "-"
    );

    @Override
    public boolean suporta(ContextoValidacaoCampo contexto) {
        // O sanitizador atua em todos os campos mapeados
        return true;
    }

    @Override
    public ResultadoValidacao validar(Object valor, ContextoValidacaoCampo contexto) {
        Object valorSanitizado = sanitizarObjeto(valor);

        if (valorSanitizado == null && contexto != null && contexto.obrigatorio()) {
            String nomeCampo = contexto.colunaOrigem() != null ? contexto.colunaOrigem() : "campo";
            String valorRecebido = valor != null ? valor.toString() : null;
            return ResultadoValidacao.erro(
                    ERRO_CAMPO_OBRIGATORIO,
                    nomeCampo,
                    valorRecebido,
                    "Campo obrigatório \"" + nomeCampo + "\" ausente ou preenchido com valor nulo/em branco"
            );
        }

        return ResultadoValidacao.sucesso(valorSanitizado);
    }

    /**
     * Sanitiza uma string removendo espaços nas extremidades e convertendo
     * literais falsos nulos em {@code null}.
     */
    public String sanitizar(String valor) {
        if (valor == null) {
            return null;
        }
        String limpo = valor.trim();
        if (limpo.isEmpty()) {
            return null;
        }
        if (FALSOS_NULOS.contains(limpo.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return limpo;
    }

    /**
     * Sanitiza qualquer objeto. Se for {@link String}, aplica {@link #sanitizar(String)}.
     * Caso contrário, preserva o objeto intacto.
     */
    public Object sanitizarObjeto(Object valor) {
        if (valor == null) {
            return null;
        }
        if (valor instanceof String str) {
            return sanitizar(str);
        }
        return valor;
    }
}
