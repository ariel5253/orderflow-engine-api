# orderflow-engine-api

[English](README.md)

Motor de cálculo de pedidos y confirmación idempotente para un marketplace B2B de bebidas en varios países. Java 21 · Spring Boot · PostgreSQL.

Este repositorio se sostiene solo: código, pruebas, este README y el ADR. La especificación completa, el contrato de la API y el modelo de datos están en el repositorio `orderflow-engine-docs` (en inglés).

## Cómo ejecutarlo

| Requisito | Versión |
|-----------|---------|
| JDK | 21 |
| Docker | en ejecución, porque las pruebas levantan PostgreSQL con Testcontainers |

```bash
./mvnw verify
```

Este **comando único** compila el servicio y ejecuta todas sus pruebas: unitarias, golden, de contrato, de arquitectura y de integración contra un PostgreSQL real. También exige el umbral de cobertura: al menos el 90% de las líneas y ramas del paquete `pricing`.

Para ejecutar el servicio con su propio PostgreSQL, solo desde este repositorio:

```bash
docker compose up --build
```

Liquibase crea el esquema cuando arranca el servicio y carga dos tenants: `co-alpha` (Colombia, COP, escala 0) y `ec-alpha` (Ecuador, USD, escala 2). Salud: `http://localhost:8080/actuator/health`. La configuración llega por variables de entorno. `.env.example` lista cada clave con un comentario. Cópialo a `.env` solo para cambiar un valor. `.env` nunca se versiona.

Las pruebas de mutación son opcionales y más lentas: `./mvnw -Pmutation verify` ejecuta PIT sobre `pricing.domain` y exige que se detecte al menos el 80% de los fallos inyectados.

### Endpoints

Toda operación de negocio exige las cabeceras `X-Tenant-Id` y `X-Customer-Id`. `Accept-Language` es opcional y elige el idioma de los mensajes de error (`es` por defecto, o `en`). Los errores son `application/problem+json` (RFC 9457) con un `code` estable y un `correlationId`. El dinero es un string JSON con la escala de la moneda. Cada petición, respuesta y error está especificado en `spec/api-contract.md` del repositorio `orderflow-engine-docs` (en inglés).

| Id | Operación | Éxito |
|----|-----------|-------|
| E-01 | `POST /api/v1/quotes` calcula el carrito y sella la cotización | 201 con `Location` |
| E-02 | `GET /api/v1/quotes/{quoteId}` lee la cotización y su estado derivado: `OPEN`, `CONFIRMED` o `EXPIRED` | 200 |
| E-03 | `POST /api/v1/orders` confirma una cotización. Exige `Idempotency-Key` | 201 con `Location` |
| E-04 | `GET /api/v1/orders/{orderId}` lee un pedido, armado desde su cotización | 200 |
| E-05 | `GET /api/v1/orders?customerId={id}&size=20` lista los pedidos del cliente, del más reciente al más antiguo, con `size` de máximo 100 | 200 |
| E-07 | `GET /api/v1/products` lista los productos del tenant en orden de SKU, con precios de lista antes de palancas e impuestos. Solo lee | 200 |

Cotizar 25 colas a crédito para un cliente sembrado con un cupo de 500.000:

```bash
curl -i http://localhost:8080/api/v1/quotes \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: co-alpha' \
  -H 'X-Customer-Id: 01a13cdb-cc00-7001-8001-000000000001' \
  -d '{"paymentMethod":"CREDIT","lines":[{"sku":"BEV-COLA-350","qty":25}]}'
```

El desglose es el golden test 1: 2.750 de descuento por escala, 11.353 de impuesto y `"total": "71103"`, con `credit.fits` en true. Guarda el `quoteId` de la respuesta en la variable de shell `quote_id` y confirma la cotización:

```bash
curl -i http://localhost:8080/api/v1/orders \
  -H 'Content-Type: application/json' \
  -H 'X-Tenant-Id: co-alpha' \
  -H 'X-Customer-Id: 01a13cdb-cc00-7001-8001-000000000001' \
  -H 'Idempotency-Key: 4f9b2c1e-7a3d-4e8f-9b6a-2d5c8e1f0a37' \
  -d "{\"quoteId\":\"$quote_id\"}"
```

El 201 trae el número de pedido y los mismos totales de la cotización. Enviar de nuevo el mismo comando devuelve la respuesta guardada byte a byte, con `Idempotent-Replayed: true`, sin crear un segundo pedido ni consumir el cupo otra vez. Otra cotización necesita otra clave: la misma clave con otro cuerpo se rechaza con 422 `IDEMPOTENCY_KEY_REUSED`. El pedido aparece en el historial en cuanto llega el 201:

```bash
curl -i 'http://localhost:8080/api/v1/orders?customerId=01a13cdb-cc00-7001-8001-000000000001' \
  -H 'X-Tenant-Id: co-alpha' \
  -H 'X-Customer-Id: 01a13cdb-cc00-7001-8001-000000000001'
```

## Orden de las palancas

| Paso | Palanca | Por qué en esta posición |
|------|---------|--------------------------|
| 1 | Elegibilidad de obsequios, sobre cantidades pagadas o el subtotal bruto antes de descuentos | El obsequio lo activa lo que se compra y nunca reduce lo que se paga por producto |
| 2 | Combos | El precio negociado del combo consume primero sus unidades, para que no reciban además descuento por escala |
| 3 | Descuento por escala, por tramos marginales, sobre las unidades que no consumió un combo | El incentivo genérico por volumen aplica solo a lo que el combo no tarifó |
| 4 | Neto de línea = bruto − descuentos | Los descuentos comerciales se liquidan antes del impuesto |
| 5 | Base gravable (neto, más el valor de referencia del obsequio si el país lo grava) | El impuesto va sobre la base después de los descuentos comerciales |
| 6 | Impuesto por línea, con la tasa de cada SKU | Las tasas son por SKU |
| 7 | Total = Σ neto + impuestos | Necesita todos los pasos anteriores |
| 8 | Verificación de cupo | Necesita el total final. Es informativa al cotizar y obligatoria al confirmar |

**Tramos de escala.** El enunciado dice que las escalas son "acumulativas por tramos". Lo leemos como tramos marginales: cada porcentaje aplica solo a las unidades dentro de su tramo. Ejemplo (golden test 1): 25 unidades a 2.500, con 10–19 → 5% y 20+ → 10%, dan 1.250 + 1.500 = **2.750** de descuento. Una regla por SKU prevalece sobre una regla por familia para ese SKU.

**Combos.** Un combo aparece como **una sola** entrada de descuento (`COMBO:<id>`). Sus productos nunca muestran descuentos sueltos. Cada línea participante lleva `comboAllocations`, que solo sirven para derivar su base gravable. Un combo que no bajaría el precio no consume nada.

**Obsequios.** Un obsequio es una línea aparte con su cantidad y origen `GIFT:<id>`, con precio 0. Su valor de referencia se grava solo donde la política del país para obsequios es `TAXED`.

Reglas completas: SPEC §3 en el repositorio `orderflow-engine-docs` (en inglés).

## Política de redondeo

- **Tipo:** `Money` envuelve un `BigDecimal` y una política de moneda. Una prueba de arquitectura prohíbe `float` y `double` en el dominio. Los montos se comparan con `compareTo`.
- **Escala y modo** salen de los datos del país. COP tiene escala 0, USD escala 2, y el modo es `HALF_UP`. Nunca están fijos en el código ni se toman de `java.util.Currency`. Un precio con más decimales de los que admite su moneda se rechaza al cargarlo.
- **Dónde se redondea: solo por línea.**
  - Cada componente de descuento se redondea por línea. Un combo se reparte entre sus líneas por mayor residuo, así que las partes suman exacto. Un componente que redondea a cero no se emite.
  - El impuesto se redondea por línea.
  - Los totales son sumas de líneas ya redondeadas y **nunca se vuelven a redondear**.
- **Por qué por línea:** la factura electrónica lleva el impuesto por línea, así que el pedido y su factura siempre coinciden.
- **Ejemplo (golden test 4):** un descuento de combo de 3.600 se reparte en 2.647 y 953. Los impuestos son 1.397 al 19% y 132 al 5%, y el total es 11.529.

## Modelo de consistencia

**Cotización = confirmación.** Las líneas y los componentes de descuento de una cotización solo se insertan. Cada línea guarda el precio y la tasa de impuesto vigentes al cotizar. El pedido referencia la cotización y no guarda ningún monto, así que cada cifra existe una sola vez. La confirmación nunca vuelve a ejecutar el motor, por lo que un cambio posterior de precios o palancas no puede llegar al pedido. Un cambio de precio aplica solo a cotizaciones nuevas, y una cotización abierta conserva su precio hasta vencer: 15 minutos después de creada, un tiempo de vida que es dato del país.

**Una transacción ACID por confirmación**, en READ COMMITTED con bloqueos explícitos de fila y un `lock_timeout`:

1. Insertar la clave de idempotencia. Una petición concurrente con la misma clave espera por ella.
2. Bloquear la cotización y verificar su dueño, que aún no tenga pedido y que no haya vencido.
3. Si se paga a crédito, consumir el cupo con una actualización condicional que nunca supera el límite. Si el total no cabe: 409 `CREDIT_LIMIT_EXCEEDED`, y el rollback no deja nada.
4. Insertar el pedido, que no guarda ningún monto, y, a crédito, su fila del libro de cupo.
5. Insertar un evento del outbox para el push y otro para el ERP.
6. Guardar el cuerpo del 201 en la clave de idempotencia.
7. Hacer commit, y solo entonces enviar el 201.

Los bloqueos se toman siempre en el orden clave → cotización → cupo, así que las confirmaciones concurrentes no pueden entrar en deadlock.

**La invariante del 201.** La respuesta se envía solo después del commit, y el detalle y el historial leen las mismas tablas primarias. Un número de pedido devuelto apunta siempre a una fila confirmada y legible.

**Reintentos:**

| Situación | Respuesta |
|-----------|-----------|
| Misma clave, mismo cuerpo, ya completada | La respuesta guardada, idéntica byte a byte, con `Idempotent-Replayed: true` |
| Misma clave, otro cuerpo | 422 `IDEMPOTENCY_KEY_REUSED` |
| Misma clave mientras la primera petición sigue en curso | Espera y luego repite la respuesta. 409 `IDEMPOTENCY_KEY_IN_PROGRESS` si antes se agota el `lock_timeout` |
| Una petición fallida (validación, cotización vencida, cupo excedido) | No se cachea. La transacción se revierte y libera la clave, así que el cliente puede corregir la causa y reintentar |
| Una clave nueva sobre una cotización confirmada | 409 `QUOTE_ALREADY_CONFIRMED`, con el número del pedido existente |

Las claves se guardan 24 horas. Después, un reintento sigue recibiendo `QUOTE_ALREADY_CONFIRMED` con el número de pedido.

**Efectos externos (outbox transaccional).** El push y el ERP se alcanzan solo mediante los eventos del outbox escritos junto con el pedido.

- Con la configuración por defecto, el relay consulta cada segundo, reclama los eventos pendientes con `FOR UPDATE SKIP LOCKED` y los toma en lease por 30 segundos, así que pueden correr varias instancias a la vez.
- Llama al push o al ERP fuera de toda transacción de base de datos, y les pasa el `event_id`.
- Un éxito marca el evento `SENT`. Un fallo se reintenta con backoff exponencial y jitter completo: la espera se elige al azar bajo un techo que empieza en 2 segundos, se duplica tras cada intento fallido y nunca pasa de 5 minutos. Tras 8 intentos, o de inmediato ante un error que reintentar no corrige, el evento pasa a `DEAD` (ver Operación).
- **Nunca se pierde:** si el servicio se detiene entre el envío y la marca, el lease vence y el evento se envía de nuevo.
- **Nunca se aplica dos veces:** los consumidores deduplican por `event_id`. Los gateways falsos de este repositorio lo hacen. Un push real lo usaría como collapse id, y el ERP haría upsert por número de pedido.
- **Sistema externo caído:** el pedido sigue confirmado y el efecto espera en el outbox. El gauge `outbox_pending` muestra el atraso, y la entrega se reanuda cuando el sistema se recupera.

**El modelo y lo que sacrifica:**

| Alcance | Modelo | Lo que se sacrifica |
|---------|--------|---------------------|
| Pedido, cupo, clave de idempotencia, eventos del outbox | Fuerte: una transacción ACID en una sola base de datos | La disponibilidad si cae la base de datos. Las confirmaciones de un mismo cliente se serializan sobre su fila de cupo |
| Push y ERP | Eventual: entrega al menos una vez, deduplicada por los consumidores | Llegan después de la confirmación, con el retraso del intervalo de consulta más los reintentos |

## Operación

### Reencolar un evento DEAD del outbox

Un evento pasa a `DEAD` tras 8 intentos fallidos, o de inmediato ante un error que reintentar no corrige, como un 4xx del ERP. El relay deja de enviarlo, el gauge `outbox_dead` lo cuenta y una línea de log ERROR indica que necesita un operador. La limpieza nunca borra eventos `DEAD`. Primero corrige la causa, que `last_error` registra. Luego, en la base de datos del servicio (por ejemplo `docker compose exec db psql -U orderflow -d orderflow`), lista los eventos y reencola uno por su `tenant_id` e `id`:

```sql
SELECT tenant_id, id, order_id, destination, attempts, last_error, created_at
FROM outbox_events
WHERE status = 'DEAD'
ORDER BY created_at DESC;

UPDATE outbox_events
SET status = 'PENDING', attempts = 0, next_attempt_at = now(), locked_until = NULL
WHERE tenant_id = :tenant AND id = :id AND status = 'DEAD';
```

Reemplaza `:tenant` e `:id` por el `tenant_id` y el `id` que devolvió la primera consulta, escritos como literales entre comillas, por ejemplo `'co-alpha'`.

Por qué es seguro:

- El relay reclama los eventos `PENDING` cuyo `next_attempt_at` ya pasó, así que la siguiente consulta lo toma y lo envía.
- `attempts = 0` le devuelve al evento todos sus reintentos.
- `last_error` no se toca, así que el último fallo sigue visible.
- La condición `status = 'DEAD'` hace repetible la sentencia: sobre un evento ya reencolado no cambia nada.
- Los consumidores deduplican por `event_id`, así que reencolar un evento que en realidad sí se entregó no tiene un segundo efecto.

### Retención

Un job de limpieza corre cada hora, para todos los tenants. Cada retención se puede cambiar en `.env`, y `.env.example` nombra la propiedad que fija cada clave.

| Dato | Se guarda | Configuración |
|------|-----------|---------------|
| Claves de idempotencia | 24 horas | `app.idempotency.retention` |
| Cotizaciones no confirmadas | 7 días después de vencer | `app.housekeeping.retention` |
| Eventos `SENT` del outbox | 7 días después de escritos | `app.housekeeping.retention` |
| Pedidos, sus cotizaciones y eventos `DEAD` | Nunca se borran | — |

## Golden tests

Cada golden test lleva la etiqueta `@Tag("golden")` y verifica montos exactos. Un caso de precios es una carpeta bajo `src/test/resources/golden/` con `request.json`, `snapshot.json` y `expected.json`. `GoldenPricingTest` verifica cada carpeta dos veces: contra su desglose esperado, y contra las identidades que cumple cualquier desglose, como total = Σ neto + impuestos.

| Caso del enunciado | Carpeta del caso | Prueba |
|--------------------|------------------|--------|
| 1. Descuento en dos tramos | `gt01-two-tier-brackets/` | `GoldenPricingTest` |
| 2. Combo sin descuentos sueltos | `gt02-combo-no-loose-discount/` | `GoldenPricingTest` |
| 3. Obsequio con su cantidad | `gt03-gift/` | `GoldenPricingTest` |
| 4. Combo con impuestos | `gt04-combo-with-taxes/` | `GoldenPricingTest` |
| 5. Pedido a crédito que excede el cupo | `gt05-credit-exceeded/`, que agrega `expected-quote.json` | `GoldenQuoteTest#gt05` para la cotización. `CreditLimitIT` para la confirmación: 409, sin pedido y sin fila en el libro de cupo |
| 6. Cotizar y confirmar dan el mismo total | Ninguna: corre contra PostgreSQL con los datos sembrados | `QuoteConfirmRegressionIT`: entre cotizar y confirmar cambia un precio y se desactiva una palanca. El total confirmado es igual al cotizado, y una cotización nueva difiere |

Más casos en el mismo arnés: `gift-policy-exempt/`, `gift-policy-taxed/`, `rounding-half-cases/`, `multi-country-co-vs-ec/`, `co-no-levers/` y `ec-no-levers/`. `QuoteLeversIT` además repite los casos de escala, combo y obsequio por `POST /api/v1/quotes` contra los datos semilla, así que las palancas quedan probadas de punta a punta, no solo en el motor.

## ADR

[ADR-0001: Cotizaciones selladas y confirmación en una sola transacción](docs/adr/0001-order-pricing-and-confirmation.es.md), aceptado. Recoge las decisiones y alternativas descartadas, lo que no se implementó a propósito, la lista de revisión de código, y el riesgo principal con su mitigación.

## No incluido

| Elemento | Por qué |
|----------|---------|
| Autenticación | Fuera de alcance. Tenant y cliente llegan en cabeceras, y un gateway los inyectaría |
| Cobro, cancelación y liberación de cupo | No se piden |
| Persistencia del carrito | No se pide. El carrito viaja en la petición de cotización |
| Revocación de cotizaciones | No se pide. Es el siguiente paso de la mitigación del riesgo del ADR |
| Administración de catálogo y palancas | No se pide. Las migraciones siembran los datos. La única operación de catálogo es una lista de productos de solo lectura (E-07), que alimenta el carrito web opcional. Vive en `quoting` porque lista exactamente lo que E-01 puede cotizar. No se agrega un módulo `catalog`, porque la administración del catálogo queda fuera (SPEC §1) |
| Categorías de impuesto e historial de precios | No se piden. Cada SKU lleva su tasa de impuesto, y cada línea de cotización guarda el precio y la tasa vigentes al cotizar |
| Clientes reales de push y ERP | Los fakes detrás de puertos prueban las garantías de entrega. Deduplican por `event_id` y se les puede ordenar que fallen |
| Archivo OpenAPI, despliegue | No se piden. El contrato es `spec/api-contract.md` del repositorio `orderflow-engine-docs` (en inglés) |
| Configuración CORS | No hace falta. El nginx del cliente web reenvía `/api/` a este servicio, así que el navegador ve un solo origen |
| SonarQube o SonarCloud | Necesita un servidor. Importaría el reporte XML de JaCoCo que este build ya genera, así que CI puede agregarlo sin cambiar el proyecto |
| Pipeline de CI | La verificación corre en local con `./mvnw verify`. Un workflow correría el mismo comando en cada pull request |
| Pruebas de mutación en CI | PIT corre en el perfil opcional `mutation` antes de cada entrega. Un job nocturno, o una corrida sobre los archivos cambiados, solo requiere configuración de CI |
| Job de migración aparte y roles de base de datos con mínimo privilegio | Liquibase aplica el esquema cuando arranca el servicio, con la credencial del dueño. En producción, un job de una sola ejecución migraría con un rol dueño, el servicio se conectaría con un rol solo DML, y el bloqueo de la cotización pasaría a `pg_advisory_xact_lock` |
| Repositorio de esquema aparte | Los changelogs viven con su único dueño, este servicio. Cada uno tiene un `logicalFilePath`, así que pueden pasar a su propio repositorio sin volver a aplicarse |

El camino de adopción de cada una de las últimas cinco filas (estado actual, cómo se adopta y su disparador) está en `spec/plan.md` §3 del repositorio `orderflow-engine-docs` (en inglés).

### Tiempo invertido

| Parte | Tiempo medido |
|-------|---------------|
| La prueba (A1, A2, A3), incluida la especificación | Tres días de trabajo, del 2026-10-13 al 2026-10-15 |
| La pantalla opcional de la prueba: el resumen del pedido | 68 min |
| Su contenedor, el cliente web en el stack completo y el paso web de `test-all` | 55 min |
| La extensión más allá de la prueba: la lista de productos (E-07), el carrito, la confirmación idempotente, el pedido, el selector de tienda y Mis pedidos | 3 h 1 min |

Los tiempos del cliente web son el tiempo de trabajo de cada parte, construyéndola y corrigiéndola, tomados de marcas de tiempo registradas en cada paso. No incluyen el tiempo de revisión. Las partes se construyeron entre el 2026-10-15 22:05 y el 2026-10-16 06:15 (UTC-5), varias en paralelo. La pantalla sola supera la hora que la prueba le asigna, una vez contados sus tests y su contenedor.

## Repositorios

| Repositorio | Rol |
|-------------|-----|
| `orderflow-engine-api` (este) | El entregable: código, pruebas, este README, el ADR |
| `orderflow-engine-docs` | Especificación, contrato, modelo de datos, arquitectura, diagramas |
| `orderflow-engine-web` | El cliente web opcional: el resumen del pedido que ofrece la prueba y el flujo de compra a su alrededor |
| `orderflow-engine-infra` | El stack completo con Docker Compose y el comando de pruebas del espacio de trabajo |
| `orderflow-engine-tools` | Generadores y verificaciones de la documentación y de los repositorios |
| `orderflow-engine-app` | Reservado para una futura app móvil |
