package br.gov.sp.cps.datagis.gold;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints da tela de consulta de indicadores (LIZARDS-34).
 *
 * <p>
 * Ficam sob {@code /api/indicadores}, entao ja caem na regra do SecurityConfig
 * que exige
 * {@code indicador:visualizar} para GET nesse prefixo — nao precisa de regra
 * nova.
 */
@RestController
@RequestMapping("/api/indicadores")
public class ConsultaIndicadoresController {

    private final ConsultaIndicadoresService service;

    public ConsultaIndicadoresController(ConsultaIndicadoresService service) {
        this.service = service;
    }

    @GetMapping("/imoveis")
    public List<ConsultaImovelResponse> listarPorImovel() {
        return service.listarPorImovel();
    }

    @GetMapping("/municipios")
    public List<ConsultaMunicipioResponse> listarPorMunicipio() {
        return service.listarPorMunicipio();
    }
}