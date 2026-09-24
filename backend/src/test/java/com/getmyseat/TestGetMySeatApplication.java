package com.getmyseat;

import org.springframework.boot.SpringApplication;

public class TestGetMySeatApplication {

	public static void main(String[] args) {
		SpringApplication.from(GetMySeatApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
