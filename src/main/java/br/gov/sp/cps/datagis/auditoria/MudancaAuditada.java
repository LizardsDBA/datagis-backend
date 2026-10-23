package br.gov.sp.cps.datagis.auditoria;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.ObjectMapper;

/**
 * Antes e depois de uma alteracao, para o log de auditoria (colunas da V082).
 *
 * <p>O servico que altera o dado chama {@link #registrar} DEPOIS de salvar com sucesso,
 * passando a "fotografia" do registro antes e depois (mapa simples, sem senha nem segredo).
 * O valor fica na propria requisicao HTTP e o {@link FiltroAuditoria} grava junto com a
 * linha do log. Fora de uma requisicao (job agendado, teste) a chamada nao faz nada.
 *
 * <p>Antes e depois iguais (ex.: salvou os perfis sem mudar nenhum) nao sao gravados: a acao
 * continua no log, so sem o antes/depois, para nao aparecer "Nenhum campo mudou".
 */
public final class MudancaAuditada {

    static final String ATRIBUTO_ANTES = MudancaAuditada.class.getName() + ".antes";
    static final String ATRIBUTO_DEPOIS = MudancaAuditada.class.getName() + ".depois";

    private static final ObjectMapper JSON = new ObjectMapper();

    private MudancaAuditada() {
    }

    /**
     * @param antes  como o registro estava ({@code null} = nao existia, cadastro novo)
     * @param depois como ficou ({@code null} = foi removido)
     */
    public static void registrar(Object antes, Object depois) {
        RequestAttributes atributos = RequestContextHolder.getRequestAttributes();
        if (atributos == null) {
            return;
        }
        String antesJson = antes == null ? null : JSON.writeValueAsString(antes);
        String depoisJson = depois == null ? null : JSON.writeValueAsString(depois);
        if (antesJson != null && antesJson.equals(depoisJson)) {
            return;
        }
        atributos.setAttribute(ATRIBUTO_ANTES, antesJson, RequestAttributes.SCOPE_REQUEST);
        atributos.setAttribute(ATRIBUTO_DEPOIS, depoisJson, RequestAttributes.SCOPE_REQUEST);
    }

    static String antes(HttpServletRequest request) {
        return request.getAttribute(ATRIBUTO_ANTES) instanceof String texto ? texto : null;
    }

    static String depois(HttpServletRequest request) {
        return request.getAttribute(ATRIBUTO_DEPOIS) instanceof String texto ? texto : null;
    }
}
