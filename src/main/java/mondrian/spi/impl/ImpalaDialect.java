/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2013-2013 Pentaho
// All Rights Reserved.
*/
package mondrian.spi.impl;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Dialect for Cloudera's Impala DB.
 *
 * @author cboyden
 * @since 2/11/13
 */
public class ImpalaDialect extends HiveDialect {
    private final String flagsRegexp = "^(\\(\\?([a-zA-Z])\\)).*$";
    private final Pattern flagsPattern = Pattern.compile(flagsRegexp);
    private final String escapeRegexp = "(\\\\Q([^\\\\Q]+)\\\\E)";
    private final Pattern escapePattern = Pattern.compile(escapeRegexp);

    /**
     * Creates an ImpalaDialect.
     *
     * @param connection Connection
     * @throws java.sql.SQLException on error
     */
    public ImpalaDialect(Connection connection) throws SQLException {
        super(connection);
    }

    public static final JdbcDialectFactory FACTORY =
        new JdbcDialectFactory(
            ImpalaDialect.class,
            DatabaseProduct.IMPALA)
        {
            protected boolean acceptsConnection(Connection connection) {
                return isDatabase(DatabaseProduct.IMPALA, connection);
            }
        };

    protected String deduceIdentifierQuoteString(
        DatabaseMetaData databaseMetaData)
    {
        return null;
    }

    @Override
    public DatabaseProduct getDatabaseProduct() {
        return DatabaseProduct.IMPALA;
    }

    @Override
    protected String generateOrderByNulls(
        String expr,
        boolean ascending,
        boolean collateNullsLast)
    {
        if (ascending) {
            return expr + " ASC";
        } else {
            return expr + " DESC";
        }
    }


    @Override
    public String generateOrderItem(
        String expr,
        boolean nullable,
        boolean ascending,
        boolean collateNullsLast)
    {
        String ret = null;

        if (nullable && collateNullsLast) {
            ret = "CASE WHEN " + expr + " IS NULL THEN 1 ELSE 0 END, ";
        } else {
            ret = "CASE WHEN " + expr + " IS NULL THEN 0 ELSE 1 END, ";
        }

        if (ascending) {
            ret += expr + " ASC";
        } else {
            ret += expr + " DESC";
        }

        return ret;
    }

    @Override
    public boolean allowsMultipleCountDistinct() {
        return false;
    }

    @Override
    public boolean allowsCompoundCountDistinct() {
        return true;
    }

    @Override
    public boolean requiresOrderByAlias() {
        return false;
    }

    @Override
    public boolean requiresAliasForFromQuery() {
        return true;
    }

    @Override
    public boolean supportsGroupByExpressions() {
        return false;
    }

    @Override
    public boolean allowsSelectNotInGroupBy() {
        return false;
    }

    @Override
    public String generateInline(
        List<String> columnNames,
        List<String> columnTypes,
        List<String[]> valueList)
    {
        return generateInlineGeneric(
            columnNames, columnTypes, valueList, null, false);
    }

    public boolean allowsJoinOn() {
        return false;
    }

    @Override
    public void quoteStringLiteral(
        StringBuilder buf,
        String value)
    {
        // Impala string literals use BACKSLASH escaping (unlike the standard, which
        // doubles the quote), and may be delimited by either quote character. The
        // delimiter is switched to '"' when the value contains a single quote, which
        // keeps the common case free of escapes.
        //
        // The backslash must be escaped, and must be escaped FIRST (issue #146).
        // Escaping the delimiter introduces backslashes of its own, so doing it in the
        // other order would escape those too and corrupt the value.
        //
        // Previously the backslash was not escaped at all, so any value containing one
        // produced a literal that escaped its own closing quote and never terminated:
        // "\" became '\', which runs on into the rest of the statement. String literals
        // carry data — captions and key values read from the fact table — so that was
        // reachable without any schema-authoring access.
        final String quote = value.indexOf('\'') >= 0 ? "\"" : "'";

        // Literal replace(), not replaceAll(): the latter treats its first argument as a
        // regex and its second as a replacement template, in both of which a backslash
        // is significant.
        final String escaped =
            value.replace("\\", "\\\\").replace(quote, "\\" + quote);

        buf.append(quote);
        buf.append(escaped);
        buf.append(quote);
    }

    public boolean allowsRegularExpressionInWhereClause() {
        return true;
    }

    public String generateRegularExpression(
        String source,
        String javaRegex)
    {
        try {
            Pattern.compile(javaRegex);
        } catch (PatternSyntaxException e) {
            // Not a valid Java regex. Too risky to continue.
            return null;
        }

        // We might have to use case-insensitive matching
        final Matcher flagsMatcher = flagsPattern.matcher(javaRegex);
        boolean caseSensitive = true;
        if (flagsMatcher.matches()) {
            final String flags = flagsMatcher.group(2);
            if (flags.contains("i")) {
                caseSensitive = false;
            }
        }
        if (flagsMatcher.matches()) {
            javaRegex =
                javaRegex.substring(0, flagsMatcher.start(1))
                + javaRegex.substring(flagsMatcher.end(1));
        }
        final Matcher escapeMatcher = escapePattern.matcher(javaRegex);
        while (escapeMatcher.find()) {
            javaRegex =
                javaRegex.replace(
                    escapeMatcher.group(1),
                    escapeMatcher.group(2));
        }
        final StringBuilder sb = new StringBuilder();

        // Now build the string.
        if (caseSensitive) {
            sb.append(source);
        } else {
            sb.append("UPPER(");
            sb.append(source);
            sb.append(")");
        }
        sb.append(" REGEXP ");
        if (caseSensitive) {
            quoteStringLiteral(sb, javaRegex);
        } else {
            quoteStringLiteral(sb, javaRegex.toUpperCase());
        }
        return sb.toString();
    }
}
// End ImpalaDialect.java
