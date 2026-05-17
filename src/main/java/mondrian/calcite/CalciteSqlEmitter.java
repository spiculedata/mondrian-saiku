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

import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.sql.SqlDialect;

/**
 * Stateless utility that converts a Calcite {@link RelNode} to
 * dialect-specific SQL via Calcite's {@link RelToSqlConverter}.
 *
 * <p>Pairs with {@link CalciteBackedDialect}: callers in the legacy
 * Mondrian SQL paths (member reads, drillthrough, agg-table probes) can
 * build a {@link RelNode} once and emit dialect-flavoured SQL for any
 * backend Calcite supports — without writing a Mondrian {@code Dialect}
 * subclass per backend first.
 *
 * <p>Why a separate class instead of one-line inlining at every call site:
 * the input-guard behaviour (null checks with clear messages) and the
 * exact converter wiring (visitRoot → asStatement → toSqlString) live in
 * one place so every legacy path that adopts Pattern A from ticket #40
 * agrees on the same emission semantics.
 */
public final class CalciteSqlEmitter {

    private CalciteSqlEmitter() {
        // Static utility — not instantiable.
    }

    /**
     * Converts the supplied {@link RelNode} to SQL using {@code dialect}.
     *
     * @param plan non-null Calcite relational plan to emit as SQL
     * @param dialect non-null Calcite SqlDialect controlling
     *                quoting, function-name translation, OFFSET/FETCH
     *                style, and other backend-specific syntax
     * @return SQL string ready to send to the backend
     * @throws IllegalArgumentException if either argument is null
     */
    public static String emit(RelNode plan, SqlDialect dialect) {
        if (plan == null) {
            throw new IllegalArgumentException(
                "CalciteSqlEmitter.emit: plan (RelNode) is null");
        }
        if (dialect == null) {
            throw new IllegalArgumentException(
                "CalciteSqlEmitter.emit: dialect (SqlDialect) is null");
        }
        RelToSqlConverter converter = new RelToSqlConverter(dialect);
        return converter.visitRoot(plan)
            .asStatement()
            .toSqlString(dialect)
            .getSql();
    }
}

// End CalciteSqlEmitter.java
