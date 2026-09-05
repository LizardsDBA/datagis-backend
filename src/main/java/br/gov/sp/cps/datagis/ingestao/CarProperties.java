package br.gov.sp.cps.datagis.ingestao;

import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuração da fonte CAR/SICAR (CLAUDE.md §5.6). O "circuito" de municípios é
 * config, não hard-coded — expandir a lista depois é editar o application.properties,
 * não o código.
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
