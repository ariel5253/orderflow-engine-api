package com.orderflow.engine.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import liquibase.Scope;
import liquibase.analytics.configuration.AnalyticsArgs;
import liquibase.command.CommandScope;
import liquibase.command.core.RollbackCountCommandStep;
import liquibase.command.core.UpdateCommandStep;
import liquibase.command.core.helpers.DatabaseChangelogCommandStep;
import liquibase.command.core.helpers.DbUrlConnectionArgumentsCommandStep;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Every changeset has a rollback (DEC-20). The Guatemala and Peru seeds (SPEC section 5) are rolled back first and must
 * remove everything they added, their order number sequences included, and nothing else. The renaming of the seeded
 * rows into the tenants' language (SPEC section 10.1) is rolled back next and must restore the exact names the seeds
 * inserted. The Colombia and Ecuador seeds are rolled back next and must leave every table empty, then the remaining
 * changesets must leave an empty schema, and the changelogs must apply again on top of it.
 */
@Testcontainers
class LiquibaseRollbackIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String CO_EC_SEED_CHANGELOG = "db/changelog/2026/2026-10-15-006-seed-co-and-ec.yaml";
    private static final String SEED_NAMES_CHANGELOG = "db/changelog/2026/2026-10-16-001-seed-names-in-spanish.yaml";
    private static final String GT_PE_SEED_CHANGELOG = "db/changelog/2026/2026-10-16-002-seed-gt-and-pe.yaml";

    private static final List<String> ALL_TENANTS = List.of("co-alpha", "ec-alpha", "gt-alpha", "pe-alpha");

    /** The order number sequences of Guatemala and Peru, as {@link #SCHEMA_OBJECTS} lists them. */
    private static final List<String> GT_AND_PE_SEQUENCES = List.of("S order_number_seq_gt", "S order_number_seq_pe");

    /** Every table that holds tenant data: the Guatemala and Peru seeds write rows into each of them. */
    private static final List<String> TENANT_SCOPED_SEED_TABLES = List.of("combo_items", "combo_rules",
            "credit_accounts", "customers", "families", "gift_rules", "products", "tier_brackets", "tier_rules");

    /** Each seeded product as "tenant SKU name", in tenant and SKU order. */
    private static final String PRODUCT_NAMES = """
            SELECT tenant_id || ' ' || sku || ' ' || name FROM products ORDER BY tenant_id, sku COLLATE "C"
            """;

    /** Each seeded customer as "tenant id name", in tenant and id order. */
    private static final String CUSTOMER_NAMES = """
            SELECT tenant_id || ' ' || id || ' ' || name FROM customers ORDER BY tenant_id, id
            """;

    /** The Colombia and Ecuador product names in the tenants' language. */
    private static final List<String> SPANISH_PRODUCT_NAMES = List.of(
            "co-alpha BEV-COLA-1500 Gaseosa cola 1,5 l",
            "co-alpha BEV-COLA-350 Gaseosa cola 350 ml",
            "co-alpha BEV-WATER-600 Agua 600 ml",
            "co-alpha SNK-CHIPS-45 Papas fritas 45 g",
            "ec-alpha BEV-COLA-1500 Gaseosa cola 1,5 l",
            "ec-alpha BEV-COLA-350 Gaseosa cola 350 ml",
            "ec-alpha BEV-WATER-600 Agua 600 ml",
            "ec-alpha SNK-PLANTAIN-45 Chifles 45 g");

    private static final List<String> SPANISH_CUSTOMER_NAMES = List.of(
            "co-alpha 01a13cdb-cc00-7001-8001-000000000001 Tienda Norte",
            "co-alpha 01a13cdb-cc00-7001-8001-000000000002 Tienda Sur",
            "ec-alpha 01a13cdb-cc00-7002-8001-000000000001 Tienda Quito",
            "ec-alpha 01a13cdb-cc00-7002-8001-000000000002 Tienda Guayaquil");

    /** The Guatemala and Peru product names, which their seeds write in the tenants' language from the start. */
    private static final List<String> GT_AND_PE_PRODUCT_NAMES = List.of(
            "gt-alpha BEV-COLA-1500 Gaseosa cola 1,5 l",
            "gt-alpha BEV-COLA-350 Gaseosa cola 350 ml",
            "gt-alpha BEV-WATER-600 Agua 600 ml",
            "gt-alpha SNK-CHIPS-45 Papas fritas 45 g",
            "pe-alpha BEV-COLA-1500 Gaseosa cola 1,5 l",
            "pe-alpha BEV-COLA-350 Gaseosa cola 350 ml",
            "pe-alpha BEV-WATER-600 Agua 600 ml",
            "pe-alpha SNK-CHIPS-45 Papas fritas 45 g");

    private static final List<String> GT_AND_PE_CUSTOMER_NAMES = List.of(
            "gt-alpha 01a13cdb-cc00-7003-8001-000000000001 Tienda Ciudad de Guatemala",
            "gt-alpha 01a13cdb-cc00-7003-8001-000000000002 Tienda Quetzaltenango",
            "pe-alpha 01a13cdb-cc00-7004-8001-000000000001 Tienda Lima",
            "pe-alpha 01a13cdb-cc00-7004-8001-000000000002 Tienda Arequipa");

    /** The product names the seeds inserted, which the rollback of the renaming restores. */
    private static final List<String> SEEDED_PRODUCT_NAMES = List.of(
            "co-alpha BEV-COLA-1500 Cola 1.5 l",
            "co-alpha BEV-COLA-350 Cola 350 ml",
            "co-alpha BEV-WATER-600 Water 600 ml",
            "co-alpha SNK-CHIPS-45 Chips 45 g",
            "ec-alpha BEV-COLA-1500 Cola 1.5 l",
            "ec-alpha BEV-COLA-350 Cola 350 ml",
            "ec-alpha BEV-WATER-600 Water 600 ml",
            "ec-alpha SNK-PLANTAIN-45 Plantain chips 45 g");

    private static final List<String> SEEDED_CUSTOMER_NAMES = List.of(
            "co-alpha 01a13cdb-cc00-7001-8001-000000000001 North Corner Shop",
            "co-alpha 01a13cdb-cc00-7001-8001-000000000002 South Corner Shop",
            "ec-alpha 01a13cdb-cc00-7002-8001-000000000001 Quito Corner Shop",
            "ec-alpha 01a13cdb-cc00-7002-8001-000000000002 Guayaquil Corner Shop");

    /** Relations, indexes, sequences and constraints of the application schema, without the Liquibase tables. */
    private static final String SCHEMA_OBJECTS = """
            SELECT c.relkind::text || ' ' || c.relname
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = current_schema() AND c.relname NOT LIKE 'databasechangelog%'
            UNION ALL
            SELECT 'constraint ' || t.relname || '.' || k.conname || ' ' || pg_get_constraintdef(k.oid)
            FROM pg_constraint k
            JOIN pg_class t ON t.oid = k.conrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname = current_schema() AND t.relname NOT LIKE 'databasechangelog%'
            ORDER BY 1
            """;

    /** The tables with a tenant_id column. */
    private static final String TENANT_SCOPED_TABLES = """
            SELECT table_name FROM information_schema.columns
            WHERE table_schema = current_schema() AND column_name = 'tenant_id'
              AND table_name NOT LIKE 'databasechangelog%'
            ORDER BY 1
            """;

    private static final String TABLES = """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
              AND table_name NOT LIKE 'databasechangelog%'
            ORDER BY 1
            """;

    /** The changesets of one changelog and every changeset applied after them, the last ones in execution order. */
    private static final String CHANGESETS_FROM = """
            SELECT count(*) FROM databasechangelog
            WHERE orderexecuted >= (SELECT min(orderexecuted) FROM databasechangelog WHERE filename = ?)
            """;

    @Test
    void everyChangesetRollsBackToAnEmptySchemaAndTheChangelogsApplyAgain() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));

            update(database);
            List<String> schema = strings(connection, SCHEMA_OBJECTS);
            assertThat(schema).as("schema objects after the first update").containsAll(GT_AND_PE_SEQUENCES);
            assertFourCountries(connection, "after the first update");
            assertThat(tablesWithRowsOfGuatemalaOrPeru(connection))
                    .as("tables with Guatemala or Peru rows after the first update")
                    .containsExactlyElementsOf(TENANT_SCOPED_SEED_TABLES);

            rollback(database, count(connection, CHANGESETS_FROM, GT_PE_SEED_CHANGELOG));
            assertThat(tablesWithRowsOfGuatemalaOrPeru(connection))
                    .as("tables with Guatemala or Peru rows after rolling back their seeds")
                    .isEmpty();
            assertThat(strings(connection, "SELECT id FROM tenants ORDER BY id"))
                    .as("tenants after rolling back the Guatemala and Peru seeds")
                    .containsExactly("co-alpha", "ec-alpha");
            assertThat(strings(connection, "SELECT code FROM countries ORDER BY code"))
                    .as("countries after rolling back the Guatemala and Peru seeds")
                    .containsExactly("CO", "EC");
            assertThat(strings(connection, "SELECT code FROM currencies ORDER BY code"))
                    .as("currencies after rolling back the Guatemala and Peru seeds")
                    .containsExactly("COP", "USD");
            assertThat(strings(connection, SCHEMA_OBJECTS))
                    .as("schema objects after rolling back the Guatemala and Peru seeds")
                    .isEqualTo(schema.stream().filter(object -> !GT_AND_PE_SEQUENCES.contains(object)).toList());
            assertNames(connection, SPANISH_PRODUCT_NAMES, SPANISH_CUSTOMER_NAMES,
                    "after rolling back the Guatemala and Peru seeds");

            rollback(database, count(connection, CHANGESETS_FROM, SEED_NAMES_CHANGELOG));
            assertNames(connection, SEEDED_PRODUCT_NAMES, SEEDED_CUSTOMER_NAMES, "after rolling back the renaming");

            rollback(database, count(connection, CHANGESETS_FROM, CO_EC_SEED_CHANGELOG));
            assertThat(tablesWithRows(connection)).as("tables with rows after rolling back the seeds").isEmpty();

            rollback(database, count(connection, "SELECT count(*) FROM databasechangelog"));
            assertThat(strings(connection, SCHEMA_OBJECTS))
                    .as("schema objects after rolling back every changeset")
                    .isEmpty();

            update(database);
            assertThat(strings(connection, SCHEMA_OBJECTS)).as("schema after updating again").isEqualTo(schema);
            assertFourCountries(connection, "after updating again");
        }
    }

    /** The four seeded tenants, their countries and currencies, and every seeded name in the tenants' language. */
    private static void assertFourCountries(Connection connection, String when) throws SQLException {
        assertThat(strings(connection, "SELECT id FROM tenants ORDER BY id"))
                .as("seeded tenants " + when)
                .containsExactlyElementsOf(ALL_TENANTS);
        assertThat(strings(connection, "SELECT code FROM countries ORDER BY code"))
                .as("seeded countries " + when)
                .containsExactly("CO", "EC", "GT", "PE");
        assertThat(strings(connection, "SELECT code FROM currencies ORDER BY code"))
                .as("seeded currencies " + when)
                .containsExactly("COP", "GTQ", "PEN", "USD");
        assertNames(connection, concat(SPANISH_PRODUCT_NAMES, GT_AND_PE_PRODUCT_NAMES),
                concat(SPANISH_CUSTOMER_NAMES, GT_AND_PE_CUSTOMER_NAMES), when);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    /** The tenant-scoped tables that hold a row of gt-alpha or pe-alpha. */
    private static List<String> tablesWithRowsOfGuatemalaOrPeru(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        for (String table : strings(connection, TENANT_SCOPED_TABLES)) {
            String rows = "SELECT count(*) FROM \"" + table + "\" WHERE tenant_id IN ('gt-alpha', 'pe-alpha')";
            if (count(connection, rows) > 0) {
                tables.add(table);
            }
        }
        return tables;
    }

    private static void assertNames(Connection connection, List<String> products, List<String> customers,
            String when) throws SQLException {
        assertThat(strings(connection, PRODUCT_NAMES)).as("product names " + when)
                .containsExactlyElementsOf(products);
        assertThat(strings(connection, CUSTOMER_NAMES)).as("customer names " + when)
                .containsExactlyElementsOf(customers);
    }

    private static void update(Database database) throws Exception {
        inLiquibaseScope(() -> new CommandScope(UpdateCommandStep.COMMAND_NAME)
                .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                .addArgumentValue(UpdateCommandStep.CHANGELOG_FILE_ARG, MASTER_CHANGELOG)
                .execute());
    }

    private static void rollback(Database database, int changesets) throws Exception {
        inLiquibaseScope(() -> new CommandScope(RollbackCountCommandStep.COMMAND_NAME)
                .addArgumentValue(DbUrlConnectionArgumentsCommandStep.DATABASE_ARG, database)
                .addArgumentValue(DatabaseChangelogCommandStep.CHANGELOG_FILE_ARG, MASTER_CHANGELOG)
                .addArgumentValue(RollbackCountCommandStep.COUNT_ARG, changesets)
                .execute());
    }

    /** Changelogs are read from the classpath, as the service reads them, and the test makes no outbound call. */
    private static void inLiquibaseScope(Scope.ScopedRunner command) throws Exception {
        Scope.child(Map.of(
                Scope.Attr.resourceAccessor.name(), new ClassLoaderResourceAccessor(),
                AnalyticsArgs.ENABLED.getKey(), false), command);
    }

    private static List<String> tablesWithRows(Connection connection) throws SQLException {
        List<String> tablesWithRows = new ArrayList<>();
        for (String table : strings(connection, TABLES)) {
            if (count(connection, "SELECT count(*) FROM \"" + table + "\"") > 0) {
                tablesWithRows.add(table);
            }
        }
        return tablesWithRows;
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rows = statement.executeQuery()) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static int count(Connection connection, String sql, String... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
