/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.olap;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #106 unit: {@link DelegatingRole} must forward the predicate-grant API
 * ({@link Role#hasPredicateGrants()} and
 * {@link Role#getPredicateGrants(String)}) to its wrapped role. A delegating
 * role that silently dropped these would re-open the native-bypass leak the
 * gate guards against (the gate reads the role via the delegating chain).
 */
public class DelegatingRolePredicateGrantTest {

    private static final String KEY = "Sales.S";

    private static RoleImpl roleWithGrant() {
        RoleImpl r = new RoleImpl();
        r.grant(
            KEY,
            new PredicateGrant("S", "tenant", PredicateGrant.Operator.EQ,
                "tenant"));
        r.makeImmutable();
        return r;
    }

    @Test
    public void delegatesHasPredicateGrantsTrue() {
        Role delegate = new DelegatingRole(roleWithGrant());
        assertTrue(delegate.hasPredicateGrants(),
            "delegating role must report the wrapped role's grants");
    }

    @Test
    public void delegatesHasPredicateGrantsFalse() {
        RoleImpl empty = new RoleImpl();
        empty.makeImmutable();
        Role delegate = new DelegatingRole(empty);
        assertFalse(delegate.hasPredicateGrants(),
            "no wrapped grants => delegate reports none (keeps native enabled "
            + "for the common case)");
    }

    @Test
    public void delegatesGetPredicateGrants() {
        RoleImpl underlying = roleWithGrant();
        Role delegate = new DelegatingRole(underlying);
        List<PredicateGrant> grants = delegate.getPredicateGrants(KEY);
        assertEquals(1, grants.size(),
            "delegate must return the wrapped role's grants for the key");
        assertEquals("tenant", grants.get(0).getColumn());
        // Identity: the delegate forwards the underlying role's exact list.
        assertSame(underlying.getPredicateGrants(KEY).get(0), grants.get(0),
            "delegate must forward the underlying grant, not a copy");
    }

    @Test
    public void delegatesGetPredicateGrantsEmptyForOtherKey() {
        Role delegate = new DelegatingRole(roleWithGrant());
        assertTrue(delegate.getPredicateGrants("Sales.OTHER").isEmpty(),
            "an unrelated measure-group key must delegate to an empty list");
    }
}
