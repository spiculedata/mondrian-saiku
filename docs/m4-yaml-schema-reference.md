# Mondrian-4 YAML Schema Reference

Mondrian-4 YAML schemas are a first-class catalog format for the mondrian-saiku engine (introduced in #34, version 4.8.1.11+). The `RolapSchemaLoader` auto-detects any catalog whose content begins with `schema:` (after optional whitespace, YAML comments, and `---` document markers) and routes it through the M4 YAML-to-XML converter before the rest of the schema-loader pipeline. The YAML format maps one-to-one onto Mondrian-4 XML — the converter builds a typed `MondrianDef.Schema` object graph and serializes it with XOM, so the two representations are semantically identical. The converter round-trips: `M4 XML → YAML → M4 XML` produces byte-equivalent query results.

> **Mondrian-3 users:** the older M3 YAML format (shared dimensions as `<DimensionUsage>`, hierarchy `<Join>`, `<Level>` elements, etc.) is documented in [yaml-schema.md](yaml-schema.md). The M3 and M4 formats are mutually exclusive and detected by the presence of the `physical_schema:` key.

---

## Quickstart

### Convert an existing M4 XML schema to YAML

```bash
./scripts/mondrian-schema to-yaml FoodMart.xml -o FoodMart.yaml
```

### Convert a YAML schema back to M4 XML

```bash
./scripts/mondrian-schema to-xml FoodMart.yaml -o FoodMart.xml
```

### Validate a schema (YAML or XML)

```bash
./scripts/mondrian-schema lint FoodMart.yaml
```

Exit codes: `0` success, `1` bad arguments or missing file, `2` parse or validation failure (diagnostic on stderr).

The first run builds a classpath cache (`target/dependency/classpath.txt`). Subsequent runs are fast (~130ms). The cache is rebuilt automatically when `pom.xml` is newer.

### Load a YAML schema at runtime

Point Mondrian's `Catalog` connect-string property at the file:

```
jdbc:mondrian:Jdbc=jdbc:...;Catalog=file:///etc/mondrian/FoodMart.yaml;...
```

YAML detection is content-based, not file-extension-based.

---

## Top-level structure

A complete M4 YAML schema has the following top-level keys (all optional except `schema`):

```yaml
schema:                   # required — schema header
  name: "FoodMart"
  metamodel_version: "4.0"

annotations:              # optional — schema-level metadata
  caption.de_DE: "Verkaufen"

physical_schema:          # optional — tables, calculated columns, links
  tables: [...]
  links: [...]

shared_dimensions:        # optional — named dimensions usable across cubes
  Store: { ... }
  Time:  { ... }

cubes:                    # optional — one entry per cube
  Sales: { ... }

roles:                    # optional — access-control roles
  - name: "California manager"
    schema_grant: { ... }
```

---

## schema (header)

Maps to the Mondrian-4 `<Schema name="..." metamodelVersion="...">` root element.

| Key | Required | XML attribute | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Schema display name |
| `metamodel_version` | no | `metamodelVersion=` | Typically `"4.0"` |

**Short form** (name only):

```yaml
schema: FoodMart
```

**Long form** (with metamodel version):

```yaml
schema:
  name: "FoodMart"
  metamodel_version: "4.0"
```

---

## annotations

Maps to `<Annotations><Annotation name="..." cdata/></Annotations>`. Supported at the following levels:

- **schema** — top-level `annotations:` key
- **cube** — `annotations:` key inside a cube body
- **dimension** — `annotations:` key inside a shared or inline dimension body
- **hierarchy** — `annotations:` key inside a hierarchy body
- **level** — `annotations:` map form of a level entry (see [levels inside a hierarchy](#levels-inside-a-hierarchy))
- **attribute** — `annotations:` key inside an attribute body (map form)
- **measure** — `annotations:` key inside a measure definition
- **calculated_member** — `annotations:` key inside a calculated member body
- **role** — `annotations:` key inside a role body

The value is a YAML map of `name: text` pairs. Annotation names are arbitrary strings; Mondrian uses dot-qualified names (e.g. `caption.de_DE`) as a convention for locale-specific metadata.

```yaml
annotations:
  caption.de_DE: "Verkaufen"
  caption.fr_FR: "Ventes"
  description.fr_FR: "Cube des ventes"
```

**Annotated dimension example:**

```yaml
shared_dimensions:
  Store:
    table: "store"
    key: "Store Id"
    annotations:
      caption.de_DE: "Geschäft"
    attributes:
      - name: "Store Country"
        key_column: "store_country"
        has_hierarchy: false
        annotations:
          description: "ISO country code of the store"
```

**Annotated level example** (map form required when adding annotations to a level):

```yaml
hierarchies:
  - name: "Stores"
    levels:
      - name: "Store Country"
        annotations:
          caption.fr_FR: "Pays"
      - "Store State"
      - "Store City"
      - "Store Name"
```

---

## physical_schema

Maps to `<PhysicalSchema>`. Declares the physical tables and the relationships between them. Contains two sub-keys: `tables` and `links`.

### tables

A list of table definitions. Each table maps to a `<Table>` element inside `<PhysicalSchema>`.

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Table name in the database |
| `alias` | no | `alias=` | Alternative name used elsewhere in the schema |
| `schema` | no | `schema=` | Database schema qualifier (e.g. `dbo`) |
| `key_column` | no | `keyColumn=` | Single-column shorthand for the primary key |
| `key` | no | `<Key><Column .../></Key>` | Multi-column primary key (list of column name strings) |
| `calculated_columns` | no | `<ColumnDefs>` | Derived columns defined as SQL expressions (see below) |

`key_column` and `key` are mutually exclusive. Use `key_column` for a single column; use `key` for composite keys.

```yaml
physical_schema:
  tables:
    - name: "customer"
      key:
        - "customer_id"
    - name: "product"
      key_column: "product_id"
    - name: "salary"
      alias: "salary2"           # second alias for a self-join
    - name: "sales_fact_1997"    # no key declared — fact table
```

#### calculated_columns

Defines virtual columns computed from SQL expressions. Maps to `<ColumnDefs><CalculatedColumnDef .../>`.

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Column name used elsewhere in the schema |
| `type` | no | `type=` | Mondrian data type (e.g. `String`, `Numeric`, `Integer`) |
| `expression` | yes | `<ExpressionView><SQL dialect="...">` | Map of dialect → SQL body |

The `expression` value is a map whose keys are SQL dialect names (`generic`, `mysql`, `oracle`, `postgres`, `mssql`, `access`, `derby`, `db2`, `luciddb`, etc.) and whose values are the SQL body for that dialect.

Inside a SQL body, inline column references are written as `{col:column_name}` (column in the same table) or `{col:table.column_name}` (qualified). These tokens are parsed back to `<Column table="..." name="..."/>` elements by the converter.

```yaml
- name: "customer"
  key:
    - "customer_id"
  calculated_columns:
    - name: "full_name"
      type: "String"
      expression:
        oracle:   "{col:fname} || ' ' || {col:lname}"
        mysql:    "CONCAT({col:fname}, ' ', {col:lname})"
        mssql:    "{col:fname} + ' ' + {col:lname}"
        generic:  "{col:fullname}"

- name: "sales_fact_1997"
  calculated_columns:
    - name: "promotion_sales"
      expression:
        access:  "Iif({col:sales_fact_1997.promotion_id} = 0, 0, {col:sales_fact_1997.store_sales})"
        generic: "case when {col:sales_fact_1997.promotion_id} = 0 then 0 else {col:sales_fact_1997.store_sales} end"
```

### links

Defines foreign-key relationships between tables. Maps to `<Link source="..." target="...">` elements inside `<PhysicalSchema>`.

| Key | Required | XML | Notes |
|---|---|---|---|
| `source` | yes | `source=` | Name of the child (many-side) table |
| `target` | yes | `target=` | Name of the parent (one-side) table |
| `foreign_key` | no* | `<ForeignKey><Column .../></ForeignKey>` | List of FK column names (multi-column) |
| `foreign_key_column` | no* | `foreignKeyColumn=` | Single FK column name (shorthand) |

*One of `foreign_key` or `foreign_key_column` is required.

```yaml
links:
  - source: "product_class"
    target: "product"
    foreign_key:
      - "product_class_id"
  - source: "store"
    target: "employee"
    foreign_key:
      - "store_id"
```

---

## shared_dimensions

Maps to top-level `<Dimension>` elements in the Mondrian-4 schema (outside any cube). The value is a YAML map of `dimension_name: dimension_body`. The map key becomes the dimension's `name` attribute.

| Key | Required | XML | Notes |
|---|---|---|---|
| `table` | no | `table=` | Default table for attributes (may be overridden per-attribute) |
| `key` | no | `key=` | Name of the key attribute (must match an attribute `name`) |
| `type` | no | `type=` | `"TIME"` for time dimensions; omit or leave blank for standard |
| `attributes` | no | `<Attributes>` | List of attribute definitions (see below) |
| `hierarchies` | no | `<Hierarchies>` | List of hierarchy definitions (see below) |

```yaml
shared_dimensions:
  Store:
    table: "store"
    key: "Store Id"
    attributes: [...]
    hierarchies: [...]

  Time:
    table: "time_by_day"
    key: "Time Id"
    type: "TIME"
    attributes: [...]
    hierarchies: [...]
```

### attributes

A list of attribute definitions inside a dimension. Each attribute maps to a `<Attribute>` element inside `<Attributes>`.

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Attribute display name |
| `table` | no | `table=` | Override the dimension's default table for this attribute |
| `key_column` | no* | `keyColumn=` | Single-column key (shorthand) |
| `key` | no* | `<Key><Column .../></Key>` | Multi-column key (list of `"table.column"` or `"column"` strings) |
| `name_column` | no | `nameColumn=` | Column for the member display name |
| `name_columns` | no | `<Name><Column .../></Name>` | Multi-column name (list of column strings) |
| `order_by_column` | no | `orderByColumn=` | Column used for member ordering |
| `caption_column` | no | `captionColumn=` | Column used for member caption |
| `level_type` | no | `levelType=` | Time grain: `TimeYears`, `TimeQuarters`, `TimeMonths`, `TimeWeeks`, `TimeDays` |
| `datatype` | no | `datatype=` | `Boolean`, `Numeric`, `Integer`, `String` (default `String` is omitted) |
| `has_hierarchy` | no | `hasHierarchy=` | `false` suppresses the auto-generated single-attribute hierarchy (default `true`; omit when true) |
| `hierarchy_all_member_name` | no | `hierarchyAllMemberName=` | All-member label for the auto-generated hierarchy |
| `hierarchy_all_member_caption` | no | `hierarchyAllMemberCaption=` | All-member caption for the auto-generated hierarchy |
| `hierarchy_default_member` | no | `hierarchyDefaultMember=` | Default member for the auto-generated hierarchy |
| `hierarchy_has_all` | no | `hierarchyHasAll=` | `false` suppresses the All level in the auto-generated hierarchy |
| `properties` | no | `<Property attribute="..."/>` | List of sibling attribute names that are properties of this attribute |

*`key_column` and `key` are mutually exclusive. Prefer `key_column` for a single column.

For cross-table column references inside `key` or `name_columns`, qualify with `table.column`:

```yaml
attributes:
  - name: "Store Country"
    key_column: "store_country"
    has_hierarchy: false

  - name: "Store City"
    key:
      - "store_state"
      - "store_city"
    name_column: "store_city"
    has_hierarchy: false

  - name: "Store Name"
    key_column: "store_name"
    properties:
      - "Store Type"
      - "Store Manager"
      - "Store Sqft"

  - name: "Brand Name"
    table: "product"
    key:
      - "product_class.product_family"   # qualified cross-table ref
      - "product_class.product_department"
      - "product_class.product_category"
      - "product_class.product_subcategory"
      - "brand_name"
    name_column: "brand_name"
    has_hierarchy: false

  - name: "Year"
    key_column: "the_year"
    level_type: "TimeYears"
    has_hierarchy: false

  - name: "Has coffee bar"
    key_column: "coffee_bar"
    datatype: "Boolean"
    has_hierarchy: false
```

### hierarchies

A list of hierarchy definitions inside a dimension. Each hierarchy maps to a `<Hierarchy>` element inside `<Hierarchies>`.

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Hierarchy name |
| `all_member_name` | no | `allMemberName=` | Label for the All member |
| `default_member` | no | `defaultMember=` | MDX unique name of the default member |
| `has_all` | no | `hasAll=` | `false` suppresses the All level (default is `true` when omitted) |
| `levels` | yes | `<Level>` children | Ordered list of levels (see below) |

#### levels inside a hierarchy

Each entry in the `levels` list is either a bare string (when the level name equals the attribute name) or a `{name, attribute}` map (when the level has a display name different from the referenced attribute):

```yaml
hierarchies:
  - name: "Stores"
    all_member_name: "All Stores"
    levels:
      - "Store Country"       # bare string — name == attribute name
      - "Store State"
      - "Store City"
      - "Store Name"

  - name: "Time"
    has_all: false
    levels:
      - "Year"
      - "Quarter"
      - "Month"

  - name: "Education Level"
    levels:
      - name: "Education Level"   # map form — level name differs from attribute
        attribute: "Education"
```

**Full example — the `Store` shared dimension:**

```yaml
shared_dimensions:
  Store:
    table: "store"
    key: "Store Id"
    attributes:
      - name: "Store Country"
        key_column: "store_country"
        has_hierarchy: false
      - name: "Store State"
        key_column: "store_state"
        has_hierarchy: false
      - name: "Store City"
        key:
          - "store_state"
          - "store_city"
        name_column: "store_city"
        has_hierarchy: false
      - name: "Store Id"
        key_column: "store_id"
        has_hierarchy: false
      - name: "Store Name"
        key_column: "store_name"
        has_hierarchy: false
        properties:
          - "Store Type"
          - "Store Manager"
      - name: "Store Type"
        key_column: "store_type"
        hierarchy_all_member_name: "All Store Types"
    hierarchies:
      - name: "Stores"
        all_member_name: "All Stores"
        levels:
          - "Store Country"
          - "Store State"
          - "Store City"
          - "Store Name"
      - name: "Store Size in SQFT"
        levels:
          - "Store Sqft"
```

---

## cubes

Maps to `<Cube>` elements. The value is a YAML map of `cube_name: cube_body`. The map key becomes the cube's `name` attribute.

| Key | Required | XML | Notes |
|---|---|---|---|
| `default_measure` | no | `defaultMeasure=` | Name of the default measure |
| `annotations` | no | `<Annotations>` | Map of name → text (same shape as schema-level annotations) |
| `dimensions` | no | `<Dimensions>` | List of dimension usages and local dimension definitions |
| `measure_groups` | no | `<MeasureGroups>` | List of measure group definitions |
| `calculated_members` | no | `<CalculatedMembers>` | List of calculated member definitions |
| `named_sets` | no | `<NamedSets>` | List of named set definitions |

```yaml
cubes:
  Sales:
    default_measure: "Unit Sales"
    annotations:
      caption.de_DE: "Verkaufen"
    dimensions: [...]
    measure_groups: [...]
    calculated_members: [...]
```

### dimensions (cube-level)

A list of dimension entries. Each entry is either a **usage** (reference to a shared dimension) or a **local definition** (inline dimension defined only for this cube).

#### Usage (reference to a shared dimension)

A map containing only `source`:

```yaml
dimensions:
  - source: "Store"
  - source: "Time"
  - source: "Product"
```

#### Local definition (inline dimension)

A map with `name` plus the full dimension body (same keys as `shared_dimensions`, including `table`, `key`, `type`, `attributes`, `hierarchies`):

```yaml
dimensions:
  - name: "Customer"
    table: "customer"
    key: "Name"
    attributes:
      - name: "Country"
        key_column: "country"
        has_hierarchy: false
      - name: "Name"
        key_column: "customer_id"
        name_column: "full_name"
        order_by_column: "full_name"
        has_hierarchy: false
      - name: "Gender"
        key_column: "gender"
    hierarchies:
      - name: "Customers"
        all_member_name: "All Customers"
        levels:
          - "Country"
          - "State Province"
          - "City"
          - "Name"
      - name: "Education Level"
        levels:
          - name: "Education Level"
            attribute: "Education"
```

### measure_groups

A list of measure group definitions. Each maps to a `<MeasureGroup>` element.

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | no | `name=` | Measure group name; optional for the primary group |
| `table` | yes | `table=` | Fact or aggregate table name |
| `type` | no | `type=` | `"aggregate"` for aggregate measure groups; omit for the default `"fact"` type |
| `approx_row_count` | no | `approxRowCount=` | Hint to the engine for the approximate row count of the fact table (string, e.g. `"86837"`) |
| `ignore_unrelated_dimensions` | no | `ignoreUnrelatedDimensions=` | `true` tells Mondrian to treat unrelated dimensions as `[All]` rather than returning null (boolean) |
| `measures` | no | `<Measures>` | List of measure or measure-ref definitions |
| `dimension_links` | no | `<DimensionLinks>` | List of links from this measure group to its dimensions |

```yaml
measure_groups:
  - name: "Sales"
    table: "sales_fact_1997"
    measures: [...]
    dimension_links: [...]

  - table: "agg_c_special_sales_fact_1997"
    type: "aggregate"
    measures: [...]
    dimension_links: [...]
```

#### measures

Each entry in `measures` is either a **measure definition** (has `name`) or a **measure reference** (has `ref`).

**Measure definition** — maps to `<Measure>`:

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Measure display name |
| `column` | no | `column=` | Source column |
| `table` | no | `table=` | Override table (rare; usually taken from the measure group) |
| `aggregator` | yes | `aggregator=` | `sum`, `count`, `distinct-count`, `min`, `max`, `avg` |
| `format_string` | no | `formatString=` | MDX format string (e.g. `"#,###.00"`, `"Standard"`, `"Currency"`) |
| `datatype` | no | `datatype=` | `Numeric`, `Integer`, `String` |
| `properties` | no | `<CalculatedMemberProperty>` | List of `{name, value}` maps (e.g. `MEMBER_ORDINAL`) |

**Measure reference** — maps to `<MeasureRef>` (used in aggregate measure groups to point at a measure defined in the primary group):

| Key | Required | XML | Notes |
|---|---|---|---|
| `ref` | yes | `name=` | Name of the referenced measure |
| `agg_column` | no | `aggColumn=` | Column in the aggregate table that holds the pre-aggregated value |

```yaml
measures:
  # Measure definition
  - name: "Unit Sales"
    column: "unit_sales"
    aggregator: "sum"
    format_string: "Standard"

  - name: "Customer Count"
    column: "customer_id"
    aggregator: "distinct-count"
    format_string: "#,###"

  - name: "Promotion Sales"
    column: "promotion_sales"
    aggregator: "sum"
    format_string: "#,###.00"
    datatype: "Numeric"

  # Measure with MEMBER_ORDINAL property
  - name: "Sales Count"
    column: "product_id"
    aggregator: "count"
    format_string: "#,###"
    properties:
      - name: "MEMBER_ORDINAL"
        value: "1"

  # Measure reference (inside an aggregate measure group)
  - ref: "Unit Sales"
    agg_column: "unit_sales_sum"

  - ref: "Fact Count"
    agg_column: "fact_count"
```

#### dimension_links

A list of links connecting this measure group to each dimension in the cube. Each entry has a `type` field that determines the link kind and its remaining fields.

**`foreign_key`** — the standard join from a fact table to a dimension via a foreign key column. Maps to `<ForeignKeyLink>`.

| Key | Required | Notes |
|---|---|---|
| `type` | yes | `"foreign_key"` |
| `dimension` | yes | Dimension name |
| `foreign_key_column` | no* | Single FK column name |
| `foreign_key` | no* | List of FK column name strings (compound FK) |
| `attribute` | no | Attribute to join to (when the FK does not point to the dimension key) |

*One of `foreign_key_column` or `foreign_key` is required.

```yaml
dimension_links:
  - type: "foreign_key"
    dimension: "Store"
    foreign_key_column: "store_id"

  - type: "foreign_key"
    dimension: "Warehouse"
    foreign_key:
      - "warehouse_id"

  - type: "foreign_key"
    dimension: "Time"
    foreign_key_column: "pay_date"
    attribute: "Date"        # join to the Date attribute, not the key
```

**`copy`** — the aggregate table inherits its dimension data from another measure group (no explicit join column needed). Maps to `<CopyLink>`.

| Key | Required | Notes |
|---|---|---|
| `type` | yes | `"copy"` |
| `dimension` | yes | Dimension name |
| `column_refs` | no | List of `{table, name, agg_column}` maps mapping dimension columns to their aggregate-table counterparts |

Each entry in `column_refs` has:

| Key | Required | Notes |
|---|---|---|
| `table` | no | Source dimension table name (omit if unambiguous) |
| `name` | yes | Dimension column name |
| `agg_column` | no | Corresponding column name in the aggregate table (omit if the same as `name`) |

> **Note:** The `attribute` XML attribute on `<CopyLink>` is a no-op in Mondrian and is intentionally not round-tripped by the converter.

```yaml
- type: "copy"
  dimension: "Time"
  column_refs:
    - table: "time_by_day"
      name: "the_year"
      agg_column: "time_year"
    - table: "time_by_day"
      name: "quarter"
      agg_column: "quarter"
    - table: "time_by_day"
      name: "month_of_year"
      agg_column: "month_of_year"
```

**`no_link`** — this measure group does not link to this dimension (Mondrian ignores queries that slice by this dimension for this measure group). Maps to `<NoLink>`.

| Key | Required | Notes |
|---|---|---|
| `type` | yes | `"no_link"` |
| `dimension` | yes | Dimension name |

```yaml
- type: "no_link"
  dimension: "Warehouse"
```

**`fact`** — the dimension's data comes directly from the fact table itself (the fact table and dimension table are the same). Maps to `<FactLink>`.

| Key | Required | Notes |
|---|---|---|
| `type` | yes | `"fact"` |
| `dimension` | yes | Dimension name |

```yaml
- type: "fact"
  dimension: "Store"
- type: "fact"
  dimension: "Store Type"
```

**`reference`** — the dimension is reached indirectly via another dimension's attribute (a bridge/snowflake path). Maps to `<ReferenceLink>`.

| Key | Required | Notes |
|---|---|---|
| `type` | yes | `"reference"` |
| `dimension` | yes | Dimension name |
| `via_dimension` | no | Intermediate dimension name |
| `via_attribute` | no | Attribute on the intermediate dimension used to join |

```yaml
- type: "reference"
  dimension: "Store"
  via_dimension: "Employee"
  via_attribute: "Store Id"
```

**Full measure group example** (from the Sales cube):

```yaml
measure_groups:
  - name: "Sales"
    table: "sales_fact_1997"
    measures:
      - name: "Unit Sales"
        column: "unit_sales"
        aggregator: "sum"
        format_string: "Standard"
      - name: "Store Cost"
        column: "store_cost"
        aggregator: "sum"
        format_string: "#,###.00"
      - name: "Customer Count"
        column: "customer_id"
        aggregator: "distinct-count"
        format_string: "#,###"
    dimension_links:
      - type: "foreign_key"
        dimension: "Store"
        foreign_key_column: "store_id"
      - type: "foreign_key"
        dimension: "Time"
        foreign_key_column: "time_id"
      - type: "foreign_key"
        dimension: "Product"
        foreign_key_column: "product_id"

  - table: "agg_c_special_sales_fact_1997"
    type: "aggregate"
    measures:
      - ref: "Fact Count"
        agg_column: "fact_count"
      - ref: "Unit Sales"
        agg_column: "unit_sales_sum"
      - ref: "Store Sales"
        agg_column: "store_sales_sum"
    dimension_links:
      - type: "foreign_key"
        dimension: "Store"
        foreign_key_column: "store_id"
      - type: "copy"
        dimension: "Time"
```

### calculated_members

A list of calculated member definitions. Each maps to `<CalculatedMember>`.

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Member name |
| `dimension` | no | `dimension=` | Target dimension (e.g. `"Measures"`) |
| `hierarchy` | no | `hierarchy=` | Target hierarchy MDX unique name |
| `parent` | no | `parent=` | Parent member MDX unique name |
| `formula` | no | `formula=` | MDX formula (inline) |
| `format_string` | no | `formatString=` | MDX format string |
| `caption` | no | `caption=` | Display caption (overrides `name` in client tools) |
| `description` | no | `description=` | Human-readable description |
| `visible` | no | `visible=` | `false` hides the member from client tools (boolean; default `true`, omitted when true) |
| `cell_formatter` | no | `<CellFormatter>` | Custom cell formatter (see below) |
| `properties` | no | `<CalculatedMemberProperty>` | List of `{name, value}` maps or `{name, expression}` maps |

The `properties` list supports both `value` (static string) and `expression` (MDX expression string) on each entry. Common property names: `FORMAT_STRING`, `SOLVE_ORDER`, `MEMBER_ORDINAL`.

#### cell_formatter

Specifies a custom Java or scripted cell formatter. Maps to `<CellFormatter>`.

| Key | Required | Notes |
|---|---|---|
| `class_name` | no | Fully qualified Java class name implementing `CellFormatter` |
| `script` | no | Inline script map (see below) |

The `script` map has:

| Key | Required | Notes |
|---|---|---|
| `language` | no | Script language (e.g. `"JavaScript"`) |
| `body` | yes | Script body text |

```yaml
calculated_members:
  - name: "Profit"
    dimension: "Measures"
    formula: "[Measures].[Store Sales] - [Measures].[Store Cost]"
    properties:
      - name: "FORMAT_STRING"
        value: "$#,##0.00"

  - name: "Profit Growth"
    dimension: "Measures"
    formula: "([Measures].[Profit] - [Measures].[Profit last Period]) / [Measures].[Profit last Period]"
    properties:
      - name: "FORMAT_STRING"
        value: "0.0%"

  - name: "Employee Salary"
    dimension: "Measures"
    formula: "([Employees].currentmember.datamember, [Measures].[Org Salary])"
    format_string: "Currency"

  # With caption, description, visible, and a scripted cell formatter
  - name: "Internal Profit"
    dimension: "Measures"
    formula: "[Measures].[Store Sales] - [Measures].[Store Cost]"
    caption: "Profit"
    description: "Net store profit after cost."
    visible: false
    cell_formatter:
      script:
        language: "JavaScript"
        body: "return '$' + value;"
```

### named_sets

A list of named set definitions inside a cube. Each maps to `<NamedSet>`. Valid at both cube scope and schema scope (top-level `named_sets:` key, though the schema-level key is not exercised by the FoodMart demo).

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Set name |
| `formula` | yes | `formula=` | MDX set expression |

```yaml
named_sets:
  - name: "Top Sellers"
    formula: "TopCount([Warehouse].[Warehouse Name].MEMBERS, 5, [Measures].[Warehouse Sales])"
```

---

## roles

A list of role definitions. Each maps to a `<Role>` element. Roles are emitted last in the XML so cube references resolve correctly.

| Key | Required | XML | Notes |
|---|---|---|---|
| `name` | yes | `name=` | Role name |
| `class_name` | no | `className=` | Java class that implements custom role logic |
| `schema_grant` | no | `<SchemaGrant>` | Top-level grant (see below) |

### schema_grant

| Key | Required | XML | Notes |
|---|---|---|---|
| `access` | yes | `access=` | `"all"`, `"none"`, `"all_dimensions"`, or `"custom"` |
| `cubes` | no | `<CubeGrant>` children | List of per-cube grants |

### cube grant

| Key | Required | XML | Notes |
|---|---|---|---|
| `cube` | yes | `cube=` | Cube name |
| `access` | yes | `access=` | `"all"`, `"none"`, or `"custom"` |
| `dimensions` | no | `<DimensionGrant>` children | List of per-dimension grants (see below) |
| `hierarchies` | no | `<HierarchyGrant>` children | List of per-hierarchy grants |

### dimension grant

| Key | Required | XML | Notes |
|---|---|---|---|
| `dimension` | yes | `dimension=` | Dimension name |
| `access` | yes | `access=` | `"all"`, `"none"`, or `"custom"` |

### hierarchy grant

| Key | Required | XML | Notes |
|---|---|---|---|
| `hierarchy` | yes | `hierarchy=` | Hierarchy MDX unique name (e.g. `"[Store].[Stores]"`) |
| `access` | yes | `access=` | `"all"`, `"none"`, or `"custom"` |
| `top_level` | no | `topLevel=` | MDX unique name of the topmost visible level |
| `bottom_level` | no | `bottomLevel=` | MDX unique name of the bottommost visible level |
| `rollup_policy` | no | `rollupPolicy=` | `"full"`, `"partial"`, or `"hidden"` |
| `members` | no | `<MemberGrant>` children | List of per-member grants |

### member grant

| Key | Required | XML | Notes |
|---|---|---|---|
| `member` | yes | `member=` | Member MDX unique name |
| `access` | yes | `access=` | `"all"` or `"none"` |

**Full example:**

```yaml
roles:
  - name: "California manager"
    schema_grant:
      access: "none"
      cubes:
        - cube: "Sales"
          access: "all"
          dimensions:
            - dimension: "Gender"
              access: "none"
          hierarchies:
            - hierarchy: "[Store].[Stores]"
              access: "custom"
              top_level: "[Store].[Stores].[Store Country]"
              members:
                - member: "[Store].[Stores].[USA].[CA]"
                  access: "all"
                - member: "[Store].[Stores].[USA].[CA].[Los Angeles]"
                  access: "none"
            - hierarchy: "[Customer].[Customers]"
              access: "custom"
              top_level: "[Customer].[Customers].[State Province]"
              bottom_level: "[Customer].[Customers].[City]"
              rollup_policy: "partial"
              members:
                - member: "[Customer].[Customers].[USA].[CA]"
                  access: "all"

  - name: "No HR Cube"
    schema_grant:
      access: "all"
      cubes:
        - cube: "HR"
          access: "none"
```

---

## Column reference encoding

Two encoding conventions appear throughout the schema format for referencing columns by string:

### `table.column` qualified references (in `key`, `name_columns`)

Inside `key` and `name_columns` lists, a column belonging to a non-default table is written as `"table_name.column_name"`. The converter splits on the first `.` character to recover `table` and `column`.

```yaml
key:
  - "product_class.product_family"
  - "product_class.product_department"
  - "brand_name"                       # unqualified — belongs to the default table
```

### `{col:...}` tokens (in calculated_column expression bodies)

Inside SQL expression bodies for calculated columns, inline column references are written as `{col:column_name}` or `{col:table.column_name}`. The converter parses these tokens and reconstructs `<Column>` elements in the Mondrian-4 XML mixed content.

```yaml
expression:
  mysql: "CONCAT({col:fname}, ' ', {col:lname})"
  generic: "{col:fullname}"
  access: "Iif({col:sales_fact_1997.promotion_id} = 0, 0, {col:sales_fact_1997.store_sales})"
```

---

## Known limitations

The following items are not yet fully captured by the M4 YAML converter. They are derived from code comments and TODOs in the converter source.

> **Note:** As of version 4.8.1.16, items previously listed as limitations — CopyLink `column_refs`, DimensionGrant in roles, sub-cube annotations, CalculatedMember `caption`/`description`/`visible`/`CellFormatter`, and MeasureGroup `approx_row_count`/`ignore_unrelated_dimensions` — are now fully supported. The `attribute` XML attribute on `<CopyLink>` is a no-op in Mondrian and is intentionally not round-tripped. The remaining open items are tracked in #86/#87.

1. **`{col:}` token assumption**: The `{col:table.column}` encoding in calculated-column SQL bodies assumes that (a) plain SQL text does not contain the literal sequence `{col:` and (b) table and column identifiers do not contain a literal `.` character. Quoted identifiers with embedded dots will not round-trip correctly.

2. **`table.column` dot assumption**: The `table.column` encoding in `key` and `name_columns` lists splits on the first `.`. Table or column names that themselves contain a `.` (e.g. quoted identifiers) will not round-trip correctly.

3. **Whitespace in calculated-column SQL**: The YAML serializer may introduce extra leading whitespace or line-break tokens when re-reading a YAML file that was machine-generated. SQL bodies with significant leading whitespace may acquire extra indentation on a second round-trip.

4. **`$ref` includes require a file URL**: `$ref` resolution only works when the schema is loaded via a `Catalog=file:///...` connect-string property. Inline loading via `CatalogContent` has no base directory and skips `$ref` resolution.

---

## See also

- [yaml-schema.md](yaml-schema.md) — Mondrian-3 legacy YAML format (M3 XML: `<DimensionUsage>`, `<Join>`, `<Level>`, etc.)
- `demo/FoodMart.yaml` — the canonical M4 YAML schema, generated from the FoodMart M4 XML fixture
- `src/main/java/mondrian/schema/yaml/m4/` — converter source (`M4YamlToXml`, `M4CubeBuilder`, `M4XmlToYaml`, `M4CubeIngester`)
