package com.example.order_service.service;

import com.example.order_service.client.ProductClient;
import com.example.order_service.model.OrderRequest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final ProductClient productClient;
    private final Tracer tracer;
    private final LongCounter ordersCreatedCounter;

    public OrderService(ProductClient productClient) {
        this.productClient = productClient;

        // Instrumentación manual: Tracer para spans custom
        this.tracer = GlobalOpenTelemetry.getTracer("order-service", "1.0.0");

        // Instrumentación manual: métrica custom "orders_created_total"
        Meter meter = GlobalOpenTelemetry.getMeter("order-service");
        this.ordersCreatedCounter = meter
                .counterBuilder("orders_created")
                .setDescription("Total de pedidos creados exitosamente")
                .setUnit("{order}")
                .build();
    }

    public String createOrder(OrderRequest request) {
        // Span manual que cubre toda la lógica de negocio de la orden
        Span span = tracer.spanBuilder("order.create")
                .setAttribute("order.product_id", request.getProductId())
                .setAttribute("order.quantity", request.getQuantity() != null ? request.getQuantity() : 0)
                .startSpan();

        try (Scope scope = span.makeCurrent()) {
            log.info("Iniciando creación de orden para producto={} cantidad={}",
                    request.getProductId(), request.getQuantity());

            // Validación de negocio con sub-span
            Span validationSpan = tracer.spanBuilder("order.validate")
                    .setAttribute("validation.product_id", request.getProductId())
                    .startSpan();

            boolean exists;
            try (Scope validationScope = validationSpan.makeCurrent()) {
                exists = productClient.productExists(request.getProductId());
                validationSpan.setAttribute("validation.product_exists", exists);
                log.info("Validación de producto={} resultado={}", request.getProductId(), exists);
            } finally {
                validationSpan.end();
            }

            if (!exists) {
                String msg = "Producto no existe: id=" + request.getProductId();
                span.setStatus(StatusCode.ERROR, msg);
                span.setAttribute("order.status", "rejected");
                log.warn("Orden rechazada - {}", msg);
                throw new RuntimeException(msg);
            }

            // Métrica custom: incrementar con atributos para poder filtrar en Grafana
            ordersCreatedCounter.add(1,
                    Attributes.builder()
                            .put("product.id", String.valueOf(request.getProductId()))
                            .build());

            span.setAttribute("order.status", "created");
            span.setStatus(StatusCode.OK);
            log.info("Orden creada exitosamente para producto={}", request.getProductId());
            return "Pedido creado correctamente para producto " + request.getProductId();

        } catch (RuntimeException e) {
            span.recordException(e);
            if (span.getSpanContext().isValid()) {
                span.setStatus(StatusCode.ERROR, e.getMessage());
            }
            throw e;
        } finally {
            span.end();
        }
    }
}
