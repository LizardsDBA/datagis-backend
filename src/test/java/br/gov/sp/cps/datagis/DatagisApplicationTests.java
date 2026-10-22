package br.gov.sp.cps.datagis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: verifica que o ApplicationContext sobe sem erros.
 *
 * Todas as propriedades necessárias são declaradas diretamente aqui para
 * que o teste seja auto-contido e não dependa de variáveis de ambiente nem
 * do perfil "dev". O datasource aponta para o Oracle local exposto pelo
 * docker-compose em localhost:1521.
 *
 * Requer que o container Oracle esteja rodando (docker compose up oracle).
 */
@SpringBootTest(properties = {
		// DataSource — Oracle local do docker-compose
		"spring.datasource.url=jdbc:oracle:thin:@localhost:1521/FREEPDB1",
		"spring.datasource.username=CONTROLE",
		"spring.datasource.password=DevLocal_2025!",
		"spring.datasource.driver-class-name=oracle.jdbc.OracleDriver",
		// Dialect explícito evita lookup de metadados JDBC antes da conexão
		"spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.OracleDialect",
		// Desabilita migrações, DDL e serviços pesados — não são necessários
		"spring.flyway.enabled=false",
		"spring.jpa.hibernate.ddl-auto=none",
		"spring.quartz.auto-startup=false",
		"spring.batch.job.enabled=false"
})
class DatagisApplicationTests {

	@Test
	void contextLoads() {
	}
}
