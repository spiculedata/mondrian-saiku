# Mondrian-4 YAML Schema Pipeline — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add full Mondrian-4 (M4) support to the YAML schema converter so M4 XML schemas (`demo/FoodMart.mondrian.xml`) round-trip through YAML losslessly, keeping the existing M3 pipeline intact, culminating in a committed `demo/FoodMart.yaml` validated against the FoodMart dataset.

**Architecture:** The two public converter classes (`XmlSchemaToYaml`, `YamlSchemaConverter`) become vocabulary dispatchers. All M4 logic lives in a new `mondrian.schema.yaml.m4` sub-package and is built on the typed `MondrianDef` (XOM) object graph: ingest parses M4 XML and walks `MondrianDef.Schema` to emit a YAML `Map` (Jackson); emit parses YAML, builds a `MondrianDef.Schema` graph, and serializes via `ElementDef.toXML()`. M3 input is untouched.

**Tech Stack:** Java 8+, Jackson `dataformat-yaml` (already used by the M3 path), XOM-generated `MondrianDef` (`target/generated-sources/xom/mondrian/olap/MondrianDef.java`), JUnit 4, Maven.

**Reference spec:** `docs/superpowers/specs/2026-06-02-m4-yaml-schema-pipeline-design.md`

---

## Verified API facts (the plan's code relies on these)

- `MondrianDef.Schema`: public fields `name`, `metamodelVersion`, `caption`, `description`, ... and `public SchemaElement[] childArray`. `PhysicalSchema` and `Cube` implement `SchemaElement`.
- `MondrianDef.PhysicalSchema`: no-arg ctor; `public PhysicalSchemaElement[] childArray`. `Table` and `Link` are physical-schema children.
- `MondrianDef.Table`: `public String name, schema, alias, keyColumn`; `public TableElement[] childArray` (holds `Key`, `ColumnDefs`).
- `MondrianDef.Key extends Columns`: `Columns` has `public Column[] array`.
- `MondrianDef.Column`: `public String table, name, aggColumn`; convenience ctor `Column(String table, String name)`.
- `MondrianDef.Link`: `public String source, target, key, foreignKeyColumn`; `public ForeignKey foreignKey` (child). `ForeignKey extends Columns` (`Column[] array`).
- `MondrianDef.Schema` is parsed from XML via `new MondrianDef.Schema(domWrapper)` (`RolapSchemaLoader.java:295`).
- Any `ElementDef` serializes to an XML string via `.toXML()` (`RolapSchemaLoader.java:291`).
- M3 ingest pattern (`XmlSchemaToYaml.toYaml`, line 72): build a `LinkedHashMap<String,Object>` tree, then `YAML.writeValueAsString(root)` where `YAML` is a Jackson `ObjectMapper(YAMLFactory)`.
- M3 emit entry (`YamlSchemaConverter.toXml`, line 193) → `emitFromRoot(Map)` (string-templated XML).
- Test harness: `TestContext.instance().withSchema(String xml)` → `.getConnection()` → `parseQuery` / `execute` (see `FoodMart3MdxEquivalenceTest`).
- `metamodelVersion='4.0'` and `<PhysicalSchema>` / `<Cube><MeasureGroups>` are the M4 markers (mirror `SchemaCli.hasMondrian4Elements`, ~line 221).

> **Note on XOM interface casts:** `childArray` element types are interfaces (`PhysicalSchemaElement`, `TableElement`, `SchemaElement`). `Table` implements `Relation` which extends `PhysicalSchemaElement`; if a direct array assignment fails to compile, declare the array as the interface type and assign elements individually (shown in the tasks). Verify the exact interface with: `grep -nE 'class (Table|Link) .*implements' target/generated-sources/xom/mondrian/olap/MondrianDef.java`.

---

## File structure (Phase 1)

- **Create** `src/main/java/mondrian/schema/yaml/m4/M4Detection.java` — vocabulary detection: `isM4Xml(org.w3c.dom.Element)` and `isM4Yaml(Map<?,?>)`. One responsibility: tell the dispatchers which path to use.
- **Create** `src/main/java/mondrian/schema/yaml/m4/M4YamlToXml.java` — YAML `Map` → `MondrianDef.Schema` graph → `.toXML()`. Phase 1 covers schema header + `physical_schema`.
- **Create** `src/main/java/mondrian/schema/yaml/m4/M4XmlToYaml.java` — `MondrianDef.Schema` → YAML `Map`. Phase 1 covers schema header + `physical_schema`.
- **Modify** `src/main/java/mondrian/schema/yaml/XmlSchemaToYaml.java` (line 72 `toYaml`) — dispatch to `M4XmlToYaml` when M4 XML detected.
- **Modify** `src/main/java/mondrian/schema/yaml/YamlSchemaConverter.java` (line 193 `toXml`) — dispatch to `M4YamlToXml` when M4 YAML detected.
- **Create** `src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java` — unit + round-trip tests for Phase 1.

Phases 2–5 add files within the same `m4/` package (see roadmap at end).

---

## Task 1: M4 detection helper

**Files:**
- Create: `src/main/java/mondrian/schema/yaml/m4/M4Detection.java`
- Test: `src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java`

- [ ] **Step 1: Write the failing test**

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml.m4;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class M4PhysicalLayerTest {

    @Test
    public void detectsM4YamlByMetamodelVersion() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("name", "FoodMart");
        schema.put("metamodel_version", "4.0");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", schema);
        assertTrue(M4Detection.isM4Yaml(root));
    }

    @Test
    public void detectsM3YamlScalarSchemaAsNotM4() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "FoodMart");          // M3 scalar form
        assertFalse(M4Detection.isM4Yaml(root));
    }

    @Test
    public void detectsM4YamlByPhysicalSchemaKey() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "FoodMart");
        root.put("physical_schema", new LinkedHashMap<>());
        assertTrue(M4Detection.isM4Yaml(root));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test-compile -q` then `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: COMPILE FAILURE — `M4Detection` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml.m4;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.Map;

/**
 * #34 M4: vocabulary detection so the YAML converters can dispatch
 * between the legacy (Mondrian-3) and modern (Mondrian-4) paths.
 */
public final class M4Detection {

    private M4Detection() {}

    /**
     * True if the parsed YAML root describes a Mondrian-4 schema.
     * M4 uses a {@code schema:} mapping carrying {@code metamodel_version}
     * (and/or a top-level {@code physical_schema} / {@code measure_groups}
     * inside cubes); M3 uses a scalar {@code schema: "Name"} top key.
     */
    public static boolean isM4Yaml(Map<?, ?> root) {
        Object schema = root.get("schema");
        if (schema instanceof Map) {
            Object v = ((Map<?, ?>) schema).get("metamodel_version");
            if (v != null && String.valueOf(v).startsWith("4")) {
                return true;
            }
        }
        return root.containsKey("physical_schema");
    }

    /**
     * True if the XML {@code <Schema>} element uses Mondrian-4 vocabulary:
     * a {@code <PhysicalSchema>} child or any {@code <Cube><MeasureGroups>}
     * grandchild. Mirrors {@code RolapSchemaLoader.hasMondrian4Elements}.
     */
    public static boolean isM4Xml(Element schema) {
        NodeList children = schema.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element el = (Element) child;
            if ("PhysicalSchema".equals(el.getTagName())) {
                return true;
            }
            if ("Cube".equals(el.getTagName())) {
                NodeList grandchildren = el.getChildNodes();
                for (int j = 0; j < grandchildren.getLength(); j++) {
                    Node gc = grandchildren.item(j);
                    if (gc.getNodeType() == Node.ELEMENT_NODE
                        && "MeasureGroups".equals(
                            ((Element) gc).getTagName()))
                    {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/m4/M4Detection.java \
        src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java
git commit -m "feat(#34): M4 vocabulary detection helper for YAML dispatch"
```

---

## Task 2: M4 emit — schema header + physical tables/keys/columns

**Files:**
- Create: `src/main/java/mondrian/schema/yaml/m4/M4YamlToXml.java`
- Test: `src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java` (add methods)

- [ ] **Step 1: Write the failing test** (append to `M4PhysicalLayerTest`)

```java
    // ---- emit (YAML -> M4 XML) ----

    private static final String PHYS_YAML =
        "schema:\n"
        + "  name: FoodMart\n"
        + "  metamodel_version: \"4.0\"\n"
        + "physical_schema:\n"
        + "  tables:\n"
        + "    - name: salary\n"
        + "    - name: salary\n"
        + "      alias: salary2\n"
        + "    - name: store\n"
        + "      key: [store_id]\n"
        + "    - name: product\n"
        + "      key_column: product_id\n";

    @Test
    public void emitsSchemaHeaderAndPhysicalTables() {
        String xml = M4YamlToXml.toXml(PHYS_YAML);
        assertTrue(xml, xml.contains("<Schema"));
        assertTrue(xml, xml.contains("name=\"FoodMart\""));
        assertTrue(xml, xml.contains("metamodelVersion=\"4.0\""));
        assertTrue(xml, xml.contains("<PhysicalSchema"));
        assertTrue(xml, xml.contains("<Table name=\"salary\""));
        assertTrue(xml, xml.contains("alias=\"salary2\""));
        // key: [store_id] -> nested <Key><Column name="store_id"/></Key>
        assertTrue(xml, xml.contains("<Key"));
        assertTrue(xml, xml.contains("name=\"store_id\""));
        // key_column shorthand -> Table keyColumn attribute
        assertTrue(xml, xml.contains("keyColumn=\"product_id\""));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: COMPILE FAILURE — `M4YamlToXml` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml.m4;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import mondrian.olap.MondrianDef;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * #34 M4: YAML -> Mondrian-4 XML. Parses the YAML into a map, builds a
 * typed {@link MondrianDef.Schema} object graph, and serializes it back
 * to XML via {@link org.eigenbase.xom.ElementDef#toXML()}. All XML
 * serialization is delegated to XOM, so the converter only maps
 * YAML structures onto MondrianDef fields.
 *
 * <p>Phase 1 covers the schema header and the {@code physical_schema}
 * (tables, keys, columns, links, calculated columns). Later phases add
 * dimensions, measure groups, roles, and calculated members.
 */
public final class M4YamlToXml {

    private static final ObjectMapper YAML =
        new ObjectMapper(new YAMLFactory());

    private M4YamlToXml() {}

    public static String toXml(String yamlText) {
        final Map<?, ?> root;
        try {
            root = YAML.readValue(yamlText, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "failed to parse YAML: " + e.getMessage(), e);
        }
        MondrianDef.Schema schema = buildSchema(root);
        return schema.toXML();
    }

    private static MondrianDef.Schema buildSchema(Map<?, ?> root) {
        MondrianDef.Schema schema = new MondrianDef.Schema();
        Object schemaNode = root.get("schema");
        if (schemaNode instanceof Map) {
            Map<?, ?> sm = (Map<?, ?>) schemaNode;
            schema.name = str(sm.get("name"));
            schema.metamodelVersion = str(sm.get("metamodel_version"));
        } else {
            schema.name = str(schemaNode);
        }
        List<MondrianDef.SchemaElement> children = new ArrayList<>();
        Object phys = root.get("physical_schema");
        if (phys instanceof Map) {
            children.add(buildPhysicalSchema((Map<?, ?>) phys));
        }
        schema.childArray =
            children.toArray(new MondrianDef.SchemaElement[0]);
        return schema;
    }

    private static MondrianDef.PhysicalSchema buildPhysicalSchema(
        Map<?, ?> phys)
    {
        MondrianDef.PhysicalSchema ps = new MondrianDef.PhysicalSchema();
        List<MondrianDef.PhysicalSchemaElement> kids = new ArrayList<>();
        Object tables = phys.get("tables");
        if (tables instanceof List) {
            for (Object t : (List<?>) tables) {
                if (t instanceof Map) {
                    kids.add(buildTable((Map<?, ?>) t));
                }
            }
        }
        ps.childArray =
            kids.toArray(new MondrianDef.PhysicalSchemaElement[0]);
        return ps;
    }

    private static MondrianDef.Table buildTable(Map<?, ?> t) {
        MondrianDef.Table table = new MondrianDef.Table();
        table.name = str(t.get("name"));
        table.alias = str(t.get("alias"));
        table.schema = str(t.get("schema"));
        table.keyColumn = str(t.get("key_column"));
        List<MondrianDef.TableElement> kids = new ArrayList<>();
        Object key = t.get("key");
        if (key instanceof List && !((List<?>) key).isEmpty()) {
            kids.add(buildKey((List<?>) key));
        }
        table.childArray =
            kids.toArray(new MondrianDef.TableElement[0]);
        return table;
    }

    private static MondrianDef.Key buildKey(List<?> columnNames) {
        MondrianDef.Key key = new MondrianDef.Key();
        List<MondrianDef.Column> cols = new ArrayList<>();
        for (Object c : columnNames) {
            cols.add(new MondrianDef.Column(null, str(c)));
        }
        key.array = cols.toArray(new MondrianDef.Column[0]);
        return key;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: PASS. If a `childArray` assignment fails to compile because `Table`'s array element interface differs, change the list type to the interface reported by the grep in "Note on XOM interface casts" and re-run.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/m4/M4YamlToXml.java \
        src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java
git commit -m "feat(#34): M4 emit — schema header + physical tables/keys"
```

---

## Task 3: M4 emit — links + foreign keys

**Files:**
- Modify: `src/main/java/mondrian/schema/yaml/m4/M4YamlToXml.java`
- Test: `src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java` (add method)

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void emitsPhysicalLinksWithForeignKey() {
        String yaml = PHYS_YAML
            + "  links:\n"
            + "    - {source: product_class, target: product,"
            + " foreign_key: [product_class_id]}\n";
        String xml = M4YamlToXml.toXml(yaml);
        assertTrue(xml, xml.contains("<Link"));
        assertTrue(xml, xml.contains("source=\"product_class\""));
        assertTrue(xml, xml.contains("target=\"product\""));
        assertTrue(xml, xml.contains("<ForeignKey"));
        assertTrue(xml, xml.contains("name=\"product_class_id\""));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest#emitsPhysicalLinksWithForeignKey`
Expected: FAIL — no `<Link>` in output (links not yet built).

- [ ] **Step 3: Write minimal implementation** — in `buildPhysicalSchema`, after the tables loop and before `ps.childArray = ...`, add:

```java
        Object links = phys.get("links");
        if (links instanceof List) {
            for (Object l : (List<?>) links) {
                if (l instanceof Map) {
                    kids.add(buildLink((Map<?, ?>) l));
                }
            }
        }
```

And add the helper:

```java
    private static MondrianDef.Link buildLink(Map<?, ?> l) {
        MondrianDef.Link link = new MondrianDef.Link();
        link.source = str(l.get("source"));
        link.target = str(l.get("target"));
        Object fk = l.get("foreign_key");
        if (fk instanceof List && !((List<?>) fk).isEmpty()) {
            MondrianDef.ForeignKey foreignKey = new MondrianDef.ForeignKey();
            List<MondrianDef.Column> cols = new ArrayList<>();
            for (Object c : (List<?>) fk) {
                cols.add(new MondrianDef.Column(null, str(c)));
            }
            foreignKey.array = cols.toArray(new MondrianDef.Column[0]);
            link.foreignKey = foreignKey;
        } else if (l.get("foreign_key_column") != null) {
            link.foreignKeyColumn = str(l.get("foreign_key_column"));
        }
        return link;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: PASS (all methods).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/m4/M4YamlToXml.java \
        src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java
git commit -m "feat(#34): M4 emit — physical links + foreign keys"
```

---

## Task 4: M4 emit — calculated columns with SQL-by-dialect

**Files:**
- Modify: `src/main/java/mondrian/schema/yaml/m4/M4YamlToXml.java`
- Test: `src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java` (add method)

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void emitsCalculatedColumnWithSqlDialects() {
        String yaml = PHYS_YAML
            + "    - name: customer\n"
            + "      key: [customer_id]\n"
            + "      calculated_columns:\n"
            + "        - name: full_name\n"
            + "          type: String\n"
            + "          expression:\n"
            + "            generic: \"{fullname}\"\n"
            + "            oracle: \"a || b\"\n";
        String xml = M4YamlToXml.toXml(yaml);
        assertTrue(xml, xml.contains("<ColumnDefs"));
        assertTrue(xml, xml.contains("<CalculatedColumnDef"));
        assertTrue(xml, xml.contains("name=\"full_name\""));
        assertTrue(xml, xml.contains("<ExpressionView"));
        assertTrue(xml, xml.contains("dialect=\"oracle\""));
        assertTrue(xml, xml.contains("dialect=\"generic\""));
    }
```

> **Verification before implementing:** confirm the exact field names of
> `MondrianDef.CalculatedColumnDef`, `MondrianDef.ExpressionView`, and
> `MondrianDef.SQL` with:
> `awk '/public static class (CalculatedColumnDef|ExpressionView|SQL|ColumnDefs) /{f=1} f{print} f&&/^\t}/{exit}' target/generated-sources/xom/mondrian/olap/MondrianDef.java | grep -E 'class |public .*;'`
> Use the reported field names (e.g. `SQL.dialect`, `SQL.cdata`/text content,
> `ExpressionView.childArray` of `SQL`) in the helper below; adjust the
> assignment lines to match.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest#emitsCalculatedColumnWithSqlDialects`
Expected: FAIL — no `<ColumnDefs>` in output.

- [ ] **Step 3: Write minimal implementation** — in `buildTable`, after the `key` block, add:

```java
        Object calcCols = t.get("calculated_columns");
        if (calcCols instanceof List && !((List<?>) calcCols).isEmpty()) {
            kids.add(buildColumnDefs((List<?>) calcCols));
        }
```

Add helpers (adjust `SQL` text-field assignment to the verified field name):

```java
    private static MondrianDef.ColumnDefs buildColumnDefs(List<?> defs) {
        MondrianDef.ColumnDefs columnDefs = new MondrianDef.ColumnDefs();
        List<MondrianDef.ColumnDef> list = new ArrayList<>();
        for (Object d : defs) {
            if (d instanceof Map) {
                list.add(buildCalculatedColumnDef((Map<?, ?>) d));
            }
        }
        columnDefs.array = list.toArray(new MondrianDef.ColumnDef[0]);
        return columnDefs;
    }

    private static MondrianDef.CalculatedColumnDef
        buildCalculatedColumnDef(Map<?, ?> d)
    {
        MondrianDef.CalculatedColumnDef ccd =
            new MondrianDef.CalculatedColumnDef();
        ccd.name = str(d.get("name"));
        ccd.type = str(d.get("type"));
        Object expr = d.get("expression");
        if (expr instanceof Map) {
            MondrianDef.ExpressionView view = new MondrianDef.ExpressionView();
            List<MondrianDef.SQL> sqls = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) expr).entrySet()) {
                MondrianDef.SQL sql = new MondrianDef.SQL();
                sql.dialect = str(e.getKey());
                sql.cdata = str(e.getValue());   // verify: SQL text field
                sqls.add(sql);
            }
            view.childArray = sqls.toArray(new MondrianDef.SQL[0]);
            ccd.expressionView = view;           // verify: child field name
        }
        return ccd;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/m4/M4YamlToXml.java \
        src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java
git commit -m "feat(#34): M4 emit — calculated columns with SQL dialects"
```

---

## Task 5: M4 ingest — schema header + physical tables/keys/links/columns

**Files:**
- Create: `src/main/java/mondrian/schema/yaml/m4/M4XmlToYaml.java`
- Test: `src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java` (add method)

- [ ] **Step 1: Write the failing test**

```java
    // ---- ingest (M4 XML -> YAML) ----

    private static final String PHYS_XML =
        "<Schema name='FoodMart' metamodelVersion='4.0'>"
        + "  <PhysicalSchema>"
        + "    <Table name='salary'/>"
        + "    <Table name='store'><Key><Column name='store_id'/></Key></Table>"
        + "    <Table name='product' keyColumn='product_id'/>"
        + "    <Link source='product_class' target='product'>"
        + "      <ForeignKey><Column name='product_class_id'/></ForeignKey>"
        + "    </Link>"
        + "  </PhysicalSchema>"
        + "</Schema>";

    @Test
    public void ingestsPhysicalSchemaToYaml() {
        String yaml = M4XmlToYaml.toYaml(PHYS_XML);
        assertTrue(yaml, yaml.contains("metamodel_version: \"4.0\"")
            || yaml.contains("metamodel_version: '4.0'"));
        assertTrue(yaml, yaml.contains("physical_schema:"));
        assertTrue(yaml, yaml.contains("name: \"salary\"")
            || yaml.contains("name: salary"));
        assertTrue(yaml, yaml.contains("store_id"));
        assertTrue(yaml, yaml.contains("links:"));
        assertTrue(yaml, yaml.contains("product_class_id"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest#ingestsPhysicalSchemaToYaml`
Expected: COMPILE FAILURE — `M4XmlToYaml` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml.m4;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import mondrian.olap.MondrianDef;

import org.eigenbase.xom.DOMWrapper;
import org.eigenbase.xom.Parser;
import org.eigenbase.xom.XOMUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #34 M4: Mondrian-4 XML -> YAML. Parses the XML into a typed
 * {@link MondrianDef.Schema} via XOM, walks the object graph, and emits
 * the equivalent YAML map (Jackson). Inverse of {@link M4YamlToXml}.
 *
 * <p>Phase 1 covers the schema header + {@code physical_schema}.
 */
public final class M4XmlToYaml {

    private static final ObjectMapper YAML;
    static {
        YAMLFactory f = new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        YAML = new ObjectMapper(f);
    }

    private M4XmlToYaml() {}

    public static String toYaml(String xmlText) {
        final MondrianDef.Schema schema;
        try {
            Parser parser = XOMUtil.createDefaultParser();
            DOMWrapper def = parser.parse(xmlText);
            schema = new MondrianDef.Schema(def);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "failed to parse M4 XML: " + e.getMessage(), e);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("name", schema.name);
        if (schema.metamodelVersion != null) {
            header.put("metamodel_version", schema.metamodelVersion);
        }
        root.put("schema", header);

        if (schema.childArray != null) {
            for (MondrianDef.SchemaElement el : schema.childArray) {
                if (el instanceof MondrianDef.PhysicalSchema) {
                    root.put("physical_schema",
                        physicalSchema((MondrianDef.PhysicalSchema) el));
                }
            }
        }
        try {
            return YAML.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "failed to serialize YAML: " + e.getMessage(), e);
        }
    }

    private static Map<String, Object> physicalSchema(
        MondrianDef.PhysicalSchema ps)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> tables = new ArrayList<>();
        List<Object> links = new ArrayList<>();
        if (ps.childArray != null) {
            for (Object child : ps.childArray) {
                if (child instanceof MondrianDef.Table) {
                    tables.add(table((MondrianDef.Table) child));
                } else if (child instanceof MondrianDef.Link) {
                    links.add(link((MondrianDef.Link) child));
                }
            }
        }
        if (!tables.isEmpty()) {
            out.put("tables", tables);
        }
        if (!links.isEmpty()) {
            out.put("links", links);
        }
        return out;
    }

    private static Map<String, Object> table(MondrianDef.Table t) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", t.name);
        if (t.alias != null) {
            out.put("alias", t.alias);
        }
        if (t.keyColumn != null) {
            out.put("key_column", t.keyColumn);
        }
        if (t.childArray != null) {
            for (Object child : t.childArray) {
                if (child instanceof MondrianDef.Key) {
                    out.put("key",
                        columnNames(((MondrianDef.Key) child).array));
                }
                // ColumnDefs handled in Task 6
            }
        }
        return out;
    }

    private static Map<String, Object> link(MondrianDef.Link l) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", l.source);
        out.put("target", l.target);
        if (l.foreignKey != null && l.foreignKey.array != null) {
            out.put("foreign_key", columnNames(l.foreignKey.array));
        } else if (l.foreignKeyColumn != null) {
            out.put("foreign_key_column", l.foreignKeyColumn);
        }
        return out;
    }

    private static List<String> columnNames(MondrianDef.Column[] cols) {
        List<String> names = new ArrayList<>();
        if (cols != null) {
            for (MondrianDef.Column c : cols) {
                names.add(c.name);
            }
        }
        return names;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/m4/M4XmlToYaml.java \
        src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java
git commit -m "feat(#34): M4 ingest — schema header + physical tables/keys/links"
```

---

## Task 6: M4 ingest — calculated columns

**Files:**
- Modify: `src/main/java/mondrian/schema/yaml/m4/M4XmlToYaml.java`
- Test: `src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java` (add method)

- [ ] **Step 1: Write the failing test**

```java
    @Test
    public void ingestsCalculatedColumns() {
        String xml =
            "<Schema name='FoodMart' metamodelVersion='4.0'>"
            + "<PhysicalSchema><Table name='customer'>"
            + "<Key><Column name='customer_id'/></Key>"
            + "<ColumnDefs><CalculatedColumnDef name='full_name' type='String'>"
            + "<ExpressionView>"
            + "<SQL dialect='generic'>x</SQL>"
            + "<SQL dialect='oracle'>y</SQL>"
            + "</ExpressionView></CalculatedColumnDef></ColumnDefs>"
            + "</Table></PhysicalSchema></Schema>";
        String yaml = M4XmlToYaml.toYaml(xml);
        assertTrue(yaml, yaml.contains("calculated_columns:"));
        assertTrue(yaml, yaml.contains("full_name"));
        assertTrue(yaml, yaml.contains("expression:"));
        assertTrue(yaml, yaml.contains("generic"));
        assertTrue(yaml, yaml.contains("oracle"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest#ingestsCalculatedColumns`
Expected: FAIL — `calculated_columns:` absent.

- [ ] **Step 3: Write minimal implementation** — in `table(...)`, replace the `// ColumnDefs handled in Task 6` comment with a handler, and add helpers (use the `SQL`/`ExpressionView` field names verified in Task 4):

```java
                else if (child instanceof MondrianDef.ColumnDefs) {
                    List<Object> ccs = calculatedColumns(
                        (MondrianDef.ColumnDefs) child);
                    if (!ccs.isEmpty()) {
                        out.put("calculated_columns", ccs);
                    }
                }
```

```java
    private static List<Object> calculatedColumns(
        MondrianDef.ColumnDefs defs)
    {
        List<Object> out = new ArrayList<>();
        if (defs.array != null) {
            for (Object d : defs.array) {
                if (d instanceof MondrianDef.CalculatedColumnDef) {
                    out.add(calculatedColumn(
                        (MondrianDef.CalculatedColumnDef) d));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> calculatedColumn(
        MondrianDef.CalculatedColumnDef d)
    {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", d.name);
        if (d.type != null) {
            out.put("type", d.type);
        }
        if (d.expressionView != null
            && d.expressionView.childArray != null)
        {
            Map<String, Object> expr = new LinkedHashMap<>();
            for (Object s : d.expressionView.childArray) {
                if (s instanceof MondrianDef.SQL) {
                    MondrianDef.SQL sql = (MondrianDef.SQL) s;
                    expr.put(sql.dialect, sql.cdata);
                }
            }
            out.put("expression", expr);
        }
        return out;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/m4/M4XmlToYaml.java \
        src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java
git commit -m "feat(#34): M4 ingest — calculated columns"
```

---

## Task 7: Wire dispatch in the public converters

**Files:**
- Modify: `src/main/java/mondrian/schema/yaml/XmlSchemaToYaml.java` (`toYaml`, line ~72)
- Modify: `src/main/java/mondrian/schema/yaml/YamlSchemaConverter.java` (`toXml`, line ~193)
- Test: `src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java` (add methods)

- [ ] **Step 1: Write the failing test**

```java
    // ---- dispatch through public API ----

    @Test
    public void publicToYamlDispatchesToM4ForM4Xml() {
        String yaml = mondrian.schema.yaml.XmlSchemaToYaml.toYaml(PHYS_XML);
        assertTrue(yaml, yaml.contains("physical_schema:"));
    }

    @Test
    public void publicToXmlDispatchesToM4ForM4Yaml() {
        String xml =
            mondrian.schema.yaml.YamlSchemaConverter.toXml(PHYS_YAML);
        assertTrue(xml, xml.contains("<PhysicalSchema"));
        assertTrue(xml, xml.contains("metamodelVersion=\"4.0\""));
    }

    @Test
    public void publicToYamlStillHandlesM3Scalar() {
        // M3 schema with no PhysicalSchema / MeasureGroups stays on M3 path
        String m3 = "<Schema name='S'><Dimension name='D'>"
            + "<Hierarchy hasAll='true'><Table name='t'/>"
            + "<Level name='L' column='c'/></Hierarchy></Dimension></Schema>";
        String yaml = mondrian.schema.yaml.XmlSchemaToYaml.toYaml(m3);
        assertTrue(yaml, yaml.contains("schema: \"S\"")
            || yaml.contains("schema: S"));
        assertFalse(yaml, yaml.contains("physical_schema:"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: FAIL — `publicToYamlDispatchesToM4ForM4Xml` (M3 path emits near-empty YAML, no `physical_schema:`).

- [ ] **Step 3: Write minimal implementation**

In `XmlSchemaToYaml.toYaml`, right after the root-element check (after line 79, before building the M3 `root` map), insert:

```java
        if (mondrian.schema.yaml.m4.M4Detection.isM4Xml(schema)) {
            return mondrian.schema.yaml.m4.M4XmlToYaml.toYaml(xmlText);
        }
```

In `YamlSchemaConverter.toXml(String yamlText)` (line 193), after the YAML is parsed into the root `Map` and before `emitFromRoot(root)` is called, insert (adapt the variable name to the actual parsed-map variable in that method):

```java
        if (mondrian.schema.yaml.m4.M4Detection.isM4Yaml(root)) {
            return mondrian.schema.yaml.m4.M4YamlToXml.toXml(yamlText);
        }
```

> If `toXml` does not already parse into a `Map` named `root` before
> `emitFromRoot`, add `Map<?,?> root = YAML_PARSER.readValue(yamlText,
> Map.class);` using the same parser the method already uses, guard with
> `isM4Yaml`, then fall through to the existing M3 logic.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -o test -q -Dtest=M4PhysicalLayerTest`
Expected: PASS (all methods).

- [ ] **Step 5: Run the full existing YAML suite (M3 regression guard)**

Run: `mvn -o test -q -Dtest='mondrian.schema.yaml.*'`
Expected: PASS — all existing `FoodMart3*` / `YamlSchema*` / `SchemaCli` tests still green.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/mondrian/schema/yaml/XmlSchemaToYaml.java \
        src/main/java/mondrian/schema/yaml/YamlSchemaConverter.java \
        src/test/java/mondrian/schema/yaml/m4/M4PhysicalLayerTest.java
git commit -m "feat(#34): dispatch YAML converters between M3 and M4 vocab"
```

---

## Task 8: Physical-layer round-trip equivalence on the FoodMart dataset

**Files:**
- Create: `src/test/java/mondrian/schema/yaml/m4/M4PhysicalRoundtripTest.java`

This proves the Phase 1 physical layer is *semantically* lossless: take a
minimal M4 schema that uses a calculated column (`full_name`), round-trip it
XML→YAML→XML, load both into Mondrian against the real FoodMart dataset, run an
MDX query that reads `full_name`, and assert byte-identical cells. Pattern
mirrors `FoodMart3MdxEquivalenceTest`.

- [ ] **Step 1: Write the test** (this is the failing test for the phase)

```java
/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml.m4;

import mondrian.olap.Connection;
import mondrian.olap.Query;
import mondrian.olap.Result;
import mondrian.test.TestContext;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * #34 M4 Phase 1 acceptance: the physical layer round-trips losslessly.
 * Uses the real {@code demo/FoodMart.mondrian.xml} as the source, runs a
 * query that exercises the customer {@code full_name} calculated column,
 * and asserts the YAML round-trip yields identical cells.
 */
public class M4PhysicalRoundtripTest {

    private static final String MDX =
        "SELECT {[Measures].[Unit Sales]} ON COLUMNS, "
        + "{[Customer].[Customers].[USA].[CA]} ON ROWS "
        + "FROM [Sales]";

    @Test
    public void foodMartM4PhysicalLayerRoundTripsIdentically()
        throws Exception
    {
        Path fixture = Paths.get("demo/FoodMart.mondrian.xml");
        assertTrue("fixture missing: " + fixture.toAbsolutePath(),
            Files.exists(fixture));
        String originalXml =
            Files.readString(fixture, StandardCharsets.UTF_8);

        String yaml = mondrian.schema.yaml.XmlSchemaToYaml.toYaml(originalXml);
        String roundTripped =
            mondrian.schema.yaml.YamlSchemaConverter.toXml(yaml);

        // Sanity: the physical layer survived (full_name expression present)
        assertTrue("round-tripped XML lost full_name",
            roundTripped.contains("full_name"));

        String[] original = runMdx(originalXml);
        String[] roundtrip = runMdx(roundTripped);
        assertArrayEquals(original, roundtrip);
    }

    private static String[] runMdx(String schemaXml) {
        TestContext ctx = TestContext.instance().withSchema(schemaXml);
        Connection conn = ctx.getConnection();
        Query q = conn.parseQuery(MDX);
        Result result = conn.execute(q);
        int rows = result.getAxes().length >= 2
            ? result.getAxes()[1].getPositions().size() : 1;
        int cols = result.getAxes().length >= 1
            ? result.getAxes()[0].getPositions().size() : 1;
        String[] cells = new String[rows * cols];
        int[] coord = new int[2];
        int idx = 0;
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                coord[0] = c;
                coord[1] = r;
                cells[idx++] = result.getCell(coord).getFormattedValue();
            }
        }
        return cells;
    }
}
```

- [ ] **Step 2: Run it — expect failure that pinpoints the next gap**

Run: `mvn -o test -q -Dtest=M4PhysicalRoundtripTest`
Expected: FAIL. At Phase 1 the round-tripped XML only contains the physical
layer (no dimensions/cubes yet), so loading will fail or the schema won't
resolve `[Sales]`. **This test is the Phase 2+ driver** — it stays red until
dimensions and measure groups are implemented. Mark it `@org.junit.Ignore("M4
dimensions/measure-groups land in Phase 2-3")` for now and remove the `@Ignore`
in the Phase 3 capstone.

- [ ] **Step 3: Add the `@Ignore` annotation** (with the reason above), so the
build stays green while the physical layer is complete and the higher layers are
pending.

- [ ] **Step 4: Run the targeted + suite checks**

Run: `mvn -o test -q -Dtest='mondrian.schema.yaml.*'`
Expected: PASS (the ignored test is skipped; everything else green).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/mondrian/schema/yaml/m4/M4PhysicalRoundtripTest.java
git commit -m "test(#34): M4 physical round-trip equivalence (ignored until Phase 3)"
```

---

## Phase 1 Definition of Done

- `mvn -o test -q -Dtest='mondrian.schema.yaml.*'` is green.
- `./scripts/mondrian-schema to-yaml demo/FoodMart.mondrian.xml` now emits a
  populated `physical_schema:` block (no longer the 56-line near-empty output).
- M3 schemas are unaffected (existing `FoodMart3*` tests pass).

---

## Roadmap: Phases 2–5 (expand into bite-sized plans after Phase 1)

Each phase follows the same TDD rhythm (failing unit test in `m4/`, build
ingest+emit symmetrically, then an MDX-equivalence phase test). Phase 1 pins the
YAML key vocabulary and the MondrianDef-build/walk patterns these reuse.

**Phase 2 — Dimensions & attributes.** Files: `m4/M4Dimensions.java` (shared by
ingest+emit) or methods on the two converters. MondrianDef types:
`Dimension`, `Dimensions`, `Attributes`, `Attribute` (`Key`/`Name`/`OrderBy`
expressions, `Property` refs, `hasHierarchy`, `hierarchyAllMemberName`),
`Hierarchies`, `Hierarchy` (`allMemberName`, `hasAll`), `Level` (`attribute`
ref). YAML keys: `shared_dimensions`, `attributes`, `hierarchies`, `levels`,
`properties`, `has_hierarchy`, `key`/`key_column`, `name_column`. Phase test:
Store (multi-hierarchy) + Customer dimension MDX equivalence.

**Phase 3 — Cubes & measure groups (capstone-enabling).** MondrianDef types:
`Cube`, `Dimensions` (usages via `source` + cube-local), `MeasureGroups`,
`MeasureGroup` (`type`), `Measures`, `Measure` (`aggregator`, `formatString`,
`datatype`), `MeasureRef` (`aggColumn`), `DimensionLinks`, `ForeignKeyLink`,
`CopyLink`, `NoLink`, fact link, `AggTable`/`AggName`. YAML keys: `cubes`,
`dimensions`, `measure_groups`, `measures`, `ref`/`agg_column`,
`dimension_links` (`type: foreign_key|copy|no_link|fact`). Phase test: full Sales
cube MDX equivalence; **remove `@Ignore` from `M4PhysicalRoundtripTest`** — it
should now pass.

**Phase 4 — Calculated members, named sets, roles, annotations.** MondrianDef
types: `CalculatedMembers`, `CalculatedMember`, `Formula`,
`CalculatedMemberProperty`, `NamedSets`, `NamedSet`, roles/grants
(`schema_grant`/`cube_grant`/`hierarchy_grant`/`member_grant` — reuse the
existing M3 grant YAML shape), `Annotations`. Phase test: Profit calc-member +
California-manager role-restricted query equivalence.

**Phase 5 — FoodMart capstone.** Steps:
1. `./scripts/mondrian-schema to-yaml demo/FoodMart.mondrian.xml -o demo/FoodMart.yaml`; commit `demo/FoodMart.yaml`.
2. Create `src/test/java/mondrian/schema/yaml/m4/FoodMartYamlSmokeCorpusEquivalenceTest.java`: load the committed `demo/FoodMart.yaml` (raw YAML string passed to `TestContext.withSchema`, exercising first-class YAML catalog detection) and `demo/FoodMart.mondrian.xml`; run `SmokeCorpus.queries()` against both; assert 0 divergent queries (reuse the skip-if-XML-baseline-fails logic from `FoodMart3SmokeCorpusEquivalenceTest`).
3. Add a drift guard: regenerate YAML from the XML in-test and assert it equals the committed `demo/FoodMart.yaml`.
4. Extend `SchemaCli`/`scripts/mondrian-schema` docs and the #34 user guide to mention M4 support. Verify `./scripts/mondrian-schema lint demo/FoodMart.yaml` exits 0.

---

## Self-review notes

- **Spec coverage:** dispatch (Task 1, 7), MondrianDef pivot (Tasks 2–6),
  M3-kept-intact (Task 7 step 5), physical layer (Tasks 2–8), dims/measure
  groups/roles/calc (roadmap Phases 2–4), committed FoodMart.yaml + SmokeCorpus
  + drift guard (roadmap Phase 5). All spec sections map to a task or roadmap
  item.
- **Verification gates:** Tasks 4 and 6 call out the exact `grep`/`awk` to
  confirm `SQL`/`ExpressionView`/`CalculatedColumnDef` field names before
  writing code, because those few field names were not separately verified
  during planning.
- **Type consistency:** helper names (`buildTable`/`table`, `buildKey`,
  `columnNames`, `physicalSchema`) are used consistently across ingest/emit.
