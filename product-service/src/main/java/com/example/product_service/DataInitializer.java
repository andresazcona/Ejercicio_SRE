package com.example.product_service;

import com.example.product_service.entity.Product;
import com.example.product_service.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final ProductRepository repository;

    public DataInitializer(ProductRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedProducts() {
        if (repository.count() == 0) {
            repository.save(product(1L, "Laptop Gaming", 1299.99));
            repository.save(product(2L, "Mouse Inalámbrico", 49.99));
            repository.save(product(3L, "Teclado Mecánico", 89.99));
            repository.save(product(4L, "Monitor 4K", 599.99));
            log.info("Productos de prueba insertados en base de datos");
        }
    }

    private Product product(Long id, String name, Double price) {
        Product p = new Product();
        p.setId(id);
        p.setName(name);
        p.setPrice(price);
        return p;
    }
}
