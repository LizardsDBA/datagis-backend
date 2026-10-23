package br.gov.sp.cps.datagis.auditoria;

import jakarta.servlet.http.HttpServletRequest;

/** So pra testes de outros pacotes lerem o que {@link MudancaAuditada} guardou na requisicao. */
public final class MudancaAuditadaAcesso {

    private MudancaAuditadaAcesso() {
    }

    public static String antes(HttpServletRequest request) {
        return MudancaAuditada.antes(request);
    }

    public static String depois(HttpServletRequest request) {
        return MudancaAuditada.depois(request);
    }
}
