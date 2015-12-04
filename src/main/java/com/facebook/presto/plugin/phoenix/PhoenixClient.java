/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.phoenix;

import com.facebook.presto.plugin.jdbc.BaseJdbcClient;
import com.facebook.presto.plugin.jdbc.BaseJdbcConfig;
import com.facebook.presto.plugin.jdbc.JdbcConnectorId;
import com.facebook.presto.plugin.jdbc.JdbcOutputTableHandle;
import com.facebook.presto.plugin.jdbc.JdbcTableHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.type.Type;
import com.google.common.base.Joiner;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slice;
import org.apache.phoenix.jdbc.PhoenixDriver;

import javax.inject.Inject;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Set;

import static com.facebook.presto.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.Maps.fromProperties;
import static java.util.Collections.nCopies;
import static java.util.Locale.ENGLISH;

public class PhoenixClient extends BaseJdbcClient
{
    @Inject
    public PhoenixClient(JdbcConnectorId connectorId, BaseJdbcConfig config, PhoenixClientConfig phoenixClientConfig)
            throws SQLException
    {
        super(connectorId, config, "\"", new PhoenixDriver());
    }

    @Override
    public Set<String> getSchemaNames()
    {
        try (Connection connection = driver.connect(connectionUrl, connectionProperties);
                ResultSet resultSet = connection.getMetaData().getSchemas()) {
            ImmutableSet.Builder<String> schemaNames = ImmutableSet.builder();
            while (resultSet.next()) {
                String schemaName = resultSet.getString("TABLE_SCHEM");
                if (schemaName == null) {
                    schemaName = "";
                }
                schemaName = schemaName.toLowerCase(ENGLISH);
                // skip internal schemas
                if (!schemaName.equals("system")) {
                    schemaNames.add(schemaName);
                }
            }
            return schemaNames.build();
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    @Override
    public void dropTable(JdbcTableHandle handle)
    {
        StringBuilder sql = new StringBuilder()
                .append("DROP TABLE ")
                .append(quoted(handle.getSchemaName(), handle.getTableName()));

        try (Connection connection = driver.connect(connectionUrl, connectionProperties)) {
            execute(connection, sql.toString());
        }
        catch (SQLException e) {
            throw Throwables.propagate(e);
        }
    }

    private String quoted(String schema, String table)
    {
        StringBuilder sb = new StringBuilder();
        if (!isNullOrEmpty(schema)) {
            sb.append(quoted(schema)).append(".");
        }
        sb.append(quoted(table));
        return sb.toString();
    }

    @Override
    public JdbcOutputTableHandle beginCreateTable(ConnectorTableMetadata tableMetadata)
    {
        SchemaTableName schemaTableName = tableMetadata.getTable();
        String schema = schemaTableName.getSchemaName();
        String table = schemaTableName.getTableName();

        if (!getSchemaNames().contains(schema)) {
            throw new PrestoException(NOT_FOUND, "Schema not found: " + schema);
        }

        try (Connection connection = driver.connect(connectionUrl, connectionProperties)) {
            boolean uppercase = connection.getMetaData().storesUpperCaseIdentifiers();
            if (uppercase) {
                schema = schema.toUpperCase(ENGLISH);
                table = table.toUpperCase(ENGLISH);
            }
            String catalog = connection.getCatalog();

            // String temporaryName = "tmp_presto_" + UUID.randomUUID().toString().replace("-", "");
            String temporaryName = "ctas_presto_" + table;
            StringBuilder sql = new StringBuilder()
                    .append("CREATE TABLE ")
                    .append(quoted(schema, temporaryName))
                    .append(" (");
            sql.append("UUID BIGINT NOT NULL PRIMARY KEY,");
            ImmutableList.Builder<String> columnNames = ImmutableList.builder();
            ImmutableList.Builder<Type> columnTypes = ImmutableList.builder();
            ImmutableList.Builder<String> columnList = ImmutableList.builder();
            for (ColumnMetadata column : tableMetadata.getColumns()) {
                String columnName = column.getName();
                if (uppercase) {
                    columnName = columnName.toUpperCase(ENGLISH);
                }
                columnNames.add(columnName);
                columnTypes.add(column.getType());
                columnList.add(new StringBuilder()
                        .append(quoted(columnName))
                        .append(" ")
                        .append(toSqlType(column.getType()))
                        .toString());
            }
            Joiner.on(", ").appendTo(sql, columnList.build());
            sql.append(")");

            // FIXME
            execute(connection, "DROP TABLE IF EXISTS " + quoted(schema, temporaryName));
            execute(connection, "CREATE SEQUENCE " + "seq_" + temporaryName);
            execute(connection, sql.toString());

            return new JdbcOutputTableHandle(
                    connectorId,
                    catalog,
                    schema,
                    table,
                    columnNames.build(),
                    columnTypes.build(),
                    tableMetadata.getOwner(),
                    temporaryName,
                    connectionUrl,
                    fromProperties(connectionProperties));
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
    }

    @Override
    public void commitCreateTable(JdbcOutputTableHandle handle, Collection<Slice> fragments)
    {
        // StringBuilder sql = new StringBuilder()
        // .append("ALTER TABLE ")
        // .append(quoted(handle.getCatalogName(), handle.getSchemaName(), handle.getTemporaryTableName()))
        // .append(" RENAME TO ")
        // .append(quoted(handle.getCatalogName(), handle.getSchemaName(), handle.getTableName()));
        StringBuilder sql = new StringBuilder()
                .append("DROP SEQUENCE seq_" + handle.getTemporaryTableName());

        try (Connection connection = getConnection(handle)) {
            execute(connection, sql.toString());
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }

    }

    @Override
    public String buildInsertSql(JdbcOutputTableHandle handle)
    {
        // FIXME
        String vars = Joiner.on(',').join(nCopies(handle.getColumnNames().size(), "?"));
        return new StringBuilder().append("UPSERT INTO ")
                .append(quoted(handle.getSchemaName(), handle.getTemporaryTableName()))
                .append(" VALUES (")
                .append("NEXT VALUE FOR " + "seq_" + handle.getTemporaryTableName() + ",")
                .append(vars).append(")").toString();
    }
}
