# YAML Schema for Mondrian

Mondrian-saiku accepts schemas written in YAML as well as XML, as of #34 (4.8.1.11+). YAML schemas are converted to XML in-process at catalog-load time; the rest of the schema-loader pipeline is unchanged.

The same schema written as XML and YAML returns byte-identical MDX cells. The canonical `demo/FoodMart3.mondrian.xml` round-trips through reverse + forward conversion with byte-identical query results (see `FoodMart3MdxEquivalenceTest`).

## Quick start

The smallest loadable schema, in YAML:

```yaml
schema: MyApp
cubes:
  UnitSalesByYear:
    fact_table: sales_fact_1997
    dimensions:
      - name: Time
        foreign_key: time_id
        hierarchy:
          has_all: true
          primary_key: time_id
          table: time_by_day
          levels:
            - name: Year
              column: the_year
              type: Numeric
              unique_members: true
    measures:
      - name: Unit Sales
        column: unit_sales
        aggregator: sum
```

Save as `schema.yaml`, point Mondrian's connect-string `Catalog` property at it:

```
jdbc:mondrian:Jdbc=...; Catalog=file:///etc/mondrian/schema.yaml; ...
```

Or pass the YAML inline via `CatalogContent`:

```java
properties.put("CatalogContent",
    Files.readString(Paths.get("schema.yaml")));
```

Both work. YAML detection is content-based — anything starting with `schema:` (after whitespace + YAML comments + `---` doc marker) is treated as YAML; anything starting with `<` is treated as XML.

## Element reference (Mondrian 3 / legacy)

All elements the canonical FoodMart fixture uses. YAML keys are snake_case; the converter maps them to Mondrian's camelCase XML attributes (`foreign_key` → `foreignKey`, `unique_members` → `uniqueMembers`, etc.).

### Schema-scope

| YAML key | XML element | Notes |
|---|---|---|
| `schema:` | `<Schema name>` | required |
| `annotations:` | `<Annotations><Annotation name>...</Annotation>...</Annotations>` | map of name → value |
| `shared_dimensions:` | top-level `<Dimension>`s | map of name → dim body |
| `named_sets:` | top-level `<NamedSet>`s | list |
| `cubes:` | `<Cube>`s | map of name → cube body |
| `virtual_cubes:` | `<VirtualCube>`s | map of name → vcube body; emitted AFTER base cubes |
| `roles:` | `<Role>`s | list; emitted last so cube refs resolve |

### Cube

| YAML key | XML | Notes |
|---|---|---|
| `default_measure:` | `defaultMeasure=` attr | |
| `annotations:` | `<Annotations>` child | |
| `fact_table:` | `<Table name>` child | bare string OR map with `name` + `agg_exclude:` list + `agg_names:` list |
| `dimension_usages:` | `<DimensionUsage>`s | list of `{name, source, foreign_key, level, usage_prefix}` |
| `dimensions:` | `<Dimension>`s | list (cube-scoped, carries `foreign_key`) |
| `measures:` | `<Measure>`s | list |
| `calculated_members:` | `<CalculatedMember>`s | list |
| `named_sets:` | `<NamedSet>`s | list |

### Dimension (shared + cube-scoped)

| YAML key | XML | Notes |
|---|---|---|
| `name:` | `name=` attr | shared dims use map key instead |
| `foreign_key:` | `foreignKey=` attr | cube-scoped dims only |
| `type:` | `type=` attr | e.g. `TimeDimension` |
| `hierarchy:` | single `<Hierarchy>` child | map |
| `hierarchies:` | multiple `<Hierarchy>` children | list (e.g. FoodMart Time has default + Weekly) |
| `annotations:` | `<Annotations>` child | |

### Hierarchy

| YAML key | XML | Notes |
|---|---|---|
| `name:` | `name=` attr | omit for the default hierarchy |
| `has_all:` | `hasAll=` attr | |
| `primary_key:` | `primaryKey=` attr | |
| `primary_key_table:` | `primaryKeyTable=` attr | required when source is a `join:` |
| `all_member_name:` | `allMemberName=` attr | |
| `default_member:` | `defaultMember=` attr | |
| `table:` | `<Table name>` child | when source is a single table |
| `join:` | `<Join>` child | when source spans 2 tables; see below |
| `levels:` | `<Level>`s | list, in drill order |

### Join (multi-table hierarchies)

| YAML key | XML | Notes |
|---|---|---|
| `left_alias:` / `left_key:` | `leftAlias=` / `leftKey=` attrs | |
| `right_alias:` / `right_key:` | `rightAlias=` / `rightKey=` attrs | |
| `tables:` | exactly two `<Table>` children | list of bare-string or `{name, schema?, alias?}` map |

### Level

| YAML key | XML | Notes |
|---|---|---|
| `name:` | `name=` attr | |
| `table:` | `table=` attr | column-table override for `join:` scenarios |
| `column:` | `column=` attr | |
| `name_column:` / `ordinal_column:` | `nameColumn=` / `ordinalColumn=` | |
| `parent_column:` / `null_parent_value:` | `parentColumn=` / `nullParentValue=` | parent-child levels |
| `type:` | `type=` attr | `Numeric`, `String`, ... |
| `unique_members:` | `uniqueMembers=` attr | |
| `level_type:` | `levelType=` attr | `TimeYears`, `TimeQuarters`, ... |
| `hide_member_if:` | `hideMemberIf=` attr | |
| `approx_row_count:` | `approxRowCount=` attr | |
| `key_expression:` | `<KeyExpression>` child | list of `{dialect, text}` — see SQL dialect expressions below |
| `name_expression:` / `ordinal_expression:` / `caption_expression:` | matching `*Expression>` children | same shape |
| `closure:` | `<Closure>` child | parent-child closure table — see below |
| `properties:` | `<Property>` children | list of `{name, column, type?}` |

### Measure

| YAML key | XML | Notes |
|---|---|---|
| `name:` | `name=` attr | |
| `column:` | `column=` attr | mutually exclusive with `measure_expression:` |
| `aggregator:` | `aggregator=` attr | `sum`, `count`, `distinct-count`, `min`, `max`, ... |
| `format_string:` | `formatString=` attr | |
| `datatype:` | `datatype=` attr | |
| `visible:` | `visible=` attr | |
| `measure_expression:` | `<MeasureExpression>` child | list of `{dialect, text}` |

### SQL dialect expressions

The Level and Measure expression elements (`key_expression:`, `measure_expression:`, ...) all share the same shape — a list of dialect-keyed SQL blocks:

```yaml
measure_expression:
  - dialect: derby
    text: '(case when "sales_fact_1997"."promotion_id" = 0 then 0 else "sales_fact_1997"."store_sales" end)'
  - dialect: generic
    text: 'CASE WHEN "sales_fact_1997"."promotion_id" = 0 THEN 0 ELSE "sales_fact_1997"."store_sales" END'
```

`dialect:` defaults to `generic` when omitted.

The SQL text is emitted **raw** — angle brackets are NOT escaped. This is so users can write Mondrian's `<Column table="..." name="..."/>` inline-refs literally in their YAML `text:` value:

```yaml
key_expression:
  - dialect: generic
    text: '<Column table="customer" name="fname"/> || '' '' || <Column table="customer" name="lname"/>'
```

Mondrian's dialect-SQL processor parses the embedded markup at schema-load time and substitutes the actual column references. **The cost:** if your SQL contains literal `<`, `>`, or `&` as SQL operators (e.g. `WHERE x < 5`), you must escape them yourself (`WHERE x &lt; 5`). This is the same rule any hand-written Mondrian XML schema already follows.

### CalculatedMember

| YAML key | XML | Notes |
|---|---|---|
| `name:` / `dimension:` / `caption:` / `visible:` | matching attrs | |
| `formula:` | `formula=` attr | inline one-liner |
| `formula_body:` | `<Formula>` child element | multi-line MDX, preserves whitespace |
| `properties:` | `<CalculatedMemberProperty>` children | map of name → value (MDX-standard names like `FORMAT_STRING`, `SOLVE_ORDER`, `MEMBER_ORDINAL` pass through verbatim) |

### NamedSet

| YAML key | XML | Notes |
|---|---|---|
| `name:` / `caption:` | matching attrs | |
| `formula:` | `formula=` attr | inline form |
| `formula_body:` | `<Formula>` child element | multi-line form |

Valid at both cube scope (inside a cube) and schema scope (top-level `named_sets:`).

### Closure (parent-child hierarchies)

```yaml
levels:
  - name: Employee Id
    column: employee_id
    parent_column: supervisor_id
    name_column: full_name
    null_parent_value: '0'
    closure:
      parent_column: supervisor_id
      child_column: employee_id
      table: employee_closure
```

### Fact-table aggregate refs

When a cube's fact table has companion aggregate tables, use the map form of `fact_table:`:

```yaml
fact_table:
  name: sales_fact_1997
  agg_exclude:
    - agg_lc_100_sales_fact_1997
  agg_names:
    - name: agg_c_special_sales_fact_1997
      fact_count_column: FACT_COUNT
      ignore_columns: [foo, bar]
      foreign_keys:
        - { fact_column: product_id,  agg_column: PRODUCT_ID }
      measures:
        - { name: '[Measures].[Unit Sales]', column: UNIT_SALES_SUM }
      levels:
        - { name: '[Time].[Year]', column: TIME_YEAR }
```

### VirtualCube

Composite cube that re-exposes dimensions and measures from one or more base cubes:

```yaml
virtual_cubes:
  Warehouse and Sales:
    default_measure: Store Sales
    dimensions:
      - { cube_name: Sales, name: Customers }
      - { name: Product }                     # shared dim — cube_name optional
    measures:
      - { cube_name: Sales,     name: '[Measures].[Store Sales]' }
      - { cube_name: Warehouse, name: '[Measures].[Warehouse Sales]' }
    calculated_members:
      - name: Profit Per Unit Shipped
        dimension: Measures
        formula_body: |
          [Measures].[Profit] / [Measures].[Units Shipped]
```

### Role + grants

```yaml
roles:
  - name: California manager
    schema_grant:
      access: none
      cubes:
        - cube: Sales
          access: all
          hierarchies:
            - hierarchy: '[Store]'
              access: custom
              top_level: '[Store].[Store Country]'
              members:
                - { member: '[Store].[USA].[CA]',                access: all }
                - { member: '[Store].[USA].[CA].[Los Angeles]',  access: none }
            - hierarchy: '[Gender]'
              access: none
```

Each grant level renders as self-closing (no children) or container (with children). Attributes: `access` (`all` | `none` | `custom` | `all_dimensions`), `topLevel`, `bottomLevel`, `rollupPolicy`.

## `$ref` includes for multi-file schemas

A YAML map containing **exactly** the key `$ref` (with a relative file path string as value) is spliced — the loaded file's root content replaces the `$ref` map in-place. Includes resolve **relative to the file containing the `$ref`**, not the project root.

```yaml
# schema.yaml
schema: FoodMart
shared_dimensions:
  Store: { $ref: shared/store.yaml }
  Time:  { $ref: shared/time.yaml }
cubes:
  Sales:     { $ref: cubes/sales.yaml }
  Warehouse: { $ref: cubes/warehouse.yaml }
```

A `cubes/sales.yaml` file can in turn `$ref: measures.yaml` from `cubes/` without needing to spell out the full path. Cyclic includes (`a → b → a`) are detected and rejected with the full ref chain shown.

`$ref` is only resolved when the loader has a base directory — i.e. when the schema is loaded from a `file://` URL via the `Catalog` property. Passing YAML inline via `CatalogContent` skips `$ref` resolution because there's no base directory to resolve against.

## CLI: `mondrian-schema`

`mondrian.schema.yaml.SchemaCli` (currently invoked via `mvn exec:java -Dexec.mainClass=mondrian.schema.yaml.SchemaCli -Dexec.args="..."`) wraps the converters with three subcommands:

```
mondrian-schema to-yaml <input.xml>  [-o output.yaml]
mondrian-schema to-xml  <input.yaml> [-o output.xml]
mondrian-schema lint    <input.{yaml,xml}>
```

| Subcommand | Behaviour |
|---|---|
| `to-yaml` | Reads an XML schema, emits the equivalent YAML to stdout (or `-o`) |
| `to-xml` | Reads a YAML schema (with `$ref` resolution), emits the equivalent Mondrian XML |
| `lint` | Validates the schema structurally. Two-tier: (1) XML parses cleanly via Mondrian's XOM parser, (2) if Mondrian-4 modern format, also runs the `MondrianDef.Schema` constructor. Legacy (M3) schemas skip tier 2 because the modern XSD enum rejects legacy values like `type="TimeDimension"` |

Exit codes: `0` success, `1` bad args / missing input, `2` lint or parse failure (diagnostic on stderr).

## Migration: lifting an existing XML schema to YAML

```
mondrian-schema to-yaml existing-schema.xml -o existing-schema.yaml
mondrian-schema lint existing-schema.yaml
```

The reverse converter (`XmlSchemaToYaml`) handles every element listed above. Round-trip semantic equality (`XML → YAML → XML' → same MDX cells`) is verified by the test suite against `demo/FoodMart3.mondrian.xml` — 754 lines, the canonical Mondrian-3 schema — including:

- Shared dims via `<DimensionUsage>` with `foreignKey`
- Multi-hierarchy dimensions (`Time` with default + `Weekly`)
- Multi-table joins (`Product` joining `product` + `product_class`)
- Calculated members on both base cubes and virtual cubes
- Parent-child closure tables (`Employee`)
- Named sets, agg-table refs, role grants

If your schema uses an element the converter doesn't yet support, the round-trip will silently drop it. Open an issue with a fixture.

## Caveats

### Mondrian 4 modern format not yet supported

The converter handles every element in the **Mondrian 3 legacy** schema model. Mondrian 4 `<PhysicalSchema>` / `<Attribute>` / `<MeasureGroup>` / `<Link>` elements are not yet implemented — schemas using them won't round-trip correctly. Most Mondrian schemas in the wild are M3; M4 support is on the roadmap.

### SQL body raw-text rule

SQL dialect text inside `key_expression:`, `measure_expression:`, etc. is emitted raw — so `<Column .../>` markup passes through and Mondrian substitutes it. The trade-off is you must escape literal `<` / `>` / `&` characters yourself if they appear as SQL operators (same rule as hand-written Mondrian XML).

### `$ref` only resolves with a file URL

When the schema is loaded via `CatalogContent` (in-memory string), `$ref` includes aren't resolved because there's no base directory. Use `Catalog=file:///path/to/schema.yaml` if you want includes.

## Implementation notes

- Converters live in `mondrian.schema.yaml` (`YamlSchemaConverter`, `XmlSchemaToYaml`, `SchemaCli`).
- Catalog-load integration sits in `RolapSchemaLoader.loadStage0` — `looksLikeYaml()` content-sniff + `convertYamlCatalog()` dispatch. All existing XML catalogs are unaffected (purely additive code path).
- 60 tests across 12 test classes cover element emission, round-trip, MDX cell equivalence, `$ref` resolution, CLI, and catalog integration.
- YAML parsing uses Jackson's `YAMLFactory` (already a Calcite transitive). No new runtime dependencies were added.
