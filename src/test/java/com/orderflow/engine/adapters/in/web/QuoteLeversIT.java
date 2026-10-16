package com.orderflow.engine.adapters.in.web;

import static com.orderflow.engine.quoting.QuoteTestData.CO;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CASH_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.CO_CREDIT_CUSTOMER;
import static com.orderflow.engine.quoting.QuoteTestData.EC;
import static com.orderflow.engine.quoting.QuoteTestData.EC_CASH_CUSTOMER;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * AC-04 to AC-07 through api-contract E-01 and E-02, against the seeded levers: a quote applies the tenant's active
 * tier rules, combos and gifts, seals every discount component and gift line, and reads back with the same breakdown.
 * Every body is also checked against the BR-05 identities. The expected figures are the worked numbers of SPEC BR-02
 * and BR-03, or are computed by hand in this class from the seed changelog: in co-alpha, the 350 ml cola lists at
 * 2,500 with 19% tax, the chips at 1,800 with 5% and the water at 1,500 with 19%; its tier rule on the 350 ml cola
 * gives 5% from unit 10 and 10% from unit 20; its combo sells 2 colas and 1 chips for 5,000; and one water is given
 * for every 10 snacks, taxed because Colombia taxes gifts. Every test runs in a transaction that is rolled back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Transactional
class QuoteLeversIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String QUOTES = "/api/v1/quotes";

    private static final String CO_TIER = "01a13cdb-cc00-7001-8003-000000000001";
    private static final String CO_COMBO = "01a13cdb-cc00-7001-8004-000000000001";
    private static final String CO_GIFT = "01a13cdb-cc00-7001-8005-000000000001";
    private static final String EC_TIER = "01a13cdb-cc00-7002-8003-000000000001";
    private static final String EC_GIFT = "01a13cdb-cc00-7002-8005-000000000001";

    /** 30 colas of 350 ml and 10 chips: a cart that every lever of co-alpha prices. */
    private static final String EVERY_LEVER_CART = """
            {"paymentMethod": "CASH",
             "lines": [{"sku": "SNK-CHIPS-45", "qty": 10}, {"sku": "BEV-COLA-350", "qty": 30}]}""";

    /**
     * {@link #EVERY_LEVER_CART} priced with every lever. The gift comes first: 10 snacks earn one water. The combo then
     * applies 10 times, consuming 20 colas (50,000) and the 10 chips (18,000) for 10 x 5,000 = 50,000, a discount of
     * 18,000. Its exact shares, 18,000 x 50,000 / 68,000 = 13,235.29 and 18,000 x 18,000 / 68,000 = 4,764.71, truncate
     * to 17,999, and the leftover peso goes to the larger remainder: 13,235 and 4,765. The 10 colas the combo leaves
     * are units 1 to 10 of the tier count, so only unit 10 earns 5%: 2,500 x 5% = 125. Colas: 75,000 - 125 - 13,235 =
     * 61,640, tax 11,711.60 rounded to 11,712. Chips: 18,000 - 4,765 = 13,235, tax 661.75 rounded to 662. Water: a
     * reference value of 1,500, taxed 285. Net 74,875 plus 12,659 of taxes is 87,534.
     */
    private static final String EVERY_LEVER_LINES = """
            [
              {"sku": "BEV-COLA-350", "description": "Cola 350 ml", "qty": 30, "isGift": false,
               "unitPrice": "2500", "gross": "75000",
               "discounts": [{"origin": "TIER:%1$s", "amount": "125"}],
               "comboAllocations": [{"comboId": "%2$s", "amount": "13235"}],
               "net": "61640", "taxableBase": "61640", "taxRate": "0.190000", "tax": "11712"},
              {"sku": "SNK-CHIPS-45", "description": "Chips 45 g", "qty": 10, "isGift": false,
               "unitPrice": "1800", "gross": "18000", "discounts": [],
               "comboAllocations": [{"comboId": "%2$s", "amount": "4765"}],
               "net": "13235", "taxableBase": "13235", "taxRate": "0.050000", "tax": "662"},
              {"sku": "BEV-WATER-600", "description": "Water 600 ml", "qty": 1, "isGift": true,
               "origin": "GIFT:%3$s",
               "unitPrice": "0", "gross": "0", "discounts": [], "comboAllocations": [],
               "net": "0", "referenceValue": "1500", "taxableBase": "1500", "taxRate": "0.190000", "tax": "285"}
            ]""".formatted(CO_TIER, CO_COMBO, CO_GIFT);

    private static final String EVERY_LEVER_TOTALS = """
            {"grossSubtotal": "93000",
             "discounts": [{"origin": "COMBO:%2$s", "amount": "18000"}, {"origin": "TIER:%1$s", "amount": "125"}],
             "gifts": [{"sku": "BEV-WATER-600", "qty": 1, "origin": "GIFT:%3$s"}],
             "taxableBase": "76375", "taxes": "12659", "total": "87534"}""".formatted(CO_TIER, CO_COMBO, CO_GIFT);

    /**
     * {@link #EVERY_LEVER_CART} once no lever is active and the chips list at 1,900: 30 colas at 2,500, 75,000 with
     * 14,250 of tax at 19%, and 10 chips at 1,900, 19,000 with 950 of tax at 5%, and no gift. Net 94,000 plus 15,200 of
     * taxes is 109,200.
     */
    private static final String EVERY_LEVER_LINES_WITHOUT_LEVERS = """
            [
              {"sku": "BEV-COLA-350", "description": "Cola 350 ml", "qty": 30, "isGift": false,
               "unitPrice": "2500", "gross": "75000", "discounts": [], "comboAllocations": [],
               "net": "75000", "taxableBase": "75000", "taxRate": "0.190000", "tax": "14250"},
              {"sku": "SNK-CHIPS-45", "description": "Chips 45 g", "qty": 10, "isGift": false,
               "unitPrice": "1900", "gross": "19000", "discounts": [], "comboAllocations": [],
               "net": "19000", "taxableBase": "19000", "taxRate": "0.050000", "tax": "950"}
            ]""";

    private static final String EVERY_LEVER_TOTALS_WITHOUT_LEVERS = """
            {"grossSubtotal": "94000", "discounts": [], "gifts": [],
             "taxableBase": "94000", "taxes": "15200", "total": "109200"}""";

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private JdbcClient jdbc;

    /**
     * GT-01 through E-01, with the worked numbers of SPEC BR-02 and the body of the E-01 example: 25 colas of 350 ml at
     * 2,500. Units 10 to 19 earn 10 x 2,500 x 5% = 1,250 and units 20 to 25 earn 6 x 2,500 x 10% = 1,500, one tier
     * discount of 2,750. Net 59,750, tax 11,352.50 rounded to 11,353, total 71,103. The combo needs chips, so it does
     * not apply.
     */
    @Test
    @Tag("golden")
    void gt01AQuoteDiscountsTheTierBracketsOfTheSeededRule() {
        MvcTestResult created = post(CO, CO_CREDIT_CUSTOMER, """
                {"paymentMethod": "CREDIT", "lines": [{"sku": "BEV-COLA-350", "qty": 25}]}""");

        JsonNode body = createdBody(created);
        assertThat(body.required("lines")).isEqualTo(JSON.readTree("""
                [{"sku": "BEV-COLA-350", "description": "Cola 350 ml", "qty": 25, "isGift": false,
                  "unitPrice": "2500", "gross": "62500",
                  "discounts": [{"origin": "TIER:%s", "amount": "2750"}],
                  "comboAllocations": [],
                  "net": "59750", "taxableBase": "59750", "taxRate": "0.190000", "tax": "11353"}]"""
                .formatted(CO_TIER)));
        assertThat(body.required("totals")).isEqualTo(JSON.readTree("""
                {"grossSubtotal": "62500",
                 "discounts": [{"origin": "TIER:%s", "amount": "2750"}],
                 "gifts": [],
                 "taxableBase": "59750", "taxes": "11353", "total": "71103"}""".formatted(CO_TIER)));
        assertThat(body.required("credit")).isEqualTo(JSON.readTree("""
                {"fits": true, "available": "500000", "shortfall": "0"}"""));
        assertIdentities(body);
        assertReadsBackUnchanged(CO, CO_CREDIT_CUSTOMER, body);
    }

    /**
     * GT-04 through E-01, with the worked numbers of SPEC BR-03: 4 colas of 350 ml and 2 chips make 2 applications of
     * the combo, which list at 10,000 + 3,600 = 13,600 and sell for 10,000. The 3,600 of discount is allocated 2,647 to
     * the colas and 953 to the chips, and each line is taxed on its allocated base: 7,353 x 19% = 1,397.07 rounded to
     * 1,397, and 2,647 x 5% = 132.35 rounded to 132. Total 10,000 + 1,529 = 11,529. The combo consumes every cola, so
     * no tier discount is shown.
     */
    @Test
    @Tag("golden")
    void gt04AQuoteAllocatesTheSeededComboAndTaxesTheAllocatedBases() {
        MvcTestResult created = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH",
                 "lines": [{"sku": "BEV-COLA-350", "qty": 4}, {"sku": "SNK-CHIPS-45", "qty": 2}]}""");

        JsonNode body = createdBody(created);
        assertThat(body.required("lines")).isEqualTo(JSON.readTree("""
                [
                  {"sku": "BEV-COLA-350", "description": "Cola 350 ml", "qty": 4, "isGift": false,
                   "unitPrice": "2500", "gross": "10000", "discounts": [],
                   "comboAllocations": [{"comboId": "%1$s", "amount": "2647"}],
                   "net": "7353", "taxableBase": "7353", "taxRate": "0.190000", "tax": "1397"},
                  {"sku": "SNK-CHIPS-45", "description": "Chips 45 g", "qty": 2, "isGift": false,
                   "unitPrice": "1800", "gross": "3600", "discounts": [],
                   "comboAllocations": [{"comboId": "%1$s", "amount": "953"}],
                   "net": "2647", "taxableBase": "2647", "taxRate": "0.050000", "tax": "132"}
                ]""".formatted(CO_COMBO)));
        assertThat(body.required("totals")).isEqualTo(JSON.readTree("""
                {"grossSubtotal": "13600",
                 "discounts": [{"origin": "COMBO:%s", "amount": "3600"}],
                 "gifts": [],
                 "taxableBase": "10000", "taxes": "1529", "total": "11529"}""".formatted(CO_COMBO)));
        assertIdentities(body);
        assertReadsBackUnchanged(CO, CO_CASH_CUSTOMER, body);
    }

    /**
     * The seeded gift rule through E-01: 20 chips are 2 whole multiples of 10 snacks, and the rule repeats, so the
     * quote gets a gift line of 2 waters. The chips are 36,000 with 1,800 of tax at 5%. The gift line costs nothing,
     * lists at 2 x 1,500 = 3,000, and Colombia taxes it at the water's 19%: 570. The taxable base is 36,000 + 3,000 =
     * 39,000, the taxes 1,800 + 570 = 2,370, and the total 36,000 + 2,370 = 38,370. The combo needs colas, so it does
     * not apply.
     */
    @Test
    void aQuoteGetsAGiftLineFromTheSeededGiftRule() {
        MvcTestResult created = post(CO, CO_CASH_CUSTOMER, """
                {"paymentMethod": "CASH", "lines": [{"sku": "SNK-CHIPS-45", "qty": 20}]}""");

        JsonNode body = createdBody(created);
        assertThat(body.required("lines")).isEqualTo(JSON.readTree("""
                [
                  {"sku": "SNK-CHIPS-45", "description": "Chips 45 g", "qty": 20, "isGift": false,
                   "unitPrice": "1800", "gross": "36000", "discounts": [], "comboAllocations": [],
                   "net": "36000", "taxableBase": "36000", "taxRate": "0.050000", "tax": "1800"},
                  {"sku": "BEV-WATER-600", "description": "Water 600 ml", "qty": 2, "isGift": true,
                   "origin": "GIFT:%s",
                   "unitPrice": "0", "gross": "0", "discounts": [], "comboAllocations": [],
                   "net": "0", "referenceValue": "3000", "taxableBase": "3000", "taxRate": "0.190000", "tax": "570"}
                ]""".formatted(CO_GIFT)));
        assertThat(body.required("totals")).isEqualTo(JSON.readTree("""
                {"grossSubtotal": "36000", "discounts": [],
                 "gifts": [{"sku": "BEV-WATER-600", "qty": 2, "origin": "GIFT:%s"}],
                 "taxableBase": "39000", "taxes": "2370", "total": "38370"}""".formatted(CO_GIFT)));
        assertIdentities(body);
        assertReadsBackUnchanged(CO, CO_CASH_CUSTOMER, body);
    }

    /**
     * The lever shapes of ec-alpha through E-01, in USD with two decimals: a family tier rule on the colas (4% from
     * unit 12, 8% from unit 24) and a gift of 2 waters for a gross subtotal from 50.00, which Ecuador does not tax.
     * The cart is 30 colas of 1.5 l at 1.45 and 15 colas of 350 ml at 0.60. The family count is filled in SKU order, so
     * the 1.5 l colas are units 1 to 30: 12 x 1.45 x 4% = 0.696 and 7 x 1.45 x 8% = 0.812, 1.508 rounded to 1.51. The
     * 350 ml colas are units 31 to 45: 15 x 0.60 x 8% = 0.72. Nets 43.50 - 1.51 = 41.99 and 9.00 - 0.72 = 8.28, taxed
     * at 15%: 6.2985 rounded to 6.30 and 1.242 rounded to 1.24. The gross of 52.50 earns the gift, which lists at
     * 2 x 0.45 = 0.90 and has no taxable base. The total is 50.27 + 7.54 = 57.81. No plantain chips, so no combo.
     */
    @Test
    void anEcuadorQuoteAppliesTheSeededFamilyTierAndAnUntaxedGiftOnTheGrossSubtotal() {
        MvcTestResult created = post(EC, EC_CASH_CUSTOMER, """
                {"paymentMethod": "CASH",
                 "lines": [{"sku": "BEV-COLA-350", "qty": 15}, {"sku": "BEV-COLA-1500", "qty": 30}]}""");

        JsonNode body = createdBody(created);
        assertThat(body.required("currency").stringValue()).isEqualTo("USD");
        assertThat(body.required("lines")).isEqualTo(JSON.readTree("""
                [
                  {"sku": "BEV-COLA-1500", "description": "Cola 1.5 l", "qty": 30, "isGift": false,
                   "unitPrice": "1.45", "gross": "43.50",
                   "discounts": [{"origin": "TIER:%1$s", "amount": "1.51"}], "comboAllocations": [],
                   "net": "41.99", "taxableBase": "41.99", "taxRate": "0.150000", "tax": "6.30"},
                  {"sku": "BEV-COLA-350", "description": "Cola 350 ml", "qty": 15, "isGift": false,
                   "unitPrice": "0.60", "gross": "9.00",
                   "discounts": [{"origin": "TIER:%1$s", "amount": "0.72"}], "comboAllocations": [],
                   "net": "8.28", "taxableBase": "8.28", "taxRate": "0.150000", "tax": "1.24"},
                  {"sku": "BEV-WATER-600", "description": "Water 600 ml", "qty": 2, "isGift": true,
                   "origin": "GIFT:%2$s",
                   "unitPrice": "0.00", "gross": "0.00", "discounts": [], "comboAllocations": [],
                   "net": "0.00", "referenceValue": "0.90", "taxableBase": "0.00", "taxRate": "0.150000",
                   "tax": "0.00"}
                ]""".formatted(EC_TIER, EC_GIFT)));
        assertThat(body.required("totals")).isEqualTo(JSON.readTree("""
                {"grossSubtotal": "52.50",
                 "discounts": [{"origin": "TIER:%1$s", "amount": "2.23"}],
                 "gifts": [{"sku": "BEV-WATER-600", "qty": 2, "origin": "GIFT:%2$s"}],
                 "taxableBase": "50.27", "taxes": "7.54", "total": "57.81"}""".formatted(EC_TIER, EC_GIFT)));
        assertIdentities(body);
        assertReadsBackUnchanged(EC, EC_CASH_CUSTOMER, body);
    }

    /**
     * The quote side of GT-06 (BR-07, SPEC section 6): a quote priced with every lever is sealed with them. Then every
     * lever of co-alpha is deactivated and the chips price rises from 1,800 to 1,900, and the quote still reads back
     * with the breakdown it was quoted with. An order row that references the quote is then inserted directly, so the
     * quote reads back as CONFIRMED, still with that breakdown. A fresh quote of the same cart prices the changed
     * catalog with no lever, and differs, which proves the test can fail. Confirming the quote through the order
     * endpoint, the other half of GT-06, is covered by QuoteConfirmRegressionIT.
     */
    @Test
    @Tag("golden")
    void aLeverDeactivatedAfterAQuoteNoLongerAppliesToAFreshQuoteWhileTheSealedQuoteKeepsIt() {
        JsonNode quoted = createdBody(post(CO, CO_CASH_CUSTOMER, EVERY_LEVER_CART));
        assertThat(quoted.required("lines")).isEqualTo(JSON.readTree(EVERY_LEVER_LINES));
        assertThat(quoted.required("totals")).isEqualTo(JSON.readTree(EVERY_LEVER_TOTALS));
        assertIdentities(quoted);
        String quoteId = quoted.required("quoteId").stringValue();

        for (String table : new String[] {"tier_rules", "combo_rules", "gift_rules"}) {
            jdbc.sql("UPDATE " + table + " SET active = false WHERE tenant_id = :tenantId")
                    .param("tenantId", CO)
                    .update();
        }
        jdbc.sql("UPDATE products SET list_price = 1900 WHERE tenant_id = :tenantId AND sku = 'SNK-CHIPS-45'")
                .param("tenantId", CO)
                .update();

        assertReadsBackUnchanged(CO, CO_CASH_CUSTOMER, quoted);
        jdbc.sql("""
                        INSERT INTO orders (tenant_id, id, quote_id, customer_id, order_number, confirmed_at)
                        VALUES (:tenantId, :orderId, :quoteId, :customerId, 'CO-000001', now())""")
                .param("tenantId", CO)
                .param("orderId", UUID.randomUUID())
                .param("quoteId", UUID.fromString(quoteId))
                .param("customerId", CO_CASH_CUSTOMER)
                .update();
        JsonNode confirmed = body(get(CO, CO_CASH_CUSTOMER, quoteId));
        assertThat(confirmed.required("status").stringValue()).isEqualTo("CONFIRMED");
        assertThat(confirmed.required("lines")).as("confirmed lines").isEqualTo(quoted.required("lines"));
        assertThat(confirmed.required("totals")).as("confirmed totals").isEqualTo(quoted.required("totals"));

        JsonNode fresh = createdBody(post(CO, CO_CASH_CUSTOMER, EVERY_LEVER_CART));
        assertThat(fresh.required("lines")).isEqualTo(JSON.readTree(EVERY_LEVER_LINES_WITHOUT_LEVERS));
        assertThat(fresh.required("totals"))
                .as("a fresh quote prices the changed catalog with no lever")
                .isEqualTo(JSON.readTree(EVERY_LEVER_TOTALS_WITHOUT_LEVERS))
                .isNotEqualTo(quoted.required("totals"));
        assertIdentities(fresh);
    }

    /**
     * The BR-05 identities, on the body as the API returns it: the gross subtotal is the paid lines' gross, the order
     * discounts sum the line discounts and combo allocations, each net is its gross less its components, the taxable
     * base is the paid nets plus the gift lines' taxable bases, the taxes sum the line taxes, and the total is the nets
     * plus the taxes.
     */
    private static void assertIdentities(JsonNode body) {
        JsonNode totals = body.required("totals");
        BigDecimal paidGross = BigDecimal.ZERO;
        BigDecimal paidNet = BigDecimal.ZERO;
        BigDecimal giftTaxableBase = BigDecimal.ZERO;
        BigDecimal lineComponents = BigDecimal.ZERO;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal taxes = BigDecimal.ZERO;
        SoftAssertions softly = new SoftAssertions();
        for (JsonNode line : body.required("lines")) {
            String sku = line.required("sku").stringValue();
            BigDecimal components = sum(line.required("discounts")).add(sum(line.required("comboAllocations")));
            softly.assertThat(amount(line, "net"))
                    .as("%s: net = gross - sum of line discounts - sum of line comboAllocations", sku)
                    .isEqualByComparingTo(amount(line, "gross").subtract(components));
            lineComponents = lineComponents.add(components);
            net = net.add(amount(line, "net"));
            taxes = taxes.add(amount(line, "tax"));
            if (line.required("isGift").booleanValue()) {
                giftTaxableBase = giftTaxableBase.add(amount(line, "taxableBase"));
            } else {
                paidGross = paidGross.add(amount(line, "gross"));
                paidNet = paidNet.add(amount(line, "net"));
            }
        }
        softly.assertThat(amount(totals, "grossSubtotal")).as("grossSubtotal = sum of paid line gross")
                .isEqualByComparingTo(paidGross);
        softly.assertThat(sum(totals.required("discounts")))
                .as("sum of discounts[].amount = sum of line discounts + sum of line comboAllocations")
                .isEqualByComparingTo(lineComponents);
        softly.assertThat(amount(totals, "taxableBase"))
                .as("taxableBase = sum of paid line net + sum of gift line taxableBase")
                .isEqualByComparingTo(paidNet.add(giftTaxableBase));
        softly.assertThat(amount(totals, "taxes")).as("taxes = sum of line tax").isEqualByComparingTo(taxes);
        softly.assertThat(amount(totals, "total")).as("total = sum of line net + taxes")
                .isEqualByComparingTo(net.add(amount(totals, "taxes")));
        softly.assertAll();
    }

    /** E-02 returns the body E-01 created, the full breakdown included. */
    private void assertReadsBackUnchanged(String tenant, UUID customer, JsonNode created) {
        MvcTestResult read = get(tenant, customer, created.required("quoteId").stringValue());
        assertThat(read).hasStatus(HttpStatus.OK);
        assertThat(body(read)).isEqualTo(created);
    }

    private static BigDecimal sum(JsonNode entries) {
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode entry : entries) {
            sum = sum.add(amount(entry, "amount"));
        }
        return sum;
    }

    private static BigDecimal amount(JsonNode node, String field) {
        return new BigDecimal(node.required(field).stringValue());
    }

    private MvcTestResult post(String tenant, UUID customer, String body) {
        return mvc.post().uri(QUOTES)
                .header(QuoteController.TENANT_HEADER, tenant)
                .header(QuoteController.CUSTOMER_HEADER, customer.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .exchange();
    }

    private MvcTestResult get(String tenant, UUID customer, String quoteId) {
        return mvc.get().uri(QUOTES + "/{quoteId}", quoteId)
                .header(QuoteController.TENANT_HEADER, tenant)
                .header(QuoteController.CUSTOMER_HEADER, customer.toString())
                .exchange();
    }

    private static JsonNode createdBody(MvcTestResult created) {
        assertThat(created).hasStatus(HttpStatus.CREATED);
        return body(created);
    }

    private static JsonNode body(MvcTestResult result) {
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }
}
