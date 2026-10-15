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
 * Every changeset has a rollback (DEC-20). The seeds are rolled back first and must leave every table empty, then
 * the remaining changesets must leave an empty schema, and the changelogs must apply again on top of it.
 */
@Testcontainers
class LiquibaseRollbackIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    private static final String MASTER_CHANGELOG = "db/changelog/db.changelog-master.yaml";
    private static final String SEED_CHANGELOG = "db/changelog/2026/2026-10-15-006-seed-co-and-ec.yaml";

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

    private static final String TABLES = """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
              AND table_name NOT LIKE 'databasechangelog%'
            ORDER BY 1
            """;

    /** The seed changesets and every changeset applied after them, the last ones in execution order. */
    private static final String CHANGESETS_FROM_THE_SEEDS = """
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
            assertThat(strings(connection, "SELECT id FROM tenants ORDER BY id"))
                    .as("seeded tenants after the first update")
                    .containsExactly("co-alpha", "ec-alpha");

            rollback(database, count(connection, CHANGESETS_FROM_THE_SEEDS, SEED_CHANGELOG));
            assertThat(tablesWithRows(connection)).as("tables with rows after rolling back the seeds").isEmpty();

            rollback(database, count(connection, "SELECT count(*) FROM databasechangelog"));
            assertThat(strings(connection, SCHEMA_OBJECTS))
                    .as("schema objects after rolling back every changeset")
                    .isEmpty();

            update(database);
            assertThat(strings(connection, SCHEMA_OBJECTS)).as("schema after updating again").isEqualTo(schema);
            assertThat(strings(connection, "SELECT id FROM tenants ORDER BY id"))
                    .as("seeded tenants after updating again")
                    .containsExactly("co-alpha", "ec-alpha");
        }
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
