package br.gov.sp.cps.datagis;

import org.springframework.boot.SpringApplication;

public class TestDatagisApplication {

	public static void main(String[] args) {
		SpringApplication.from(DatagisApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
