# Ejercicio SRE — Observabilidad con OpenTelemetry

**Autores:** Andrés Azcona · Laura Franco · Nicolás Muñoz

---

## Descripción

Implementación completa de observabilidad sobre una arquitectura de microservicios usando **OpenTelemetry**, siguiendo prácticas SRE. El sistema instrumenta dos servicios Spring Boot (`order-service` y `product-service`) con trazas distribuidas, métricas y logs correlacionados, exportando todo a través de un OTel Collector hacia Jaeger y Grafana.

---

## Arquitectura

```
Cliente HTTP
    │
    ▼
order-service (puerto 8081)
    │  valida la orden y llama a product-service
    ▼
product-service (puerto 8082)
    │  consulta el producto en base de datos
    ▼
MySQL (puerto 3306)

──────────────────────────────────────────────────────────
Plano de observabilidad:

order-service ──┐
                ├──► OTel Collector ──► Jaeger (trazas)
product-service─┘         │
                           ├──► Prometheus (métricas)
                           │         │
                           │         ▼
                           │      Grafana (dashboards)
                           │
                           └──► Debug (logs en consola del collector)
```

---

## Flujo de una orden

1. El cliente hace `POST /orders` con `{ productId, quantity }` al **order-service**.
2. El order-service abre un span manual `order.create` y llama internamente a product-service para validar si el producto existe.
3. El product-service abre un span manual `product.db.findById` y consulta MySQL.
4. Si el producto existe, la orden se registra y se incrementa la métrica custom `orders_created_total`.
5. Si no existe, el span se marca con `status=ERROR` y se registra la excepción.
6. **Todo el flujo comparte el mismo `traceId`** gracias a la propagación de contexto W3C TraceContext vía cabeceras HTTP.

---

## Stack tecnológico

| Componente | Tecnología | Puerto |
|---|---|---|
| order-service | Spring Boot 3.3.4 + Java 17 | 8081 |
| product-service | Spring Boot 3.3.4 + Java 17 | 8082 |
| Base de datos | MySQL 8 | 3306 |
| OTel Collector | otel/opentelemetry-collector-contrib | 4317, 4318, 8889 |
| Trazas | Jaeger all-in-one | 16686 |
| Métricas | Prometheus | 9090 |
| Dashboards | Grafana | 3000 |

---

## Objetivos cumplidos

| Objetivo | Implementación |
|---|---|
| Instrumentación automática | OTel Java Agent v2.5.0 — instrumenta Spring MVC, RestTemplate y JDBC sin modificar el código de negocio |
| Instrumentación manual | Spans custom: `order.create`, `order.validate` (order-service) y `product.db.findById` (product-service) |
| Propagación de contexto | `RestTemplate` registrado como `@Bean` de Spring; el agente inyecta la cabecera `traceparent` (W3C) en cada llamada HTTP — mismo `traceId` de punta a punta |
| Correlación logs-traces | Patrón de log con `%X{trace_id}` y `%X{span_id}`; el agente rellena el MDC automáticamente |
| Métricas HTTP | Automáticas via agente: `http.server.request.duration`, `http.client.request.duration` |
| Métrica custom | `orders_created_total` (con atributo `product.id`) y `product_lookups_total` (con atributo `lookup.result`: found/not_found) |
| Exportación OTLP | Configurado via variables de entorno en docker-compose; protocolo gRPC al OTel Collector |
| Collector funcionando | otel-collector-contrib recibe trazas, métricas y logs; los enruta a Jaeger y Prometheus |
| Visualización Jaeger | Trazas distribuidas con spans anidados y atributos de negocio |
| Visualización Grafana | Dashboard pre-provisionado con métricas HTTP, JVM y métricas custom |
| Manejo de errores en spans | `span.recordException(e)` + `span.setStatus(StatusCode.ERROR)` en ambos servicios |
| Soporte New Relic / Dynatrace | Secciones comentadas en `otel-collector.yaml` listas para activar con API key |

---

## Instrumentación manual — detalles clave

### order-service — OrderService.java

```java
Span span = tracer.spanBuilder("order.create")
    .setAttribute("order.product_id", request.getProductId())
    .setAttribute("order.quantity", request.getQuantity())
    .startSpan();

try (Scope scope = span.makeCurrent()) {
    // lógica de negocio...
} catch (RuntimeException e) {
    span.recordException(e);
    span.setStatus(StatusCode.ERROR, e.getMessage());
    throw e;
} finally {
    span.end();
}
```

### product-service — ProductService.java

```java
Span span = tracer.spanBuilder("product.db.findById")
    .setAttribute("db.system", "mysql")
    .setAttribute("db.operation", "SELECT")
    .setAttribute("product.id", id)
    .startSpan();
```

### Métrica custom con atributos

```java
ordersCreatedCounter.add(1,
    Attributes.builder()
        .put("product.id", String.valueOf(request.getProductId()))
        .build());
```

Los atributos permiten filtrar en Grafana por producto específico usando PromQL:
```promql
rate(otel_orders_created_total{product_id="1"}[1m])
```

---

## ¿Por qué Prometheus y no conectar Grafana directo al OTel Collector?

Esta es una decisión de arquitectura importante que vale la pena entender.

### La alternativa sin Prometheus

El OTel Collector expone un endpoint compatible con el formato de scrape de Prometheus en el puerto `8889`. Grafana puede conectarse directamente a ese endpoint usando el datasource de tipo Prometheus y realizar consultas PromQL normalmente:

```
Servicios → OTel Collector (:8889) → Grafana
```

Esto funciona y reduce un componente del stack.

### Por qué igual elegimos incluir Prometheus

| Criterio | Sin Prometheus | Con Prometheus |
|---|---|---|
| **Retención de datos** | Solo lo que el collector mantiene en memoria (~3 minutos por defecto) | Configurable: días, semanas, meses en disco |
| **Histórico** | Si el collector se reinicia, se pierden todas las métricas | Prometheus persiste en disco; los reinicios no pierden datos |
| **Alertas** | No disponible | AlertManager integrado con reglas PromQL |
| **Federación** | No | Múltiples instancias de Prometheus pueden federarse |
| **Ecosistema SRE** | Limitado | Estándar de la industria; compatible con todos los sistemas de alertas |
| **PromQL completo** | Parcial (sin funciones que requieren histórico como `increase`) | Completo |
| **Complejidad** | Menor (un contenedor menos) | Un contenedor adicional |

**Conclusión:** Para un entorno de desarrollo o demo puntual, conectar Grafana directo al collector es suficiente. En un entorno productivo o de ejercicio SRE real, Prometheus es indispensable porque la observabilidad requiere histórico, alertas y la capacidad de analizar tendencias a lo largo del tiempo — no solo el instante actual.

---

## Cómo levantar el proyecto

### Requisitos previos

- Docker Desktop corriendo
- JDK 17 instalado (Eclipse Temurin recomendado)

### Paso 1 — Compilar los servicios

```powershell
# Desde la raíz del proyecto
Set-Location ".\order-service"; .\mvnw.cmd package -DskipTests -q; Set-Location ..
Set-Location ".\product-service"; .\mvnw.cmd package -DskipTests -q; Set-Location ..
```

### Paso 2 — Levantar el stack completo

```powershell
docker-compose up --build
```

### Paso 3 — Generar tráfico de prueba (PowerShell)

```powershell
# Orden exitosa — producto 1 existe (seed data)
Invoke-WebRequest -Uri "http://localhost:8081/orders" `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"productId": 1, "quantity": 2}' | Select-Object -ExpandProperty Content

# Orden con error — producto no existe (genera span con ERROR en Jaeger)
Invoke-WebRequest -Uri "http://localhost:8081/orders" `
    -Method POST `
    -ContentType "application/json" `
    -Body '{"productId": 999, "quantity": 1}' | Select-Object -ExpandProperty Content
```

---

## URLs de los servicios

| Servicio | URL |
|---|---|
| order-service API | http://localhost:8081/orders |
| product-service API | http://localhost:8082/products/1 |
| Jaeger UI | http://localhost:16686 |
| Grafana | http://localhost:3000 (admin / admin) |
| Prometheus | http://localhost:9090 |
| OTel Collector metrics | http://localhost:8889/metrics |

---

## Cómo ver las trazas en Jaeger

1. Abrir http://localhost:16686
2. En el dropdown **Service** seleccionar `order-service`
3. Click en **Find Traces**
4. Click en cualquier traza para ver el árbol de spans:

```
order-service: POST /orders
  └─ order.create
       └─ order.validate
            └─ GET http://product-service:8082/products/1  ← propagación automática
                  └─ product-service: GET /products/{id}
                       └─ product.db.findById
```

Los spans en **rojo** corresponden a órdenes rechazadas (producto no encontrado).

---

## Correlación logs-trazas

Cada línea de log incluye el `traceId` y `spanId` del span activo en ese momento:

```
15:38:32.099  INFO [order-service,808b48331a87df586b46c165587db5d1,13bda3f02ebb5321] ...OrderService - Iniciando creación de orden
15:38:32.102  INFO [order-service,808b48331a87df586b46c165587db5d1,69d487a40ac6de35] ...ProductClient - Consultando producto en product-service
15:38:32.790  INFO [order-service,808b48331a87df586b46c165587db5d1,69d487a40ac6de35] ...ProductClient - Respuesta de product-service: status=200 OK
```

El `traceId` `808b48331a87df586b46c165587db5d1` es el mismo en todas las líneas del flujo y coincide con la traza visible en Jaeger.

---

## Habilitar New Relic o Dynatrace

Editar `otel-collector.yaml` y descomentar la sección correspondiente, luego agregar las credenciales en `docker-compose.yml`:

```yaml
# docker-compose.yml — sección otel-collector
environment:
  NEW_RELIC_LICENSE_KEY: "TU_LICENSE_KEY_AQUI"
```

```yaml
# otel-collector.yaml — exporters
otlp/newrelic:
  endpoint: otlp.nr-data.net:4317
  headers:
    api-key: ${env:NEW_RELIC_LICENSE_KEY}
```

Y agregar `otlp/newrelic` a los pipelines de trazas, métricas y logs en el mismo archivo.

---

## Estructura del proyecto

```
Ejercicio_SRE/
├── docker-compose.yml
├── otel-collector.yaml
├── prometheus.yaml
├── grafana/
│   └── provisioning/
│       ├── datasources/datasources.yaml
│       └── dashboards/
│           ├── dashboard.yaml
│           └── sre-microservices.json
├── order-service/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/example/order_service/
│       ├── OrderServiceApplication.java   ← define RestTemplate @Bean
│       ├── controller/OrderController.java
│       ├── service/OrderService.java      ← spans manuales + métrica custom
│       ├── client/ProductClient.java      ← propagación de contexto
│       └── model/OrderRequest.java
└── product-service/
    ├── Dockerfile
    ├── pom.xml
    └── src/main/java/com/example/product_service/
        ├── ProductServiceApplication.java
        ├── DataInitializer.java           ← seed data MySQL
        ├── controller/ProductController.java  ← métrica custom product_lookups
        ├── service/ProductService.java        ← spans manuales
        ├── entity/Product.java
        └── repository/ProductRepository.java
```
