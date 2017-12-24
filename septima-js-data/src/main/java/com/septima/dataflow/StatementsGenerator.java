package com.septima.dataflow;

import com.septima.DataTypes;
import com.septima.NamedValue;
import com.septima.changes.*;
import com.septima.entities.SqlEntities;
import com.septima.entities.SqlEntity;
import com.septima.jdbc.NamedJdbcValue;
import com.septima.jdbc.UncheckedSQLException;
import com.septima.metadata.Field;
import com.septima.metadata.JdbcColumn;
import com.septima.queries.SqlQuery;

import java.sql.Connection;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Writer for jdbc data sources. Performs writing indices data. There are two modes
 * indices database updating. The first one "write mode" is update/delete/insert
 * statements preparation and batch execution. The second one "log mode" is
 * logging indices statements transform be executed with parameters values. In log mode no
 * execution is performed.
 *
 * @author mg
 */
public class StatementsGenerator implements ChangesVisitor {

    public interface TablesContainer {

        Optional<Map<String, JdbcColumn>> getTableColumns(String aTableName) throws SQLException;
    }

    public interface GeometryConverter {

        NamedJdbcValue convertGeometry(String aValue, Connection aConnection) throws SQLException;
    }

    private static class NamedGeometryValue extends NamedValue {

        NamedGeometryValue(String aName, Object aValue) {
            super(aName, aValue);
        }
    }

    /**
     * Stores short living information about statements, transform be executed while
     * jdbc update process. Performs parametrized prepared statements
     * execution.
     */
    public static class GeneratedStatement {

        private static final Logger QUERIES_LOGGER = Logger.getLogger(GeneratedStatement.class.getName());
        private final String clause;
        private final List<NamedValue> parameters;
        private final GeometryConverter geometryConverter;

        GeneratedStatement(String aClause, List<NamedValue> aParameters, GeometryConverter aGeometryConverter) {
            super();
            clause = aClause;
            parameters = aParameters;
            geometryConverter = aGeometryConverter;
        }

        public int apply(Connection aConnection) throws SQLException {
            try (PreparedStatement stmt = aConnection.prepareStatement(clause)) {
                ParameterMetaData pMeta = stmt.getParameterMetaData();
                for (int i = 1; i <= parameters.size(); i++) {
                    NamedValue v = parameters.get(i - 1);
                    Object value;
                    int jdbcType;
                    String sqlTypeName;
                    if (v instanceof NamedJdbcValue) {
                        NamedJdbcValue jv = (NamedJdbcValue) v;
                        value = jv.getValue();
                        jdbcType = jv.getJdbcType();
                        sqlTypeName = jv.getSqlTypeName();
                    } else if (v instanceof NamedGeometryValue) {
                        NamedJdbcValue jv = geometryConverter.convertGeometry(v.getValue() != null ? v.getValue().toString() : null, aConnection);
                        value = jv.getValue();
                        jdbcType = jv.getJdbcType();
                        sqlTypeName = jv.getSqlTypeName();
                    } else {
                        value = v.getValue();
                        try {
                            jdbcType = pMeta.getParameterType(i);
                            sqlTypeName = pMeta.getParameterTypeName(i);
                        } catch (SQLException | UncheckedSQLException ex) {
                            Logger.getLogger(StatementsGenerator.class.getName()).log(Level.WARNING, null, ex);
                            jdbcType = JdbcDataProvider.assumeJdbcType(v.getValue());
                            sqlTypeName = null;
                        }
                    }
                    JdbcDataProvider.assign(value, i, stmt, jdbcType, sqlTypeName);
                }
                if (QUERIES_LOGGER.isLoggable(Level.FINE)) {
                    QUERIES_LOGGER.log(Level.FINE, "Executing sql with {0} parameters: {1}", new Object[]{parameters.size(), clause});
                }
                return stmt.executeUpdate();
            }
        }
    }

    private static final String INSERT_CLAUSE = "insert into %s (%s) values (%s)";
    private static final String DELETE_CLAUSE = "delete from %s where %s";
    private static final String UPDATE_CLAUSE = "update %s set %s where %s";

    private final List<GeneratedStatement> logEntries = new ArrayList<>();
    private final SqlEntities entities;
    private final TablesContainer tables;
    private final GeometryConverter geometryConverter;

    public StatementsGenerator(SqlEntities aEntities, TablesContainer aTables, GeometryConverter aGeometryConverter) {
        super();
        entities = aEntities;
        tables = aTables;
        geometryConverter = aGeometryConverter;
    }

    public List<GeneratedStatement> getLogEntries() {
        return logEntries;
    }

    private NamedValue bindNamedValueToTable(final String aTableName, final String aColumnName, final Object aValue) throws SQLException {
        return tables.getTableColumns(aTableName)
                .map(tableFields -> tableFields.get(aColumnName))
                .map(tableField -> (NamedValue) new NamedJdbcValue(aColumnName, aValue, tableField.getJdbcType(), tableField.getType()))
                .orElseGet(() -> new NamedValue(aColumnName, aValue));
    }

    private Function<NamedValue, Map.Entry<String, List<NamedValue>>> asTableDatumEntry(String aEntityName) {
        return datum -> {
            try {
                SqlEntity entity = entities.loadEntity(aEntityName);
                Field entityField = entity.getFields().get(datum.getName());
                if (entityField != null) {
                    String keyColumnName = entityField.getOriginalName() != null ? entityField.getOriginalName() : entityField.getName();
                    NamedValue bound = DataTypes.GEOMETRY_TYPE_NAME.equals(entityField.getType()) ?
                            new NamedGeometryValue(keyColumnName, datum.getValue()) :
                            bindNamedValueToTable(entityField.getTableName(), keyColumnName, datum.getValue());
                    return Map.entry(entityField.getTableName(), List.of(bound));
                } else {
                    throw new IllegalStateException("Entity field '" + datum.getName() + "' is not found in entity '" + aEntityName + "'");
                }
            } catch (SQLException ex) {
                throw new UncheckedSQLException(ex);
            }
        };
    }

    @Override
    public void visit(Insert aInsert) {
        aInsert.getData().stream()
                .map(asTableDatumEntry(aInsert.getEntityName()))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.flatMapping(entry -> entry.getValue().stream(), Collectors.toList())))
                .entrySet().stream()
                .filter(entry -> {
                    SqlEntity entity = entities.loadEntity(aInsert.getEntityName());
                    return !entry.getValue().isEmpty() &&
                            (entity.getWritable().isEmpty() || entity.getWritable().contains(entry.getKey()));
                })
                .map(entry -> new GeneratedStatement(
                        String.format(INSERT_CLAUSE,
                                entry.getKey(),
                                generateInsertColumnsClause(entry.getValue()),
                                generatePlaceholders(entry.getValue().size())
                        ),
                        Collections.unmodifiableList(entry.getValue()),
                        geometryConverter
                ))
                .forEach(logEntries::add);
    }

    @Override
    public void visit(Update aUpdate) {
        Map<String, List<NamedValue>> updatesKeys = aUpdate.getKeys().stream()
                .map(asTableDatumEntry(aUpdate.getEntityName()))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.flatMapping(entry -> entry.getValue().stream(), Collectors.toList())));
        aUpdate.getData().stream()
                .map(asTableDatumEntry(aUpdate.getEntityName()))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.flatMapping(entry -> entry.getValue().stream(), Collectors.toList())))
                .entrySet().stream()
                .filter(entry -> {
                    SqlEntity entity = entities.loadEntity(aUpdate.getEntityName());
                    return !entry.getValue().isEmpty() && !updatesKeys.getOrDefault(entry.getKey(), List.of()).isEmpty() &&
                            (entity.getWritable().isEmpty() || entity.getWritable().contains(entry.getKey()));
                })
                .map(entry -> new GeneratedStatement(
                        String.format(UPDATE_CLAUSE,
                                entry.getKey(),
                                generateUpdateColumnsClause(entry.getValue()),
                                generateWhereClause(updatesKeys.getOrDefault(entry.getKey(), List.of()))
                        ),
                        Collections.unmodifiableList(concat(entry.getValue(), updatesKeys.getOrDefault(entry.getKey(), List.of()))),
                        geometryConverter
                ))
                .forEach(logEntries::add);
    }

    /**
     * Generates deletion statements for an entity for all underlying tables.
     * It can reorder deletion statements for tables.
     * So, you should avoid deletion from compound entities interlinked with foreign keys.
     * In general, you shouldn't meet such case, because interlinked tables should interact via
     * foreign keys rather than via this multi tables deletion.
     *
     * @param aDeletion Deletion command transform delete from all underlying tables indices an entity
     */
    @Override
    public void visit(Delete aDeletion) {
        aDeletion.getKeys().stream()
                .map(asTableDatumEntry(aDeletion.getEntityName()))
                .collect(Collectors.groupingBy(Map.Entry::getKey,
                        Collectors.flatMapping(entry -> entry.getValue().stream(), Collectors.toList())))
                .entrySet().stream()
                .filter(entry -> {
                    SqlEntity entity = entities.loadEntity(aDeletion.getEntityName());
                    return !entry.getValue().isEmpty() &&
                            (entity.getWritable().isEmpty() || entity.getWritable().contains(entry.getKey()));
                })
                .map(entry -> new GeneratedStatement(
                        String.format(DELETE_CLAUSE,
                                entry.getKey(),
                                generateWhereClause(entry.getValue())
                        ),
                        Collections.unmodifiableList(entry.getValue()),
                        geometryConverter
                ))
                .forEach(logEntries::add);
    }

    @Override
    public void visit(Command aChange) {
        SqlEntity entity = entities.loadEntity(aChange.getEntityName());
        SqlQuery query = entity.toQuery();
        logEntries.add(new GeneratedStatement(
                query.getSqlClause(),
                Collections.unmodifiableList(query.getParameters().stream()
                        .map(queryParameter -> {
                            NamedValue commandValue = aChange.getParameters().getOrDefault(queryParameter.getName(), new NamedValue(queryParameter.getName(), null));
                            if (commandValue.getValue() != null && DataTypes.GEOMETRY_TYPE_NAME.equals(queryParameter.getType())) {
                                return new NamedGeometryValue(commandValue.getName(), commandValue.getValue());
                            } else {
                                return new NamedJdbcValue(commandValue.getName(), commandValue.getValue(), JdbcDataProvider.calcJdbcType(queryParameter.getType(), commandValue.getValue()), null);
                            }
                        })
                        .collect(Collectors.toList())),
                geometryConverter
        ));
    }

    private static String generatePlaceholders(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    /**
     * Generates sql where clause string for values array passed in. It's assumed
     * that key columns may not have NULL values. This assumption is made
     * because we use simple "=" operator in WHERE clause.
     *
     * @param keys Keys array transform deal with.
     * @return Generated Where clause.
     */
    private String generateWhereClause(List<NamedValue> keys) {
        return keys.stream()
                .map(datum -> new StringBuilder(datum.getName()).append(" = ?"))
                .reduce((name1, name2) -> name1.append(" and ").append(name2))
                .map(StringBuilder::toString)
                .orElse("");
    }

    private static String generateUpdateColumnsClause(List<NamedValue> data) {
        return data.stream()
                .map(datum -> new StringBuilder(datum.getName()).append(" = ?"))
                .reduce((name1, name2) -> name1.append(", ").append(name2))
                .map(StringBuilder::toString)
                .orElse("");
    }

    private static String generateInsertColumnsClause(List<NamedValue> data) {
        return data.stream()
                .map(datum -> new StringBuilder(datum.getName()))
                .reduce((name1, name2) -> name1.append(", ").append(name2))
                .map(StringBuilder::toString)
                .orElse("");
    }

    private static <E> List<E> concat(List<E> first, List<E> second) {
        List<E> general = new ArrayList<>(first.size() + second.size());
        general.addAll(first);
        general.addAll(second);
        return general;
    }
}
