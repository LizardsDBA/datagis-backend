package br.gov.sp.cps.datagis.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import br.gov.sp.cps.datagis.auditoria.DescricaoAcao.Acao;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

class DescricaoAcaoTest {

    @Test
    void identificaMoldeEDescricao() {
        assertThat(DescricaoAcao.identificar("POST", "/api/fontes/12/executar"))
                .contains(new Acao("/api/fontes/{id}/executar", "Executar ingestao da fonte"));
    }

    @Test
    void loteDaQuarentenaNaoEConfundidoComRegistroDaQuarentena() {
        // "/api/quarentena/lotes/45/descartar" casaria tambem com "/{entidade}/{id}/descartar"
        assertThat(DescricaoAcao.identificar("POST", "/api/quarentena/lotes/45/descartar"))
                .map(Acao::descricao).contains("Descartar lote");
        assertThat(DescricaoAcao.identificar("POST", "/api/quarentena/imovel_rural/9/descartar"))
                .map(Acao::descricao).contains("Descartar registro da quarentena");
    }

    @Test
    void metodoDiferenteNaoCasa() {
        assertThat(DescricaoAcao.identificar("DELETE", "/api/fontes/12/executar")).isEmpty();
        assertThat(DescricaoAcao.identificar("POST", "/api/rota/inexistente")).isEmpty();
    }

    /**
     * Rede de seguranca: todo endpoint que altera dado nos controllers reais precisa ter
     * descricao, senao o log de auditoria mostraria so "POST /api/...". Endpoint novo ->
     * linha nova em DescricaoAcao.
     */
    @Test
    void todoEndpointQueAlteraDadoTemDescricao() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<String> semDescricao = new ArrayList<>();
        int verificados = 0;
        for (BeanDefinition bd : scanner.findCandidateComponents("br.gov.sp.cps.datagis")) {
            Class<?> controller = Class.forName(bd.getBeanClassName());
            if (controller.getName().contains("Test")) {
                continue;
            }
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            String prefixo = base != null && base.path().length > 0 ? base.path()[0] : "";
            for (Method metodo : controller.getDeclaredMethods()) {
                RequestMapping mapeamento = AnnotatedElementUtils.findMergedAnnotation(metodo, RequestMapping.class);
                if (mapeamento == null) {
                    continue;
                }
                String[] caminhos = mapeamento.path().length > 0 ? mapeamento.path() : new String[] {""};
                for (RequestMethod verbo : mapeamento.method()) {
                    if (verbo == RequestMethod.GET) {
                        continue;
                    }
                    for (String caminho : caminhos) {
                        String rota = (prefixo + caminho).replaceAll("\\{[^}]+}", "1");
                        verificados++;
                        if (DescricaoAcao.identificar(verbo.name(), rota).isEmpty()) {
                            semDescricao.add(verbo + " " + prefixo + caminho);
                        }
                    }
                }
            }
        }
        assertThat(verificados).as("o scanner precisa achar os endpoints de escrita").isGreaterThan(15);
        assertThat(semDescricao).as("endpoints que alteram dado sem descricao em DescricaoAcao").isEmpty();
    }
}
