package com.orderflow.engine.adapters.in.web;

import static com.orderflow.engine.ordering.OrderTestSupport.ORDERS;
import static com.orderflow.engine.ordering.OrderTestSupport.assertProblem;
import static com.orderflow.engine.ordering.OrderTestSupport.json;
import static com.orderflow.engine.ordering.OrderTestSupport.newKey;
import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.EC;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CREDIT_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import com.orderflow.engine.ordering.OrderRepository;
import com.orderflow.engine.ordering.OrderTestSupport;
import com.orderflow.engine.ordering.PlacedOrder;
import com.orderflow.engine.quoting.PaymentMethod;
import com.orderflow.engine.quoting.QuoteRepository;
import com.orderflow.engine.quoting.QuoteTestData;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * AC-31, BD-01 and api-contract E-04, E-05: an order reads back with the body of its confirmation; a history lists the
 * caller's orders newest first, 20 by default and never more than 100, with the tenant's currency once per body and
 * every total a string at that currency's scale; and every error row of the two operation cards is a problem with its
 * status and code. An order of another tenant or customer is not found, and a history asked for anyone but the caller
 * answers CUSTOMER_NOT_FOUND, the same whether or not that customer exists. Confirmations commit for real, so each
 * test removes its orders and quotes afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrderHistoryContractIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    /** The order of the E-03 body fields in the contract, the body E-04 returns too. */
    private static final List<String> ORDER_FIELDS = List.of("orderId", "orderNumber", "status", "quoteId",
            "confirmedAt", "currency", "lines", "totals", "paymentMethod");

    /** The order of the E-05 body fields in the contract. */
    private static final List<String> HISTORY_FIELDS = List.of("currency", "items");

    /** The order of the fields of an E-05 item in the contract: no item repeats the currency. */
    private static final List<String> ITEM_FIELDS = List.of("orderId", "orderNumber", "confirmedAt", "total");

    /** The validity of a quote that is still open when the test confirms it. */
    private static final Instant OPEN_FROM = Instant.parse("2100-01-01T00:00:00Z");
    private static final Instant OPEN_UNTIL = Instant.parse("2100-01-01T00:15:00Z");

    /**
     * The total of {@link QuoteTestData#withEveryComponent}: a net of 61,640 + 13,235 + 0 for the gift, 74,875, plus
     * taxes of 11,712 + 662 + 285, 12,659.
     */
    private static final String EVERY_COMPONENT_TOTAL = "87534";

    /** The total of {@link QuoteTestData#threeColas}: 15,600 plus 2,964 of tax. */
    private static final String THREE_COLAS_TOTAL = "18564";

    /** The total of {@link OrderTestSupport#CO_CART}: 19,200 of net plus 2,964 + 180 of tax. */
    private static final String CO_CART_TOTAL = "22344";

    /** Three 1.5 l colas of ec-alpha at 1.45 with 15% tax, paid cash. No lever of the seeds applies to it. */
    private static final String EC_CART = """
            {"paymentMethod": "CASH", "lines": [{"sku": "BEV-COLA-1500", "qty": 3}]}""";

    /** 3 x 1.45 = 4.35, and 15% of it, 0.6525, rounded half up to 0.65: 5.00, with both decimals of USD. */
    private static final String EC_CART_TOTAL = "5.00";

    private static final String UNKNOWN_ORDER = "01a13cdb-0000-7000-8000-00000000dead";
    private static final String UNKNOWN_CUSTOMER = "01a13cdb-0000-7000-8000-00000000beef";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private QuoteRepository quotes;

    @Autowired
    private OrderRepository orderRepository;

    private OrderTestSupport orders;

    @BeforeEach
    void setUp() {
        orders = new OrderTestSupport(mvc, jdbc);
    }

    /** Orders and quotes go first: a quote references its customer, so the shared-id customer goes after them. */
    @AfterEach
    void removeOrdersAndQuotes() {
        orders.deleteOrdersAndQuotes();
        jdbc.sql("DELETE FROM customers WHERE tenant_id = :tenantId AND id = :customerId")
                .param("tenantId", EC)
                .param("customerId", CO_CASH_CUSTOMER)
                .update();
    }

    @Test
    void anOrderReadsBackWithTheBodyOfItsConfirmation() {
        JsonNode confirmed = confirmEveryComponent();
        String orderId = confirmed.required("orderId").stringValue();

        MvcTestResult read = orders.readOrder(CO, CO_CASH_CUSTOMER, orderId);

        assertThat(read).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        JsonNode body = json(read);
        assertThat(body.propertyNames()).containsExactlyElementsOf(ORDER_FIELDS);
        assertThat(body).isEqualTo(confirmed);
        assertThat(body.required("status").stringValue()).isEqualTo("CONFIRMED");
        assertThat(body.required("totals").required("total").stringValue()).isEqualTo(EVERY_COMPONENT_TOTAL);
    }

    @Test
    void aHistoryItemCarriesTheTotalOfItsOrdersBreakdown() {
        JsonNode confirmed = confirmEveryComponent();

        MvcTestResult history = orders.history(CO, CO_CASH_CUSTOMER);

        assertThat(history).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(json(history)).isEqualTo(json("""
                {"currency": "COP",
                 "items": [{"orderId": "%s", "orderNumber": "%s", "confirmedAt": "%s", "total": "%s"}]}"""
                .formatted(confirmed.required("orderId").stringValue(),
                        confirmed.required("orderNumber").stringValue(),
                        confirmed.required("confirmedAt").stringValue(), EVERY_COMPONENT_TOTAL)));
    }

    @Test
    void aHistoryGivesItsCurrencyOnceAndEveryTotalAtTheScaleOfThatCurrency() {
        JsonNode coOrder = confirm(CO, CO_CREDIT_CUSTOMER, OrderTestSupport.CO_CART.formatted("CREDIT"));
        JsonNode ecOrder = confirm(EC, EC_CASH_CUSTOMER, EC_CART);

        JsonNode coHistory = json(orders.history(CO, CO_CREDIT_CUSTOMER));
        JsonNode ecHistory = json(orders.history(EC, EC_CASH_CUSTOMER));

        assertThat(coHistory.propertyNames()).containsExactlyElementsOf(HISTORY_FIELDS);
        assertThat(coHistory.required("items").get(0).propertyNames()).containsExactlyElementsOf(ITEM_FIELDS);
        assertThat(coHistory).isEqualTo(historyOf("COP", coOrder, CO_CART_TOTAL));
        assertThat(ecHistory.propertyNames()).containsExactlyElementsOf(HISTORY_FIELDS);
        assertThat(ecHistory.required("items").get(0).propertyNames()).containsExactlyElementsOf(ITEM_FIELDS);
        assertThat(ecHistory).isEqualTo(historyOf("USD", ecOrder, EC_CART_TOTAL));
    }

    @Test
    void anEmptyHistoryStillGivesTheCurrencyOfTheTenant() {
        MvcTestResult co = orders.history(CO, CO_CASH_CUSTOMER);
        MvcTestResult ec = orders.history(EC, EC_CREDIT_CUSTOMER);

        assertThat(co).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(json(co)).isEqualTo(json("{\"currency\": \"COP\", \"items\": []}"));
        assertThat(ec).hasStatus(HttpStatus.OK).hasContentType(MediaType.APPLICATION_JSON);
        assertThat(json(ec)).isEqualTo(json("{\"currency\": \"USD\", \"items\": []}"));
    }

    /** 101 orders one second apart: the default size gives the 20 newest, and any size above 100 gives 100. */
    @Test
    void aHistoryListsTheNewestOrdersFirstAndNeverMoreThanAHundred() {
        Instant oldest = Instant.parse("2026-10-01T08:00:00Z");
        List<PlacedOrder> placed = cashOrdersOneSecondApart(101, oldest);
        List<String> newestFirst = placed.stream().map(PlacedOrder::orderNumber).toList().reversed();

        assertThat(numbers(orders.history(CO, CO_CASH_CUSTOMER))).as("default size")
                .isEqualTo(newestFirst.subList(0, 20));
        assertThat(numbers(history(CO_CASH_CUSTOMER, "1"))).as("size 1").isEqualTo(newestFirst.subList(0, 1));
        assertThat(numbers(history(CO_CASH_CUSTOMER, "100"))).as("size 100").isEqualTo(newestFirst.subList(0, 100));
        assertThat(numbers(history(CO_CASH_CUSTOMER, "101"))).as("size 101").isEqualTo(newestFirst.subList(0, 100));
        assertThat(numbers(history(CO_CASH_CUSTOMER, "99999999999"))).as("size above the int range")
                .isEqualTo(newestFirst.subList(0, 100));

        PlacedOrder newest = placed.getLast();
        assertThat(json(history(CO_CASH_CUSTOMER, "1")).required("items").get(0)).isEqualTo(json("""
                {"orderId": "%s", "orderNumber": "%s", "confirmedAt": "2026-10-01T08:01:40Z", "total": "%s"}"""
                .formatted(newest.orderId(), newest.orderNumber(), THREE_COLAS_TOTAL)));
    }

    /** Two orders of the same instant are listed the same way on every read: the greater id first. */
    @Test
    void ordersConfirmedAtTheSameInstantAreListedByTheirIdsDescending() {
        Instant sameInstant = Instant.parse("2026-10-01T08:00:00Z");
        insertOrder("01a13cdb-0000-7000-8000-000000000001", "CO-900001", sameInstant);
        insertOrder("01a13cdb-0000-7000-8000-000000000002", "CO-900002", sameInstant);

        assertThat(numbers(orders.history(CO, CO_CASH_CUSTOMER))).containsExactly("CO-900002", "CO-900001");
    }

    @Test
    void aHistoryListsOnlyTheCallersOwnOrders() {
        JsonNode creditOrder = confirm(CO, CO_CREDIT_CUSTOMER, OrderTestSupport.CO_CART.formatted("CREDIT"));
        JsonNode cashOrder = confirm(CO, CO_CASH_CUSTOMER, OrderTestSupport.CO_CART.formatted("CASH"));
        JsonNode ecOrder = confirm(EC, EC_CASH_CUSTOMER, EC_CART);

        assertThat(numbers(orders.history(CO, CO_CREDIT_CUSTOMER)))
                .containsExactly(creditOrder.required("orderNumber").stringValue());
        assertThat(numbers(orders.history(CO, CO_CASH_CUSTOMER)))
                .containsExactly(cashOrder.required("orderNumber").stringValue());
        assertThat(numbers(orders.history(EC, EC_CASH_CUSTOMER)))
                .containsExactly(ecOrder.required("orderNumber").stringValue());
        assertThat(numbers(orders.history(EC, EC_CREDIT_CUSTOMER))).isEmpty();
    }

    /**
     * A customer is keyed by its tenant and its id, so two tenants can each have a customer with the same id. Each
     * tenant's history of that id lists only its own order, in its own currency.
     */
    @Test
    void aHistoryListsOnlyTheOrdersOfTheCallersTenantWhenAnotherTenantHasACustomerWithTheSameId() {
        jdbc.sql("INSERT INTO customers (tenant_id, id, name) VALUES (:tenantId, :customerId, 'Cuenca Corner Shop')")
                .param("tenantId", EC)
                .param("customerId", CO_CASH_CUSTOMER)
                .update();
        JsonNode coOrder = confirm(CO, CO_CASH_CUSTOMER, OrderTestSupport.CO_CART.formatted("CASH"));
        JsonNode ecOrder = confirm(EC, CO_CASH_CUSTOMER, EC_CART);

        MvcTestResult coHistory = orders.history(CO, CO_CASH_CUSTOMER);
        MvcTestResult ecHistory = orders.history(EC, CO_CASH_CUSTOMER);

        assertThat(coHistory).hasStatus(HttpStatus.OK);
        assertThat(json(coHistory)).isEqualTo(historyOf("COP", coOrder, CO_CART_TOTAL));
        assertThat(ecHistory).hasStatus(HttpStatus.OK);
        assertThat(json(ecHistory)).isEqualTo(historyOf("USD", ecOrder, EC_CART_TOTAL));
    }

    @Test
    void anOrderOfAnotherCustomerOrTenantIsNotFound() {
        String orderId = confirm(CO, CO_CREDIT_CUSTOMER, OrderTestSupport.CO_CART.formatted("CREDIT"))
                .required("orderId").stringValue();
        String path = ORDERS + "/" + orderId;

        assertProblem(orders.readOrder(CO, CO_CASH_CUSTOMER, orderId), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", path);
        assertProblem(orders.readOrder(EC, EC_CREDIT_CUSTOMER, orderId), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                path);
        assertProblem(orders.readOrder(EC, CO_CREDIT_CUSTOMER, orderId), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND",
                path);
        assertProblem(orders.readOrder(CO, UNKNOWN_CUSTOMER, orderId), HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", path);
        assertThat(orders.readOrder(CO, CO_CREDIT_CUSTOMER, orderId)).as("read by its own customer")
                .hasStatus(HttpStatus.OK);
    }

    /** BD-01: the answer is the same for a customer with orders, one of another tenant and one that does not exist. */
    @Test
    void aHistoryAskedForAnyoneButTheCallerIsNotFoundWhetherOrNotThatCustomerExists() {
        confirm(CO, CO_CASH_CUSTOMER, OrderTestSupport.CO_CART.formatted("CASH"));
        String caller = CO_CREDIT_CUSTOMER.toString();

        MvcTestResult withOrders = orders.history(CO, caller, CO_CASH_CUSTOMER.toString(), null);
        MvcTestResult ofAnotherTenant = orders.history(CO, caller, EC_CASH_CUSTOMER.toString(), null);
        MvcTestResult unknown = orders.history(CO, caller, UNKNOWN_CUSTOMER, null);

        assertProblem(withOrders, HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertProblem(ofAnotherTenant, HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertProblem(unknown, HttpStatus.NOT_FOUND, "CUSTOMER_NOT_FOUND");
        assertThat(withoutCorrelationId(withOrders))
                .isEqualTo(withoutCorrelationId(unknown))
                .isEqualTo(withoutCorrelationId(ofAnotherTenant));
    }

    @ParameterizedTest(name = "{0}: tenant {2}, customer {3}, order {4}")
    @MethodSource("readErrors")
    void everyErrorOfTheReadOperationIsAProblem(String code, HttpStatus status, String tenant, String customer,
            String orderId) {
        assertProblem(orders.readOrder(tenant, customer, orderId), status, code, ORDERS + "/" + orderId);
    }

    /** The rows of the E-04 error table, one request each. A null tenant or customer leaves its header out. */
    static Stream<Arguments> readErrors() {
        String customer = CO_CREDIT_CUSTOMER.toString();
        return Stream.of(
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, null, customer, UNKNOWN_ORDER),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, null, UNKNOWN_ORDER),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, "customer-1", UNKNOWN_ORDER),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, customer, "not-a-uuid"),
                arguments("TENANT_NOT_FOUND", HttpStatus.BAD_REQUEST, "zz-unknown", customer, UNKNOWN_ORDER),
                arguments("ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, CO, customer, UNKNOWN_ORDER),
                arguments("ORDER_NOT_FOUND", HttpStatus.NOT_FOUND, CO, UNKNOWN_CUSTOMER, UNKNOWN_ORDER));
    }

    @ParameterizedTest(name = "{0}: tenant {2}, customer {3}, customerId {4}, size [{5}]")
    @MethodSource("historyErrors")
    void everyErrorOfTheHistoryOperationIsAProblem(String code, HttpStatus status, String tenant, String customer,
            String customerId, String size) {
        assertProblem(orders.history(tenant, customer, customerId, size), status, code);
    }

    /**
     * The rows of the E-05 error table, one request each. A null tenant or customer leaves its header out, and a null
     * customerId or size leaves its query parameter out.
     */
    static Stream<Arguments> historyErrors() {
        String caller = CO_CREDIT_CUSTOMER.toString();
        return Stream.of(
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, null, caller, caller, null),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, null, caller, null),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, null, null),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, "", null),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, "customer-1", caller, null),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, "customer-1", null),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, caller, "twenty"),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, caller, "1.5"),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, caller, "1e2"),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, caller, ""),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, caller, "0"),
                arguments("VALIDATION_FAILED", HttpStatus.BAD_REQUEST, CO, caller, caller, "-1"),
                arguments("TENANT_NOT_FOUND", HttpStatus.BAD_REQUEST, "zz-unknown", caller, caller, null),
                arguments("CUSTOMER_NOT_FOUND", HttpStatus.NOT_FOUND, CO, caller, CO_CASH_CUSTOMER.toString(), null),
                arguments("CUSTOMER_NOT_FOUND", HttpStatus.NOT_FOUND, CO, UNKNOWN_CUSTOMER, UNKNOWN_CUSTOMER, null),
                arguments("CUSTOMER_NOT_FOUND", HttpStatus.NOT_FOUND, CO, EC_CREDIT_CUSTOMER.toString(),
                        EC_CREDIT_CUSTOMER.toString(), null));
    }

    /** Confirms {@link QuoteTestData#withEveryComponent} for the co-alpha cash customer and returns the 201 body. */
    private JsonNode confirmEveryComponent() {
        UUID quoteId = quotes.insert(QuoteTestData.withEveryComponent(CO_CASH_CUSTOMER, OPEN_FROM, OPEN_UNTIL));
        MvcTestResult confirmed = orders.confirm(CO, CO_CASH_CUSTOMER, newKey(), quoteId);
        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        return json(confirmed);
    }

    /** Quotes the cart and confirms the quote through the endpoints, as a client would, and returns the 201 body. */
    private JsonNode confirm(String tenant, UUID customer, String cart) {
        MvcTestResult quoted = orders.quote(tenant, customer, cart);
        assertThat(quoted).hasStatus(HttpStatus.CREATED);
        UUID quoteId = UUID.fromString(json(quoted).required("quoteId").stringValue());
        MvcTestResult confirmed = orders.confirm(tenant, customer, newKey(), quoteId);
        assertThat(confirmed).hasStatus(HttpStatus.CREATED);
        return json(confirmed);
    }

    /**
     * Places {@code count} orders of the co-alpha cash customer through the ports, one second apart from
     * {@code oldest}, each confirming its own quote of three 1.5 l colas. They are returned from the oldest to the
     * newest.
     */
    private List<PlacedOrder> cashOrdersOneSecondApart(int count, Instant oldest) {
        List<PlacedOrder> placed = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UUID quoteId = quotes.insert(
                    QuoteTestData.threeColas(CO_CASH_CUSTOMER, PaymentMethod.CASH, OPEN_FROM, OPEN_UNTIL));
            placed.add(orderRepository.insert(CO, CO_CASH_CUSTOMER, quoteId, oldest.plusSeconds(i)));
        }
        return placed;
    }

    /** Inserts an order of the co-alpha cash customer with this id, number and instant. */
    private void insertOrder(String orderId, String orderNumber, Instant confirmedAt) {
        UUID quoteId = quotes.insert(
                QuoteTestData.threeColas(CO_CASH_CUSTOMER, PaymentMethod.CASH, OPEN_FROM, OPEN_UNTIL));
        jdbc.sql("""
                        INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
                        VALUES (:tenantId, :orderId, :quoteId, :customerId, :orderNumber, :confirmedAt)""")
                .param("tenantId", CO)
                .param("orderId", UUID.fromString(orderId))
                .param("quoteId", quoteId)
                .param("customerId", CO_CASH_CUSTOMER)
                .param("orderNumber", orderNumber)
                .param("confirmedAt", confirmedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private MvcTestResult history(UUID customer, String size) {
        return orders.history(CO, customer.toString(), customer.toString(), size);
    }

    /** The E-05 body of one order, with the order's id, number and instant taken from its 201 body. */
    private static JsonNode historyOf(String currency, JsonNode order, String total) {
        return json("""
                {"currency": "%s",
                 "items": [{"orderId": "%s", "orderNumber": "%s", "confirmedAt": "%s", "total": "%s"}]}"""
                .formatted(currency, order.required("orderId").stringValue(),
                        order.required("orderNumber").stringValue(), order.required("confirmedAt").stringValue(),
                        total));
    }

    /** The order numbers of a 200 history, in the order of its items. */
    private static List<String> numbers(MvcTestResult history) {
        assertThat(history).hasStatus(HttpStatus.OK);
        return json(history).required("items").valueStream()
                .map(item -> item.required("orderNumber").stringValue())
                .toList();
    }

    /** The problem body without its correlation id, the one field that differs between two requests. */
    private static JsonNode withoutCorrelationId(MvcTestResult problem) {
        return ((ObjectNode) json(problem)).without("correlationId");
    }
}
