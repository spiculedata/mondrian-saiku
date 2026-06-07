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

import mondrian.lookml.model.Classification;
import mondrian.lookml.parse.LookmlNode;
import mondrian.lookml.parse.LookmlParser;
import mondrian.lookml.transpile.LookmlTranspiler;
import mondrian.lookml.transpile.TranspileResult;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The synthetic {@code demo/lookml/bank.lkml} transpiles to M4 with the expected
 * constructs and WITHOUT silently dropping row-security — the Looker-migration
 * companion to {@link BankShowcaseH2EndToEndTest}. Demonstrates the importer's
 * shipped CLEAN/DEGRADE paths (#106/#107/#108/#117/#119/#124) end-to-end.
 */
public class BankLookmlMigrationH2EndToEndTest {

    private static TranspileResult transpile() throws Exception {
        String lkml = new String(
            Files.readAllBytes(Path.of("demo/lookml/bank.lkml")));
        LookmlNode doc = LookmlParser.parse(lkml);
        return new LookmlTranspiler().transpile(doc);
    }

    @Test
    public void modelTranspilesWithBridgeRlsAndDistinctGrain() throws Exception {
        TranspileResult result = transpile();
        String yaml = result.yaml();
        // many-to-many two-hop -> bridge dimension link (#124).
        assertTrue(yaml.contains("type: \"bridge\""), yaml);
        // access_filter on a fact column -> predicate grant (#106).
        assertTrue(yaml.contains("predicate_grants:"), yaml);
        // sum_distinct on a same-view non-PK key -> measure-level distinct
        // grain (#119).
        assertTrue(yaml.contains("distinct_key_column:"), yaml);
        // The secured explore must NOT be REFUSED — row-security must survive
        // the migration (ties to the #106/#107 fail-closed work).
        assertFalse(result.classification()
                .withClassification(Classification.REFUSE).stream()
                .anyMatch(r -> r.qualifiedName().startsWith("explore:account")),
            "the secured explore must transpile, not be refused: "
                + result.classification().withClassification(
                    Classification.REFUSE));
    }
}
