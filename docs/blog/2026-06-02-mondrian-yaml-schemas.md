# Mondrian-4 YAML Schemas: Write Less, Ship More

**Published:** 2026-06-02  
**Author:** Spicule Engineering  
**Branch / Issue:** #34 — M4 YAML schema pipeline

---

Mondrian's XML schema format is one of the most expressive schema languages in open-source BI. It is also one of the noisiest to write by hand. Every attribute is quoted, every element is closed, and every relationship lives inside a deeply nested angle-bracket tree that is completely invisible in a `git diff`. A single typo in a namespace declaration can prevent the engine from loading at all, and the error message tells you approximately nothing.

What if you could write the same schema in YAML?

As of `mondrian-saiku` 4.8.1.11 (PR #34), you can.

---

## What shipped

Three things landed together:

**1. YAML as a first-class catalog format.** `RolapSchemaLoader` now auto-detects YAML content by inspecting the catalog string — if it begins with `schema:` (after any whitespace, comments, or `---` document markers) it is routed through the converter before the rest of the pipeline sees it. No configuration flag, no file-extension check, no extra properties. Point your `Catalog` URL at a `.yaml` file and Mondrian loads it.

**2. A bidirectional converter.** The new `M4YamlToXml` / `M4XmlToYaml` pair converts in both directions with symmetric fidelity. The canonical representation stays XML (that is what XOM, the Mondrian XML object model, serializes); YAML is translated to a typed `MondrianDef` object graph first, so the converter is not fragile string-munging — it is the same structured pipeline Mondrian uses to load schemas from disk.

**3. A `mondrian-schema` CLI.** A single shell script wraps the converter and a linter:

```bash
# Convert an existing Mondrian-4 XML schema to YAML
./scripts/mondrian-schema to-yaml demo/FoodMart.mondrian.xml -o demo/FoodMart.yaml

# Convert back (lossless round-trip)
./scripts/mondrian-schema to-xml demo/FoodMart.yaml -o FoodMart-roundtrip.xml

# Lint a schema before deploying (validates structure; exits 2 on failure)
./scripts/mondrian-schema lint demo/FoodMart.yaml
```

First invocation compiles and caches the classpath (`target/dependency/classpath.txt`). Subsequent runs are fast — around 130 ms.

Mondrian-3 support is unchanged. The M3 YAML format (shared dimensions as `<DimensionUsage>`, `<Join>`, `<Level>`, etc.) continues to work and is documented in [`docs/yaml-schema.md`](../yaml-schema.md). M3 and M4 YAML are mutually exclusive and detected by the presence of the `physical_schema:` key.

---

## Show, don't tell

Here is the `Store` shared dimension from the FoodMart schema — first as Mondrian-4 XML, then as its YAML equivalent.

### XML (51 lines)

```xml
<Dimension name='Store' table='store' key='Store Id'>
    <Attributes>
        <Attribute name='Store Country' hasHierarchy='false'>
            <Key>
                <Column name='store_country'/>
            </Key>
        </Attribute>
        <Attribute name='Store State' keyColumn='store_state' hasHierarchy='false'/>
        <Attribute name='Store City' hasHierarchy='false'>
            <Key>
                <Column name='store_state'/>
                <Column name='store_city'/>
            </Key>
            <Name>
                <Column name='store_city'/>
            </Name>
        </Attribute>
        <Attribute name='Store Id' keyColumn='store_id' hasHierarchy='false'/>
        <Attribute name='Store Name' keyColumn='store_name' hasHierarchy='false'>
            <Property attribute='Store Type'/>
            <Property attribute='Store Manager'/>
            <Property attribute='Store Sqft'/>
            <Property attribute='Grocery Sqft'/>
            <Property attribute='Frozen Sqft'/>
            <Property attribute='Meat Sqft'/>
            <Property attribute='Has coffee bar'/>
            <Property attribute='Street address'/>
        </Attribute>
        <Attribute name='Store Type' keyColumn='store_type'
                   hierarchyAllMemberName='All Store Types'/>
        <Attribute name='Store Manager' keyColumn='store_manager' hasHierarchy='false'/>
        <Attribute name='Store Sqft' keyColumn='store_sqft' hasHierarchy='false'/>
        <Attribute name='Grocery Sqft' keyColumn='grocery_sqft' hasHierarchy='false'/>
        <Attribute name='Frozen Sqft' keyColumn='frozen_sqft' hasHierarchy='false'/>
        <Attribute name='Meat Sqft' keyColumn='meat_sqft' hasHierarchy='false'/>
        <Attribute name='Has coffee bar' keyColumn='coffee_bar' hasHierarchy='false'/>
        <Attribute name='Street address' keyColumn='store_street_address' hasHierarchy='false'/>
    </Attributes>
    <Hierarchies>
        <Hierarchy name='Stores' allMemberName='All Stores'>
            <Level attribute='Store Country'/>
            <Level attribute='Store State'/>
            <Level attribute='Store City'/>
            <Level attribute='Store Name'/>
        </Hierarchy>
        <Hierarchy name='Store Size in SQFT'>
            <Level attribute='Store Sqft'/>
        </Hierarchy>
    </Hierarchies>
</Dimension>
```

### YAML (38 lines, from `demo/FoodMart.yaml`)

```yaml
shared_dimensions:
  Store:
    table: "store"
    key: "Store Id"
    attributes:
    - name: "Store Country"
      key: ["store_country"]
      has_hierarchy: false
    - name: "Store State"
      key_column: "store_state"
      has_hierarchy: false
    - name: "Store City"
      key: ["store_state", "store_city"]
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
      - "Store Sqft"
      - "Grocery Sqft"
      - "Frozen Sqft"
      - "Meat Sqft"
      - "Has coffee bar"
      - "Street address"
    - name: "Store Type"
      key_column: "store_type"
      hierarchy_all_member_name: "All Store Types"
    hierarchies:
    - name: "Stores"
      all_member_name: "All Stores"
      levels: ["Store Country", "Store State", "Store City", "Store Name"]
    - name: "Store Size in SQFT"
      levels: ["Store Sqft"]
```

The information content is identical. What disappeared: 79 closing tags, 34 attribute-quotation pairs, the `<Key><Column .../></Key>` ceremony for multi-column keys (replaced by a plain list), and the `<Level attribute='...'/>` dance for hierarchy levels (replaced by a bare list of strings). What remained: every structural relationship, every column reference, every property association.

---

## How this makes your life easier

**Migrate in one command.** If you have an existing Mondrian-4 XML schema, converting it is a single CLI call:

```bash
./scripts/mondrian-schema to-yaml your-schema.xml -o your-schema.yaml
```

The output is immediately loadable — Mondrian detects the YAML format on next startup with no other changes.

**Diff and code-review schemas like real code.** XML diffs are dominated by closing-tag churn and indentation noise. YAML diffs show only the structural change. A hierarchy level addition is one line; adding a property to an attribute is one line. Pull-request reviews for schema changes become tractable.

**Lint before you deploy.** The `lint` subcommand validates the schema's structure and reports errors with a non-zero exit code, which makes it trivially hookable into CI:

```bash
./scripts/mondrian-schema lint your-schema.yaml
# exit 0 → clean
# exit 2 → diagnostic printed to stderr, CI fails
```

**Split large schemas across files.** For schemas that have grown unwieldy, YAML's `$ref` include mechanism lets you break one big file into logical pieces:

```yaml
# top-level schema file
schema: "MyWarehouse"
shared_dimensions:
  $ref: "dimensions/store.yaml"
cubes:
  $ref: "cubes/sales.yaml"
```

`$ref` resolution works when the schema is loaded via a `file://` Catalog URL; it is skipped when content is passed inline.

**Keep schemas in version control readably.** Because YAML is plain text with clean diff behaviour, you can commit your schema next to your dbt models or Liquibase migrations and treat schema changes as first-class tracked changes rather than opaque XML blobs.

---

## How it works

The architecture is straightforward and avoids string-munging entirely.

When `RolapSchemaLoader` reads a catalog and detects YAML content, it calls `YamlSchemaConverter.toXml()`. That method checks for a `metamodel_version` key to distinguish M4 from M3 YAML, then hands off to `M4YamlToXml`.

`M4YamlToXml` walks the parsed YAML map and builds a typed `MondrianDef.Schema` object graph — the same Java types that `RolapSchemaLoader` normally constructs from XOM-parsed XML. Once the object graph is built, XOM serializes it to XML, and from that point onward the loading pipeline is identical to reading an XML file directly.

The reverse path (`M4XmlToYaml`) uses `M4CubeIngester` to walk the `MondrianDef` graph and emit a structured YAML document. Neither direction manipulates XML as text; both operate on the typed object model. This means the converter inherits Mondrian's own structural guarantees — it cannot produce XML that does not correspond to a valid `MondrianDef` tree.

Correctness of the round-trip is enforced by a dedicated test (`FoodMartYamlSmokeCorpusEquivalenceTest`). The test converts `demo/FoodMart.yaml` to XML, loads it alongside the original `demo/FoodMart.mondrian.xml` into two separate `TestContext` instances, and runs every query in the `SmokeCorpus` against both. Any divergence in query results fails the test. A second test (`committedYamlMatchesRegenerated`) re-derives the YAML from the XML fixture and asserts byte-equality against the committed `demo/FoodMart.yaml`, preventing the two from drifting silently as the schema evolves.

---

## Try it now

```bash
# 1. Convert your Mondrian-4 XML schema to YAML
./scripts/mondrian-schema to-yaml path/to/your-schema.xml -o your-schema.yaml

# 2. Inspect and edit the YAML
# 3. Lint it
./scripts/mondrian-schema lint your-schema.yaml

# 4. Point Mondrian at the YAML file
#    (no other changes required — Mondrian auto-detects YAML by content)
jdbc:mondrian:Jdbc=jdbc:...;Catalog=file:///path/to/your-schema.yaml;...
```

If you prefer to keep XML as the source of truth, you can use YAML purely for authoring and commit the generated XML, or do the opposite — author in XML and generate YAML for review. Both directions are stable.

The full field-by-field reference, including all supported link types, calculated-column expression syntax, role and grant definitions, and the `$ref` include format, is in [`docs/m4-yaml-schema-reference.md`](../m4-yaml-schema-reference.md).

---

## Known limitations and what is next

Being upfront about gaps builds trust more than glossing over them.

The converter does not yet handle every Mondrian-4 feature:

- **`CopyLink` does not preserve the `attribute` field.** Round-tripping a `CopyLink` with a non-default attribute loses that field.
- **`DimensionGrant` in roles is not supported.** Role definitions support `SchemaGrant` and `CubeGrant` but not dimension-level grants.
- **Some `CalculatedMember` fields are deferred.** `caption`, `description`, `visible`, and `CellFormatter` are not yet captured.
- **Some `MeasureGroup` fields are deferred.** `approxRowCount` and `ignoreUnrelatedDimensions` are not emitted.
- **Identifiers with embedded dots do not round-trip.** The `table.column` encoding and `{col:}` token both split on the first `.`, which breaks quoted identifiers that contain a literal dot character.

The remaining gaps are well-understood and tracked. Priority for the next phase is filling in the `CalculatedMember` deferred fields and adding `DimensionGrant` support so that complex role definitions can be authored and reviewed in YAML without loss.

---

If you run into an edge case or have an M3 migration story to share, open an issue or reach out on the Saiku community channels.
