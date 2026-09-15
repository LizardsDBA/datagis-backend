package br.gov.sp.cps.datagis.validacao;

import org.springframework.stereotype.Component;

/**
 * 2.5. Validador de Faixa Mínima (Limite Numérico Inferior).
 *
 * <p>Responsável por:
 * <ul>
 *   <li>Verificar se o valor recebido no payload é maior ou igual ao limite numérico
 *       inferior parametrizado em {@code controle.mapeamento_coluna.valor_minimo} (ex.: {@code area_ha > 0});</li>
 *   <li>Converter de forma resiliente representações numéricas (tipos {@link Number}, strings com
 *       ponto decimal internacional ou vírgula brasileira, remoção de separadores de milhar e símbolos de moeda);</li>
 *   <li>Retornar o erro {@code TIPO_DE_DADO_INVALIDO} caso o valor não seja conversível para número;</li>
 *   <li>Retornar o erro {@code VALOR_ABAIXO_DO_MINIMO} caso o número seja estritamente menor que o piso configurado;</li>
 *   <li>Retornar o número tratado ({@link Double}) em {@code valorTransformado} em caso de aprovação.</li>
 * </ul>
 *
 * <p>Valores {@code null} ou vazios são repassados como {@code sucesso(null)}
 * (a obrigatoriedade é responsabilidade do {@link SanitizadorEntrada}).
 */
@Component
public class ValidadorFaixaMinima implements ValidadorCampo {

    public static final String ERRO_ABAIXO_MINIMO = "VALOR_ABAIXO_DO_MINIMO";
    public static final String ERRO_TIPO_INVALIDO = "TIPO_DE_DADO_INVALIDO";

    @Override
    public boolean suporta(ContextoValidacaoCampo contexto) {
        return contexto != null && contexto.valorMinimo() != null;
    }

    @Override
    public ResultadoValidacao validar(Object valor, ContextoValidacaoCampo contexto) {
        if (valor == null) {
            // Valor nulo é responsabilidade do SanitizadorEntrada (se obrigatorio)
            return ResultadoValidacao.sucesso(null);
        }

        if (contexto == null || contexto.valorMinimo() == null) {
            return ResultadoValidacao.sucesso(valor);
        }

        String valorOriginal = valor.toString();
        if (valorOriginal.trim().isEmpty()) {
            return ResultadoValidacao.sucesso(null);
        }

        Double valorNumerico = converterParaDouble(valor);
        String nomeCampo = contexto.colunaOrigem() != null ? contexto.colunaOrigem() : "campo";

        if (valorNumerico == null) {
            return ResultadoValidacao.erro(
                    ERRO_TIPO_INVALIDO,
                    nomeCampo,
                    valorOriginal,
                    "Valor \"" + valorOriginal + "\" não é um número válido para validação de faixa mínima"
            );
        }

        double minimo = contexto.valorMinimo();
        if (valorNumerico < minimo) {
            return ResultadoValidacao.erro(
                    ERRO_ABAIXO_MINIMO,
                    nomeCampo,
                    valorOriginal,
                    "Valor " + valorOriginal + " é inferior ao mínimo permitido de " + minimo
            );
        }

        return ResultadoValidacao.sucesso(valorNumerico);
    }

    /**
     * Converte com tolerância um objeto qualquer ({@link Number} ou {@link String}) para {@link Double}.
     * Retorna {@code null} se o valor for nulo, vazio ou impossível de converter para número.
     */
    public Double converterParaDouble(Object valor) {
        if (valor == null) {
            return null;
        }

        if (valor instanceof Number num) {
            return num.doubleValue();
        }

        String str = valor.toString().trim();
        if (str.isEmpty()) {
            return null;
        }

        try {
            String normalizado = normalizarStringNumerica(str);
            return Double.parseDouble(normalizado);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Normaliza formatos numéricos brasileiros e internacionais:
     * <ul>
     *   <li>{@code "1.234,56"} -> {@code "1234.56"}</li>
     *   <li>{@code "1,234.56"} -> {@code "1234.56"}</li>
     *   <li>{@code "15,5"} -> {@code "15.5"}</li>
     *   <li>{@code "R$ 100,00"} -> {@code "100.00"}</li>
     * </ul>
     */
    private String normalizarStringNumerica(String str) {
        // Remove símbolos de moeda, letras e espaços mantendo dígitos, sinais, ponto e vírgula
        String limpo = str.replaceAll("[R$\\s]", "");

        int ultimoPonto = limpo.lastIndexOf('.');
        int ultimaVirgula = limpo.lastIndexOf(',');

        if (ultimoPonto != -1 && ultimaVirgula != -1) {
            if (ultimaVirgula > ultimoPonto) {
                // Formato brasileiro: 1.234,56 -> remove pontos de milhar e troca vírgula por ponto decimal
                limpo = limpo.replace(".", "").replace(',', '.');
            } else {
                // Formato internacional: 1,234.56 -> remove vírgulas de milhar
                limpo = limpo.replace(",", "");
            }
        } else if (ultimaVirgula != -1) {
            // Apenas vírgula decimal: 15,5 -> 15.5
            limpo = limpo.replace(',', '.');
        }

        return limpo;
    }
}
