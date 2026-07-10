package com.toyeah.dispatching;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestTemplate;


@EnableScheduling
@SpringBootApplication(scanBasePackages = {"com.toyeah"})
public class RtcApplication {

	public static void main(String[] args) {
		SpringApplication.run(RtcApplication.class, args);
	}

	@Bean
	public RestTemplate restTemplate() {
		return new RestTemplate();
	}

}
