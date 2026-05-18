package com.example.product_service.service;

import com.example.product_service.entity.Product;
import com.example.product_service.repository.ProductRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    private final ProductRepository repository;
    private final Tracer tracer;

    public ProductService(ProductRepository repository) {
        this.repository = repository;
        this.tracer = GlobalOpenTelemetry.getTracer("product-service", "1.0.0");
    }

    public Optional<Product> getProduct(Long id) {
        // Span manual que cubre la consulta a base de datos
        Span span = tracer.spanBuilder("product.db.findById")
                .setAttribute("db.system", "mysql")
                .setAttribute("db.operation", "SELECT")
                .setAttribute("product.id", id)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            log.info("Consultando producto en base de datos id={}", id);
            Optional<Product> result = repository.findById(id);

            if (result.isPresent()) {
                span.setAttribute("product.found", true);
                span.setAttribute("product.name", result.get().getName());
                span.setAttribute("product.price", result.get().getPrice());
                log.info("Producto encontrado id={} name={}", id, result.get().getName());
            } else {
                span.setAttribute("product.found", false);
                span.setStatus(StatusCode.ERROR, "Producto no encontrado id=" + id);
                log.warn("Producto no encontrado id={}", id);
            }

            return result;

        } catch (Exception e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR, e.getMessage());
            log.error("Error al consultar producto id={} error={}", id, e.getMessage());
            throw e;
        } finally {
            span.end();
        }
    }
}
