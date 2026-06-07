/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2005-2005 Julian Hyde
// Copyright (C) 2005-2013 Pentaho
// All Rights Reserved.
*/
package mondrian.rolap.agg;

import mondrian.olap.Util;
import mondrian.rolap.*;
import mondrian.rolap.sql.*;
import mondrian.util.Pair;

import java.util.*;

/**
 * Provides the information necessary to generate SQL for a drill-through
 * request.
 *
 * @author jhyde
 * @author Richard M. Emberson
 */
class DrillThroughQuerySpec extends AbstractQuerySpec {
    private final DrillThroughCellRequest request;
    private final List<StarPredicate> listOfStarPredicates;
    private final List<String> columnNames;
    // #106 (SECURITY): the active role + parameter context whose predicate
    // grants must be enforced on this drill-through. May be null (unsecured).
    private final mondrian.olap.Role role;
    private final mondrian.calcite.QueryParameterContext paramContext;

    public DrillThroughQuerySpec(
        DrillThroughCellRequest request,
        StarPredicate starPredicateSlicer,
        boolean countOnly)
    {
        this(request, starPredicateSlicer, countOnly, null, null);
    }

    public DrillThroughQuerySpec(
        DrillThroughCellRequest request,
        StarPredicate starPredicateSlicer,
        boolean countOnly,
        mondrian.olap.Role role,
        mondrian.calcite.QueryParameterContext paramContext)
    {
        super(request.getMeasure().getStar(), countOnly);
        this.request = request;
        this.role = role;
        this.paramContext = paramContext;
        if (starPredicateSlicer != null) {
            this.listOfStarPredicates =
                Collections.singletonList(starPredicateSlicer);
        } else {
            this.listOfStarPredicates = Collections.emptyList();
        }
        int maxColumnNameLength =
            getStar().getSqlQueryDialect().getMaxColumnNameLength();
        if (maxColumnNameLength == 0) {
            // From java.sql.DatabaseMetaData: "a result of zero means that
            // there is no limit or the limit is not known"
            maxColumnNameLength = Integer.MAX_VALUE;
        }
        this.columnNames =
            computeDistinctColumnNames(request, maxColumnNameLength);
    }

    private static List<String> computeDistinctColumnNames(
        DrillThroughCellRequest request, int maxColumnNameLength)
    {
        final List<String> columnNames = new ArrayList<String>();
        final Set<String> columnNameSet = new HashSet<String>();

        for (RolapStar.Column column : request.getConstrainedColumns()) {
            addColumnName(
                request.getColumnAlias(column),
                column,
                maxColumnNameLength,
                columnNames,
                columnNameSet);
        }

        addColumnName(
            request.getMeasure().getName(),
            request.getMeasure(),
            maxColumnNameLength,
            columnNames,
            columnNameSet);

        return columnNames;
    }

    private static void addColumnName(
        String columnName,
        final RolapStar.Column column,
        int maxColumnNameLength,
        final List<String> columnNames,
        final Set<String> columnNameSet)
    {
        if (columnName == null) {
            columnName = column.getName();
            if (columnName != null) {
                if (columnName.startsWith("$")) {
                    // internal property name. truncate the $.
                    columnName = columnName.replace("$", "");
                }
            } else if (column.getExpression()
                instanceof RolapSchema.PhysRealColumn)
            {
                columnName =
                    ((RolapSchema.PhysRealColumn) column.getExpression()).name;
            } else {
                columnName = "c" + Integer.toString(columnNames.size());
            }
        }
        // Register the column name, and if it's not unique, append numeric
        // suffixes until it is. Also make sure that it is within the
        // range allowed by this SQL dialect.
        String originalColumnName = columnName;
        if (columnName.length() > maxColumnNameLength) {
            columnName = columnName.substring(0, maxColumnNameLength);
        }
        for (int j = 0; !columnNameSet.add(columnName); j++) {
            final String suffix = "_" + Integer.toString(j);
            columnName = originalColumnName;
            if (originalColumnName.length() + suffix.length()
                > maxColumnNameLength)
            {
                columnName =
                    originalColumnName.substring(
                        0, maxColumnNameLength - suffix.length());
            }
            columnName += suffix;
        }
        columnNames.add(columnName);
    }

    @Override
    protected boolean isPartOfSelect(RolapStar.Column col) {
        return request.includeInSelect(col);
    }

    @Override
    protected boolean isPartOfSelect(RolapStar.Measure measure) {
        return request.includeInSelect(measure);
    }

    public List<Pair<RolapStar.Measure, String>> getMeasures() {
        final List<RolapStar.Measure> drillThroughMeasures =
            request.getDrillThroughMeasures();
        if (drillThroughMeasures.size() > 0) {
            return new AbstractList<Pair<RolapStar.Measure, String>>() {
                public int size() {
                    return drillThroughMeasures.size();
                }

                public Pair<RolapStar.Measure, String> get(int index) {
                    final RolapStar.Measure measure =
                        drillThroughMeasures.get(index);
                    return Pair.of(measure, measure.getName());
                }
            };
        } else {
            return Collections.singletonList(
                Pair.of(
                    request.getMeasure(),
                    Util.last(columnNames)));
        }
    }

    public List<Pair<RolapStar.Column, String>> getColumns() {
        final RolapStar.Column[] constrainedColumns =
            request.getConstrainedColumns();
        return new AbstractList<Pair<RolapStar.Column, String>>() {
            public int size() {
                return constrainedColumns.length;
            }

            public Pair<RolapStar.Column, String> get(int index) {
                return Pair.of(
                    constrainedColumns[index],
                    columnNames.get(index));
            }
        };
    }

    public StarColumnPredicate getColumnPredicate(final int i) {
        final StarColumnPredicate constraint = request.getValueAt(i);
        return (constraint == null)
            ? Predicates.wildcard(
                new PredicateColumn(
                    RolapSchema.BadRouter.INSTANCE,
                    request.getConstrainedColumns()[i].getExpression()),
                true)
            : constraint;
    }

    public Pair<String, List<SqlStatement.Type>> generateSqlQuery(String desc) {
        // #107 fail-closed: a bridge (many-to-many) measure group secured by a
        // <MemberGrant>/<HierarchyGrant> cannot be drilled through safely — the
        // legacy generator has no bridge fan-out join and cannot inject the
        // member filter, so it would return raw fact rows for hidden owners.
        // Refuse rather than leak (mirrors the segment-load fail-closed gate).
        if (mondrian.rolap.PredicateGrantSqlFilter.isBridgeMemberSecured(
                getStar(), request.getMeasure(), role))
        {
            throw new mondrian.olap.MondrianException(
                "Row-security: DRILLTHROUGH on a bridge (many-to-many) measure "
                + "group secured by a <MemberGrant>/<HierarchyGrant> is not "
                + "supported — the drill-through SQL generator cannot enforce "
                + "the bridge member filter and would leak fact rows for hidden "
                + "owners. Refused (fail-closed).");
        }
        SqlQueryBuilder queryBuilder = createQueryBuilder(desc);
        nonDistinctGenerateSql(queryBuilder);
        injectPredicateGrants(queryBuilder);
        return queryBuilder.toSqlAndTypes();
    }

    /**
     * #106 (SECURITY): enforce the active role's predicate row-security grants
     * on this drill-through by ANDing one fail-closed {@code WHERE} fragment per
     * applicable grant onto the generated SQL. This is the drill-through
     * counterpart of the Calcite segment-load injection chokepoint — without it
     * a row-secured user's drill-through would leak raw fact rows for every
     * tenant. A grant whose bound parameter cannot be resolved throws (the
     * SQL is never emitted unfiltered); an empty IN set renders zero-row.
     */
    private void injectPredicateGrants(SqlQueryBuilder queryBuilder) {
        for (String fragment
            : mondrian.rolap.PredicateGrantSqlFilter.whereFragments(
                getStar(),
                request.getMeasure(),
                role,
                paramContext,
                queryBuilder.getDialect()))
        {
            queryBuilder.sqlQuery.addWhere(fragment);
        }
    }

    protected void addMeasure(
        RolapStar.Measure measure,
        String alias,
        final SqlQueryBuilder queryBuilder)
    {
        if (!isPartOfSelect(measure)) {
            return;
        }

        assert measure.getTable() == getStar().getFactTable();
        String exprInner;
        if (measure.getExpression() == null) {
            exprInner = "*";
        } else {
            exprInner = measure.getExpression().toSql();
            queryBuilder.addColumn(
                queryBuilder.column(
                    measure.getExpression(), measure.getTable()),
                Clause.FROM);
        }

        if (!countOnly) {
            String exprOuter = measure.getAggregator().getExpression(exprInner);
            queryBuilder.sqlQuery.addSelect(
                exprOuter,
                measure.getInternalType(),
                alias);
        }
    }

    protected boolean isAggregate() {
        // As per SSAS 2005, the query must include a Group By clause
        // so that each row returned contains the aggregated value
        // of the measures for all rows with similar key values.
        return true;
    }

    protected boolean isOrdered() {
        // We don't order drillthrough queries.
        return false;
    }

    protected List<StarPredicate> getPredicateList() {
        return listOfStarPredicates;
    }
}

// End DrillThroughQuerySpec.java
