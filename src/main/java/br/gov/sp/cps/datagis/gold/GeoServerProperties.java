package br.gov.sp.cps.datagis.gold;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Propriedades {@code app.geoserver.*} (ver application.properties).
 *
 * @param url     endereco do GeoServer visto pelo backend (no compose,
 *                {@code http://geoserver:8080/geoserver})
 * @param timeout tempo maximo de uma chamada encaminhada
 */
@ConfigurationProperties("app.geoserver")
public record GeoServerProperties(String url, Duration timeout) {
}
