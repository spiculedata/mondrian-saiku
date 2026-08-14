/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.calcite;

import mondrian.spi.Dialect;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.ViewExpanders;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalValues;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.util.DateString;
import org.apache.calcite.util.TimeString;
import org.apache.calcite.util.TimestampString;

import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Calcite {@link org.apache.calcite.schema.Table} over a
 * {@link VirtualRelation} -- a Mondrian {@code <InlineTable>} or
 * {@code <View>}, neither of which exists in the database.
 *
 * <p>The table is {@link TranslatableTable}, so a scan never survives into
 * the generated SQL as a table reference. An inline table expands to a
 * {@link LogicalValues}, which Calcite unparses as {@code VALUES} (or as a
 * {@code UNION ALL} of {@code SELECT}s on dialects that cannot alias a
 * {@code VALUES} clause). A view expands to its parsed and validated query,
 * so the planner can push filters and projections into it exactly as it
 * would into a derived table.
 *
 * <p>View SQL is parsed with unquoted identifiers left as written and
 * matched case-insensitively, because Mondrian schema authors write the SQL
 * in their database's own dialect (typically all-lowercase table names,
 * unquoted) rather than in Calcite's default upper-casing dialect. A view
 * whose SQL uses syntax Calcite's parser does not accept fails at plan time
 * with a message naming the view and its SQL, rather than silently
 * disappearing.
 */
final class VirtualRelationTable extends AbstractTable
    implements TranslatableTable
{
    /**
     * Parser configuration for view SQL. {@code UNCHANGED} casing plus a
     * case-insensitive catalog is the combination that resolves
     * {@code SELECT * FROM customer} against a JdbcSchema whose table is
     * named {@code customer}; Calcite's default would look for
     * {@code CUSTOMER}.
     */
    private static final SqlParser.Config PARSER_CONFIG =
        SqlParser.config()
            .withQuoting(Quoting.DOUBLE_QUOTE)
            .withUnquotedCasing(Casing.UNCHANGED)
            .withQuotedCasing(Casing.UNCHANGED)
            .withCaseSensitive(false);

    private static final CalciteConnectionConfig CONN_CONFIG;

    static {
        final Properties props = new Properties();
        props.setProperty(
            CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "false");
        props.setProperty(
            CalciteConnectionProperty.UNQUOTED_CASING.camelName(),
            Casing.UNCHANGED.name());
        props.setProperty(
            CalciteConnectionProperty.QUOTED_CASING.camelName(),
            Casing.UNCHANGED.name());
        CONN_CONFIG = new CalciteConnectionConfigImpl(props);
    }

    private final VirtualRelation def;
    /** Root schema the view SQL resolves its table references against.
     *  Null for an inline table, which references nothing. */
    private final SchemaPlus rootSchema;
    private final List<String> schemaPath;

    VirtualRelationTable(
        VirtualRelation def,
        SchemaPlus rootSchema,
        String schemaName)
    {
        this.def = def;
        this.rootSchema = rootSchema;
        this.schemaPath =
            schemaName == null
                ? Collections.<String>emptyList()
                : Collections.singletonList(schemaName);
    }

    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        final SqlValidator validator = viewValidator(typeFactory);
        return validator.getValidatedNodeType(validate(validator));
    }

    public RelNode toRel(
        RelOptTable.ToRelContext context, RelOptTable relOptTable)
    {
        final RelOptCluster cluster = context.getCluster();
        // Expand the relation in the caller's cluster. Calcite's own ViewTable
        // would delegate to ToRelContext.expandView, which RelBuilder's
        // context does not implement; converting here keeps every rel in the
        // one cluster the caller is building into.
        final CalciteCatalogReader catalogReader =
            catalogReader(cluster.getTypeFactory());
        final SqlValidator validator = validatorFor(catalogReader);
        final SqlNode validated = validate(validator);
        final SqlToRelConverter converter =
            new SqlToRelConverter(
                ViewExpanders.simpleContext(cluster),
                validator,
                catalogReader,
                cluster,
                StandardConvertletTable.INSTANCE,
                SqlToRelConverter.config());
        final RelNode rel = converter.convertQuery(validated, false, true).rel;
        return def.kind == VirtualRelation.Kind.INLINE
            ? preserveLiteralTypes(rel, cluster.getRexBuilder())
            : rel;
    }

    /**
     * Re-attaches an explicit {@code CAST} to every literal an inline table
     * projects.
     *
     * <p>{@code Dialect.generateInline} renders an inline table as a
     * {@code UNION ALL} of one-row {@code SELECT}s and casts the literals so
     * that the column types do not depend on which row happens to be first --
     * a column holding 1234 and 1234567890123 must be BIGINT, not the INTEGER
     * the first row alone suggests. Calcite folds {@code CAST(1234 AS BIGINT)}
     * into a BIGINT literal at parse time and then unparses that literal as
     * plain {@code 1234}, so the cast is lost and the database goes back to
     * typing the column from the first branch -- "numeric value out of range"
     * on the second. {@link RexBuilder#makeAbstractCast} builds a cast that is
     * deliberately not folded, which is exactly what is needed here.
     *
     * @param rel Parsed inline-table query
     * @param rexBuilder Builder for the cluster {@code rel} belongs to
     * @return equivalent query whose literals carry their types explicitly
     */
    private static RelNode preserveLiteralTypes(
        RelNode rel, final RexBuilder rexBuilder)
    {
        return rel.accept(
            new org.apache.calcite.rel.RelShuttleImpl() {
                @Override public RelNode visit(
                    org.apache.calcite.rel.logical.LogicalProject project)
                {
                    final RelNode input = project.getInput().accept(this);
                    final List<RexNode> exprs = new ArrayList<RexNode>();
                    boolean changed = false;
                    for (RexNode e : project.getProjects()) {
                        if (e instanceof RexLiteral
                            && !RexLiteral.isNullLiteral(e))
                        {
                            exprs.add(
                                rexBuilder.makeAbstractCast(e.getType(), e));
                            changed = true;
                        } else {
                            exprs.add(e);
                        }
                    }
                    if (!changed && input == project.getInput()) {
                        return project;
                    }
                    return project.copy(
                        project.getTraitSet(), input, exprs,
                        project.getRowType());
                }
            });
    }




    private SqlNode validate(SqlValidator validator) {
        final SqlNode parsed;
        try {
            parsed = SqlParser.create(def.sql, PARSER_CONFIG).parseQuery();
        } catch (org.apache.calcite.sql.parser.SqlParseException e) {
            throw new mondrian.olap.MondrianException(
                "Calcite backend: cannot parse the SQL defining view '"
                + def.alias + "'. The Calcite backend plans view relations by "
                + "parsing their SQL, so the SQL must be valid ANSI SQL. "
                + "Parser said: " + e.getMessage()
                + " SQL was: " + def.sql,
                e);
        }
        return validator.validate(parsed);
    }

    private SqlValidator viewValidator(RelDataTypeFactory typeFactory) {
        return validatorFor(catalogReader(typeFactory));
    }

    private CalciteCatalogReader catalogReader(
        RelDataTypeFactory typeFactory)
    {
        return new CalciteCatalogReader(
            CalciteSchema.from(rootSchema),
            schemaPath,
            typeFactory,
            CONN_CONFIG);
    }

    private static SqlValidator validatorFor(
        CalciteCatalogReader catalogReader)
    {
        return SqlValidatorUtil.newValidator(
            SqlStdOperatorTable.instance(),
            catalogReader,
            catalogReader.getTypeFactory(),
            SqlValidator.Config.DEFAULT.withIdentifierExpansion(true));
    }

}

// End VirtualRelationTable.java
