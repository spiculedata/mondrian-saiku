# Predicate Row-Security (#106) and Query Parameters (#105)

Predicate row-security restricts the **fact rows** a role can see by comparing a
real fact column against the validated value of a bounded query parameter
(`<QueryParameter>`, #105). A `<PredicateGrant>` is declared inside a
`<CubeGrant>`:

```xml
<QueryParameter name='tenant' type='Numeric' defaultValue='1'>
  <QueryParameterValue>1</QueryParameterValue>
  <QueryParameterValue>2</QueryParameterValue>
</QueryParameter>
...
<Role name='Tenant'>
  <SchemaGrant access='all'>
    <CubeGrant cube='Sales' access='all'>
      <PredicateGrant measureGroup='S' column='tenant'
                      operator='eq' parameter='tenant'/>
    </CubeGrant>
  </SchemaGrant>
</Role>
```

The bound value is supplied per connection through the `session.<name>`
connection property (the same channel dynamic roles use) and is type-coerced
and allowed-set-checked before it can become a SQL literal — a string value is
always rendered as a quoted literal, never executable SQL.

## Where the restriction is enforced

Predicate grants are enforced at **every** path that can reach a secured
measure group's fact rows:

| Path | Enforcement site | Mechanism |
|------|------------------|-----------|
| Aggregate segment load | `CalcitePlannerAdapters.injectPredicateGrants` → `SegmentLoader` | EQ/IN filter on the real fact column, injected pre-aggregation at the Calcite chokepoint; segment cache identity includes the resolved value |
| **Drill-through** | `RolapCell.getDrillThroughSQL` → `AggregationManager.getDrillThroughSql` → `DrillThroughQuerySpec.injectPredicateGrants` → `PredicateGrantSqlFilter` | Equivalent EQ/IN `WHERE` fragment ANDed onto the legacy drill-through SQL |

Drill-through goes through a **separate legacy SQL path** that the Calcite
segment-load injection never sees. Before #106's drill-through fix, a
row-secured user who ran `DRILLTHROUGH SELECT ... FROM [Sales]` received raw
fact rows for **every** tenant — a data leak. The fix threads the connection's
`Role` and `QueryParameterContext` explicitly from `RolapCell` (drill-through
SQL is generated outside any `Locus`, so ambient resolution is not available)
into `DrillThroughQuerySpec`, which renders the grant via the shared
`mondrian.rolap.PredicateGrantSqlFilter` helper.

## Fail-closed contract

Security code never emits an unfiltered query for a secured load. If a
predicate grant applies but its bound parameter cannot be resolved
(undeclared / unbound / fails validation), the load **fails closed**:

- Aggregate path: the segment load is forced to zero rows (`universalFalse`),
  and the legacy backend refuses to serve a predicate-secured load at all.
- Drill-through path: `PredicateGrantSqlFilter` throws a clear
  `MondrianException` rather than returning SQL; an empty `IN` value set renders
  a universally-false predicate (`1 = 0`).

An undeclared grant parameter is also rejected at schema load.

## Union roles: AND (most-restrictive), by design

When a connection combines roles (`Role=A,B`), a `UnionRoleImpl` is built. For
**access** (`getAccess`), Mondrian combines constituents with `max(...)` —
most-**permissive** wins. For **predicate grants**
(`UnionRoleImpl.getPredicateGrants`) we deliberately do the **opposite**:
constituent grants are **concatenated and ANDed** at injection, so a fact row is
visible only if it satisfies **every** constituent's restriction —
most-**restrictive** wins.

This divergence is intentional: a union of roles must never *widen* the rows any
single constituent could see. The asymmetric case — one arm carries a predicate
grant on the measure group and another arm carries none — still applies the
granting arm's restriction, because the un-granting arm contributes no grant and
therefore cannot relax the result.

See `PredicateGrantSecurityBatteryTest` for the executable specification of
these invariants, and `PredicateGrantDrillThroughTest` for the drill-through
enforcement and fail-closed cases.
