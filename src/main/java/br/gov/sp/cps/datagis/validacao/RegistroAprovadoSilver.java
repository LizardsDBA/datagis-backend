package br.gov.sp.cps.datagis.validacao;

import br.gov.sp.cps.datagis.bronze.RegistroBruto;
import java.util.Map;

/**
 * Representa um registro que passou por todas as regras de validação
 * e sanitização com sucesso, pronto para ser promovido à camada Silver.
 */
public record RegistroAprovadoSilver(
        RegistroBruto registroBruto,
        Map<String, Object> atributosTransformados,
        String geometriaWkt
) {}
