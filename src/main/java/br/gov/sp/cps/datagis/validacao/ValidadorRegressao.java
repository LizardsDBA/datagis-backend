package br.gov.sp.cps.datagis.validacao;

import org.springframework.stereotype.Component;

/**
 * 2.7. Validador de Regressão de Valor.
 *
 * <p>Responsável por:
 * <ul>
 *   <li>Identificar se um campo estava preenchido em snapshot/lote anterior e
 *       veio nulo ou vazio no lote mais recente da mesma chave natural;</li>
 *   <li>Atua quando a flag {@code impedir_regressao = true} (padrão {@code DEFAULT 1} na tabela
 *       {@code controle.mapeamento_coluna});</li>
 *   <li>Gerar o erro {@code REGRESSAO_DE_VALOR} para a Quarentena em caso de perda de informação,
 *       permitindo que o operador de dados decida sobre a aceitação ou descarte;</li>
 *   <li>Aprovar atualizações quando o novo valor estiver preenchido (atualização legítima de dado)
 *       ou quando o campo já era nulo anteriormente (permanência de estado nulo).</li>
 * </ul>
 */
@Component
public class ValidadorRegressao implements ValidadorCampo {

    public static final String ERRO_REGRESSAO = "REGRESSAO_DE_VALOR";

    @Override
    public boolean suporta(ContextoValidacaoCampo contexto) {
        return contexto != null && contexto.impedirRegressao();
    }

    @Override
    public ResultadoValidacao validar(Object valor, ContextoValidacaoCampo contexto) {
        if (contexto == null || !contexto.impedirRegressao()) {
            return ResultadoValidacao.sucesso(valor);
        }

        Object valorAnterior = contexto.valorAnterior();
        boolean atualVazio = isVazioOuNulo(valor);
        boolean anteriorPreenchido = !isVazioOuNulo(valorAnterior);

        if (atualVazio && anteriorPreenchido) {
            String nomeCampo = contexto.colunaOrigem() != null ? contexto.colunaOrigem() : "campo";
            String valorAnteriorStr = valorAnterior.toString();
            return ResultadoValidacao.erro(
                    ERRO_REGRESSAO,
                    nomeCampo,
                    null,
                    "Campo \"" + nomeCampo + "\" estava preenchido no snapshot anterior com \""
                            + valorAnteriorStr + "\" e veio nulo/vazio no lote atual"
            );
        }

        return ResultadoValidacao.sucesso(valor);
    }

    /**
     * Verifica se houve regressão de valor entre dois estados.
     */
    public boolean houveRegressao(Object valorAtual, Object valorAnterior) {
        return isVazioOuNulo(valorAtual) && !isVazioOuNulo(valorAnterior);
    }

    /**
     * Identifica se um valor é nulo, composto apenas por espaços ou literal falso nulo.
     */
    public boolean isVazioOuNulo(Object val) {
        if (val == null) {
            return true;
        }
        String str = val.toString().trim();
        return str.isEmpty()
                || "null".equalsIgnoreCase(str)
                || "n/a".equalsIgnoreCase(str)
                || "-".equals(str);
    }
}
