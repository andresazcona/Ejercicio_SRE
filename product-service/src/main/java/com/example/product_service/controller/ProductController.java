package com.example.product_service.controller;

import com.example.product_service.entity.Product;
import com.example.product_service.service.ProductService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/products")
public class ProductController {

    private static final Logger log = LoggerFactory.getLogger(ProductController.class);

    private final ProductService service;

    // Métrica custom: total de consultas por producto con resultado (found/not_found)
    private final LongCounter productLookupCounter;

    public ProductController(ProductService service) {
        this.service = service;

        Meter meter = GlobalOpenTelemetry.getMeter("product-service");
        this.productLookupCounter = meter
                .counterBuilder("product_lookups")
                .setDescription("Total de consultas de producto por ID y resultado")
                .setUnit("{lookup}")
                .build();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        log.info("Recibida solicitud GET /products/{}", id);

        return service.getProduct(id)
                .map(product -> {
                    productLookupCounter.add(1,
                            Attributes.builder()
                                    .put("product.id", String.valueOf(id))
                                    .put("lookup.result", "found")
                                    .build());
                    return ResponseEntity.ok(product);
                })
                .orElseGet(() -> {
                    productLookupCounter.add(1,
                            Attributes.builder()
                                    .put("product.id", String.valueOf(id))
                                    .put("lookup.result", "not_found")
                                    .build());
                    log.warn("Producto no encontrado id={}", id);
                    return ResponseEntity.<Product>notFound().build();
                });
    }
}
