package br.gov.sp.cps.datagis.ingestao;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracao da fonte do CAR. A lista de municipios fica no application.properties
 * pra dar pra adicionar mais sem mexer em codigo.
 */
@Component
@ConfigurationProperties(prefix = "app.car")
@Getter
@Setter
public class CarProperties {

    private String wfsUrl;
    private String layer;
    private List<String> municipiosIbge;
}
