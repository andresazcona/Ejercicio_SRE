package com.example.order_service.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class ProductClient {

    private static final Logger log = LoggerFactory.getLogger(ProductClient.class);

    // URL configurable via application.properties o env var SERVICES_PRODUCT_URL
    @Value("${services.product.url:http://product-service:8082}")
    private String productServiceUrl;

    // Bean inyectado (definido en OrderServiceApplication) para que el OTel Agent
    // lo instrumente automáticamente y propague el TraceContext en la cabecera HTTP
    private final RestTemplate restTemplate;

    public ProductClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean productExists(Long productId) {
        String url = productServiceUrl + "/products/" + productId;
        log.info("Consultando producto en product-service: url={}", url);
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            boolean found = response.getStatusCode().is2xxSuccessful();
            log.info("Respuesta de product-service: status={} found={}", response.getStatusCode(), found);
            return found;
        } catch (Exception e) {
            log.error("Error al consultar product-service: url={} error={}", url, e.getMessage());
            return false;
        }
    }
}
