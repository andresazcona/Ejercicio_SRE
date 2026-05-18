package com.example.order_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.client.RestTemplate;

@SpringBootApplication
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }

    // Bean para que el OTel Agent auto-instrumente RestTemplate e inyecte
    // las cabeceras de propagación de contexto (traceparent, tracestate)
    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
