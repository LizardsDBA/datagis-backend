package br.gov.sp.cps.datagis.gold;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caminho do mapa de cobertura ate o GeoServer (LIZARDS-35): desenho das camadas
 * (WMS) e consultas de intersecao (WFS). Fica sob {@code /api/indicadores}, entao cai
 * na regra existente de {@code indicador:visualizar} - nao precisou de regra nova no
 * SecurityConfig. Leitura (GET) nao entra no log de auditoria.
 */
@RestController
@RequestMapping("/api/indicadores/geoserver")
public class GeoServerProxyController {

    private final GeoServerProxyService service;

    public GeoServerProxyController(GeoServerProxyService service) {
        this.service = service;
    }

    @GetMapping("/{servico}")
    public ResponseEntity<byte[]> encaminhar(@PathVariable String servico,
                                             @RequestParam MultiValueMap<String, String> parametros) {
        return service.encaminhar(servico, parametros);
    }
}
