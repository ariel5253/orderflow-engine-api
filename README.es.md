# orderflow-engine-api

[English](README.md)

Motor de cálculo de pedidos y confirmación idempotente para un marketplace B2B de bebidas en varios países. Java 21 · Spring Boot · PostgreSQL.

> **Estado:** la especificación está completa y la implementación está en curso. El estado verificado de cada tarea está en `handoff/HANDOFF.md` del repositorio `orderflow-engine-docs` (en inglés).

## Cómo ejecutarlo

| Requisito | Versión |
|-----------|---------|
| JDK | 21 |
| Docker | en ejecución, porque las pruebas levantan PostgreSQL con Testcontainers |

```bash
./mvnw verify
```

Este **comando único** compila el servicio y ejecuta todas sus pruebas. También exige el umbral de cobertura (≥ 90% en `pricing`).

Para ejecutar el servicio con su propio PostgreSQL, solo desde este repositorio:

```bash
docker compose up --build
```

Salud: `http://localhost:8080/actuator/health`. La configuración llega por variables de entorno. `.env.example` lista cada clave con un comentario. Cópialo a `.env` solo para cambiar un valor. `.env` nunca se versiona.

Las pruebas de mutación son opcionales y más lentas: `./mvnw -Pmutation verify` ejecuta PIT sobre el dominio de precios y exige que se detecte al menos el 80% de los fallos inyectados. Mientras `pricing.domain` no tenga clases, PIT detiene el build con "No mutations found", a propósito, para que un filtro de clases equivocado nunca pase inadvertido.

## Orden de las palancas

| Paso | Palanca | Por qué en esta posición |
|------|---------|--------------------------|
| 1 | Elegibilidad de obsequios, sobre cantidades pagadas | El obsequio lo activa lo que se compra y nunca reduce lo que se paga por producto |
| 2 | Combos | El precio negociado del combo consume primero sus unidades, para que no reciban además descuento por escala |
| 3 | Descuento por escala, por tramos marginales, sobre las unidades que no consumió un combo | El incentivo genérico por volumen aplica solo a lo que el combo no tarifó |
| 4 | Neto de línea = bruto − descuentos | Los descuentos comerciales se liquidan antes del impuesto |
| 5 | Base gravable (neto, más el valor de referencia del obsequio si el país lo grava) | El impuesto va sobre la base después de los descuentos comerciales |
| 6 | Impuesto por línea, con la tasa de cada SKU | Las tasas son por SKU |
| 7 | Total = Σ neto + impuestos | Necesita todos los pasos anteriores |
| 8 | Verificación de cupo | Necesita el total final. Es informativa al cotizar y obligatoria al confirmar |

**Tramos de escala.** El enunciado dice que las escalas son "acumulativas por tramos". Lo leemos como tramos marginales: cada porcentaje aplica solo a las unidades dentro de su tramo. Ejemplo (golden test 1): 25 unidades a 2.500, con 10–19 → 5% y 20+ → 10%, dan 1.250 + 1.500 = **2.750** de descuento.

Un combo aparece como **una sola** entrada de descuento (`COMBO:<id>`). Sus productos nunca muestran descuentos sueltos. Reglas completas: SPEC §3 en el repositorio `orderflow-engine-docs` (en inglés).

## Política de redondeo

- **Tipo:** `Money` envuelve un `BigDecimal` y una política de moneda. Una prueba de arquitectura prohíbe `float` y `double` en el dominio.
- **Escala y modo** salen de los datos del país. COP tiene escala 0, USD escala 2, y el modo es `HALF_UP`. Nunca están fijos en el código ni se toman de `java.util.Currency`.
- **Dónde se redondea: solo por línea.**
  - Cada componente de descuento se redondea por línea. Un combo se reparte entre sus líneas por mayor residuo, así que las partes suman exacto.
  - El impuesto se redondea por línea.
  - Los totales son sumas de líneas ya redondeadas y **nunca se vuelven a redondear**.
- **Por qué por línea:** la factura electrónica lleva el impuesto por línea, así que el pedido y su factura siempre coinciden.

## Modelo de consistencia

- **Cotización = confirmación.** Las líneas y descuentos de la cotización solo se insertan. El pedido referencia la cotización y no guarda ningún monto, así que cada cifra existe una sola vez. Nada se copia ni se recalcula, por lo que un cambio posterior de precios o palancas no puede llegar al pedido.
- **Una transacción ACID por confirmación.** Cubre la clave de idempotencia, el bloqueo de la cotización, el consumo del cupo, el pedido y los eventos del outbox, y los bloqueos se toman siempre en ese orden.
- **El 201 se envía solo después del commit.** El detalle y el historial leen las mismas tablas primarias. Un número de pedido devuelto apunta siempre a una fila confirmada y legible.
- **Reintentos:**
  - La misma `Idempotency-Key` con el mismo cuerpo devuelve la respuesta guardada.
  - La misma clave con otro cuerpo → 422.
  - Los fallos no se cachean.
- **Efectos externos.** El push y el ERP salen solo por un outbox transaccional, con entrega al menos una vez, reintentos con backoff y cola de fallidos. Los consumidores deduplican por `event_id`. Si un sistema está caído, el pedido sigue confirmado y el efecto espera.
- **El modelo:** consistencia fuerte dentro de la base de datos y eventual hacia el push y el ERP.
- **Lo que se sacrifica:**
  - la disponibilidad si cae la base de datos
  - la serialización por cliente sobre la fila de cupo
  - un breve retraso del push y del ERP

## Golden tests

Cada golden test lleva la etiqueta `@Tag("golden")` y verifica el desglose completo.

| Caso del enunciado | Prueba |
|--------------------|--------|
| 1. Descuento en dos tramos | `GoldenPricingTest#gt01` |
| 2. Combo sin descuentos sueltos | `GoldenPricingTest#gt02` |
| 3. Obsequio con su cantidad | `GoldenPricingTest#gt03` |
| 4. Combo con impuestos | `GoldenPricingTest#gt04` |
| 5. Pedido a crédito que excede el cupo | `GoldenQuoteTest#gt05`, `CreditLimitIT` |
| 6. Cotizar y confirmar dan el mismo total | `QuoteConfirmRegressionIT` |

## ADR

[ADR-0001: Cotizaciones selladas y confirmación en una sola transacción](docs/adr/0001-order-pricing-and-confirmation.es.md). Recoge las decisiones y alternativas descartadas, lo que no se implementó a propósito, la lista de revisión de código, y el riesgo principal con su mitigación.

## No incluido

| Elemento | Por qué |
|----------|---------|
| Autenticación | Fuera de alcance. Tenant y cliente llegan en cabeceras |
| Revocación de cotizaciones, cancelación, liberación de cupo | No se piden. La revocación es la mitigación del riesgo del ADR |
| Clientes reales de push y ERP | Los fakes detrás de puertos prueban las garantías de entrega |
| SonarQube o SonarCloud | Necesita un servidor. Importa el reporte XML de JaCoCo que este build ya genera, así que puede agregarse en CI sin cambiar el proyecto |
| Archivo OpenAPI, despliegue | No se piden. El contrato es `spec/api-contract.md` del repositorio `orderflow-engine-docs` (en inglés) |
| Job de migración aparte y roles de base de datos con mínimo privilegio | Liquibase aplica el esquema cuando arranca el servicio, con la credencial del dueño. En producción, un job de una sola ejecución migraría con un rol dueño y el servicio se conectaría con un rol solo DML. Queda registrado como camino de adopción en `spec/plan.md` §3 del repositorio `orderflow-engine-docs` |

## Repositorios

| Repositorio | Rol |
|-------------|-----|
| `orderflow-engine-api` (este) | El entregable: código, pruebas, este README, el ADR |
| `orderflow-engine-docs` | Especificación, contrato, modelo de datos, arquitectura, diagramas |
| `orderflow-engine-web` | La pantalla opcional de resumen del pedido |
| `orderflow-engine-infra` | El stack completo con Docker Compose y el comando de pruebas del espacio de trabajo |
| `orderflow-engine-tools` | Generadores y verificaciones de la documentación y de los repositorios |
| `orderflow-engine-app` | Reservado para una futura app móvil |
