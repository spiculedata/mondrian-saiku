/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2026 Spicule
// All Rights Reserved.
*/
package mondrian.schema.yaml;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * #34 Phase 1 Session 4: reverse of {@link YamlSchemaConverter} — given
 * a Mondrian XML schema string, emit the equivalent YAML the forward
 * converter would consume.
 *
 * <p>This is the migration tool. Existing customer/upstream XML
 * schemas can be lifted into YAML without hand-editing. Combined with
 * a roundtrip test (XML → YAML → XML, compare MDX cell sets) it also
 * doubles as the verification harness for the forward converter.
 *
 * <p>Element coverage mirrors the forward converter: Schema, Cube,
 * Table (with AggExclude/AggName + 5 children), shared/cube-scoped
 * Dimension, DimensionUsage, single+multi Hierarchy, Join, Level (+
 * Properties), Measure, CalculatedMember, NamedSet, Annotations,
 * Role + all 4 nested grant types.
 *
 * <p>Attribute naming flips from camelCase ({@code foreignKey}) back
 * to snake_case ({@code foreign_key}) so the round-tripped YAML
 * remains idiomatic.
 */
public final class XmlSchemaToYaml {

    /** Single mapper instance: omit `---` doc marker, lit-block strings
     *  for multi-line text (preserves Formula whitespace). */
    private static final ObjectMapper YAML;
    static {
        YAMLFactory f = new YAMLFactory()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        YAML = new ObjectMapper(f);
    }

    private XmlSchemaToYaml() {}

    /**
     * Parse the given XML schema string and return the equivalent YAML
     * text. The output is suitable for round-tripping through
     * {@link YamlSchemaConverter#toXml(String)}.
     *
     * @throws IllegalArgumentException if the XML is malformed or the
     *         root element is not {@code <Schema>}.
     */
    public static String toYaml(String xmlText) {
        Document doc = parseDocument(xmlText);
        Element schema = doc.getDocumentElement();
        if (schema == null || !"Schema".equals(schema.getTagName())) {
            throw new IllegalArgumentException(
                "expected root element <Schema>, got: "
                    + (schema == null ? "(none)" : schema.getTagName()));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", schema.getAttribute("name"));
        Map<String, String> ann = collectAnnotations(schema);
        if (ann != null) {
            root.put("annotations", ann);
        }
        List<Map<String, Object>> schemaNs = collectNamedSets(schema);
        if (!schemaNs.isEmpty()) {
            root.put("named_sets", schemaNs);
        }
        Map<String, Map<String, Object>> sharedDims =
            collectSharedDimensions(schema);
        if (!sharedDims.isEmpty()) {
            root.put("shared_dimensions", sharedDims);
        }
        Map<String, Map<String, Object>> cubes = collectCubes(schema);
        if (!cubes.isEmpty()) {
            root.put("cubes", cubes);
        }
        List<Map<String, Object>> roles = collectRoles(schema);
        if (!roles.isEmpty()) {
            root.put("roles", roles);
        }
        try {
            return YAML.writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "failed to serialize YAML: " + e.getMessage(), e);
        }
    }

    private static Document parseDocument(String xmlText) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Disable external entities / DTDs — defensive, schemas
            // should be self-contained anyway.
            dbf.setFeature(
                "http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setNamespaceAware(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            return db.parse(new InputSource(new StringReader(xmlText)));
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "failed to parse XML: " + e.getMessage(), e);
        }
    }

    // ---------- top-level collectors ----------

    private static Map<String, Map<String, Object>> collectSharedDimensions(
        Element schema)
    {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Element d : directChildren(schema, "Dimension")) {
            String name = d.getAttribute("name");
            Map<String, Object> body = new LinkedHashMap<>();
            putAttrIfPresent(body, d, "type", "type");
            Map<String, String> dann = collectAnnotations(d);
            if (dann != null) {
                body.put("annotations", dann);
            }
            putHierarchies(body, d);
            out.put(name, body);
        }
        return out;
    }

    private static Map<String, Map<String, Object>> collectCubes(
        Element schema)
    {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Element c : directChildren(schema, "Cube")) {
            String name = c.getAttribute("name");
            Map<String, Object> body = new LinkedHashMap<>();
            putAttrIfPresent(body, c, "defaultMeasure", "default_measure");
            Map<String, String> cann = collectAnnotations(c);
            if (cann != null) {
                body.put("annotations", cann);
            }
            // Fact <Table> — bare-string shape if no agg children, else
            // map { name, agg_exclude, agg_names }.
            Element table = firstChild(c, "Table");
            if (table != null) {
                List<Element> aggExcludes =
                    directChildren(table, "AggExclude");
                List<Element> aggNames = directChildren(table, "AggName");
                if (aggExcludes.isEmpty() && aggNames.isEmpty()) {
                    body.put("fact_table", table.getAttribute("name"));
                } else {
                    Map<String, Object> ft = new LinkedHashMap<>();
                    ft.put("name", table.getAttribute("name"));
                    if (!aggExcludes.isEmpty()) {
                        List<String> ex = new ArrayList<>();
                        for (Element x : aggExcludes) {
                            ex.add(x.getAttribute("name"));
                        }
                        ft.put("agg_exclude", ex);
                    }
                    if (!aggNames.isEmpty()) {
                        List<Map<String, Object>> an = new ArrayList<>();
                        for (Element a : aggNames) {
                            an.add(toAggName(a));
                        }
                        ft.put("agg_names", an);
                    }
                    body.put("fact_table", ft);
                }
            }
            List<Map<String, Object>> dus = new ArrayList<>();
            for (Element du : directChildren(c, "DimensionUsage")) {
                Map<String, Object> m = new LinkedHashMap<>();
                putAttrIfPresent(m, du, "name", "name");
                putAttrIfPresent(m, du, "source", "source");
                putAttrIfPresent(m, du, "foreignKey", "foreign_key");
                putAttrIfPresent(m, du, "level", "level");
                putAttrIfPresent(m, du, "usagePrefix", "usage_prefix");
                dus.add(m);
            }
            if (!dus.isEmpty()) {
                body.put("dimension_usages", dus);
            }
            List<Map<String, Object>> dims = new ArrayList<>();
            for (Element d : directChildren(c, "Dimension")) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", d.getAttribute("name"));
                putAttrIfPresent(m, d, "foreignKey", "foreign_key");
                putAttrIfPresent(m, d, "type", "type");
                putHierarchies(m, d);
                dims.add(m);
            }
            if (!dims.isEmpty()) {
                body.put("dimensions", dims);
            }
            List<Map<String, Object>> measures = new ArrayList<>();
            for (Element m : directChildren(c, "Measure")) {
                Map<String, Object> mm = new LinkedHashMap<>();
                putAttrIfPresent(mm, m, "name", "name");
                putAttrIfPresent(mm, m, "column", "column");
                putAttrIfPresent(mm, m, "aggregator", "aggregator");
                putAttrIfPresent(mm, m, "formatString", "format_string");
                putAttrIfPresent(mm, m, "datatype", "datatype");
                putAttrIfPresent(mm, m, "visible", "visible");
                putSqlDialectList(mm, m, "MeasureExpression",
                    "measure_expression");
                measures.add(mm);
            }
            if (!measures.isEmpty()) {
                body.put("measures", measures);
            }
            List<Map<String, Object>> cms = new ArrayList<>();
            for (Element cm : directChildren(c, "CalculatedMember")) {
                cms.add(toCalculatedMember(cm));
            }
            if (!cms.isEmpty()) {
                body.put("calculated_members", cms);
            }
            List<Map<String, Object>> ns = collectNamedSets(c);
            if (!ns.isEmpty()) {
                body.put("named_sets", ns);
            }
            out.put(name, body);
        }
        return out;
    }

    private static List<Map<String, Object>> collectRoles(Element schema) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element r : directChildren(schema, "Role")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", r.getAttribute("name"));
            Element sg = firstChild(r, "SchemaGrant");
            if (sg != null) {
                m.put("schema_grant", toSchemaGrant(sg));
            }
            out.add(m);
        }
        return out;
    }

    // ---------- element converters ----------

    private static Map<String, Object> toAggName(Element a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.getAttribute("name"));
        Element fc = firstChild(a, "AggFactCount");
        if (fc != null) {
            m.put("fact_count_column", fc.getAttribute("column"));
        }
        List<String> ignores = new ArrayList<>();
        for (Element x : directChildren(a, "AggIgnoreColumn")) {
            ignores.add(x.getAttribute("column"));
        }
        if (!ignores.isEmpty()) {
            m.put("ignore_columns", ignores);
        }
        List<Map<String, Object>> fks = new ArrayList<>();
        for (Element f : directChildren(a, "AggForeignKey")) {
            Map<String, Object> fk = new LinkedHashMap<>();
            fk.put("fact_column", f.getAttribute("factColumn"));
            fk.put("agg_column", f.getAttribute("aggColumn"));
            fks.add(fk);
        }
        if (!fks.isEmpty()) {
            m.put("foreign_keys", fks);
        }
        List<Map<String, Object>> measures = new ArrayList<>();
        for (Element mm : directChildren(a, "AggMeasure")) {
            Map<String, Object> mp = new LinkedHashMap<>();
            mp.put("name", mm.getAttribute("name"));
            mp.put("column", mm.getAttribute("column"));
            measures.add(mp);
        }
        if (!measures.isEmpty()) {
            m.put("measures", measures);
        }
        List<Map<String, Object>> levels = new ArrayList<>();
        for (Element l : directChildren(a, "AggLevel")) {
            Map<String, Object> lp = new LinkedHashMap<>();
            lp.put("name", l.getAttribute("name"));
            lp.put("column", l.getAttribute("column"));
            levels.add(lp);
        }
        if (!levels.isEmpty()) {
            m.put("levels", levels);
        }
        return m;
    }

    /**
     * Populate {@code hierarchy:} (single map) when the dimension has
     * exactly one hierarchy and that hierarchy has no {@code name}
     * attribute, otherwise emit a {@code hierarchies:} list.
     */
    private static void putHierarchies(Map<String, Object> body, Element dim) {
        List<Element> hs = directChildren(dim, "Hierarchy");
        if (hs.isEmpty()) {
            return;
        }
        if (hs.size() == 1 && hs.get(0).getAttribute("name").isEmpty()) {
            body.put("hierarchy", toHierarchy(hs.get(0)));
            return;
        }
        List<Map<String, Object>> arr = new ArrayList<>();
        for (Element h : hs) {
            arr.add(toHierarchy(h));
        }
        body.put("hierarchies", arr);
    }

    private static Map<String, Object> toHierarchy(Element h) {
        Map<String, Object> m = new LinkedHashMap<>();
        putAttrIfPresent(m, h, "name", "name");
        putAttrIfPresent(m, h, "hasAll", "has_all");
        putAttrIfPresent(m, h, "primaryKey", "primary_key");
        putAttrIfPresent(m, h, "primaryKeyTable", "primary_key_table");
        putAttrIfPresent(m, h, "allMemberName", "all_member_name");
        putAttrIfPresent(m, h, "defaultMember", "default_member");
        Element join = firstChild(h, "Join");
        Element table = firstChild(h, "Table");
        if (join != null) {
            m.put("join", toJoin(join));
        } else if (table != null) {
            m.put("table", table.getAttribute("name"));
        }
        List<Map<String, Object>> levels = new ArrayList<>();
        for (Element l : directChildren(h, "Level")) {
            levels.add(toLevel(l));
        }
        if (!levels.isEmpty()) {
            m.put("levels", levels);
        }
        return m;
    }

    private static Map<String, Object> toJoin(Element j) {
        Map<String, Object> m = new LinkedHashMap<>();
        putAttrIfPresent(m, j, "leftAlias", "left_alias");
        putAttrIfPresent(m, j, "leftKey", "left_key");
        putAttrIfPresent(m, j, "rightAlias", "right_alias");
        putAttrIfPresent(m, j, "rightKey", "right_key");
        List<Object> tables = new ArrayList<>();
        for (Element t : directChildren(j, "Table")) {
            // Bare string if only name attr; map otherwise so we don't
            // lose schema/alias.
            if (hasOnlyAttrs(t, "name")) {
                tables.add(t.getAttribute("name"));
            } else {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("name", t.getAttribute("name"));
                putAttrIfPresent(tm, t, "schema", "schema");
                putAttrIfPresent(tm, t, "alias", "alias");
                tables.add(tm);
            }
        }
        m.put("tables", tables);
        return m;
    }

    private static Map<String, Object> toLevel(Element l) {
        Map<String, Object> m = new LinkedHashMap<>();
        putAttrIfPresent(m, l, "name", "name");
        putAttrIfPresent(m, l, "table", "table");
        putAttrIfPresent(m, l, "column", "column");
        putAttrIfPresent(m, l, "type", "type");
        putAttrIfPresent(m, l, "uniqueMembers", "unique_members");
        putAttrIfPresent(m, l, "levelType", "level_type");
        putAttrIfPresent(m, l, "approxRowCount", "approx_row_count");
        putSqlDialectList(m, l, "KeyExpression", "key_expression");
        putSqlDialectList(m, l, "NameExpression", "name_expression");
        putSqlDialectList(m, l, "CaptionExpression", "caption_expression");
        putSqlDialectList(m, l, "OrdinalExpression", "ordinal_expression");
        List<Map<String, Object>> props = new ArrayList<>();
        for (Element p : directChildren(l, "Property")) {
            Map<String, Object> pm = new LinkedHashMap<>();
            putAttrIfPresent(pm, p, "name", "name");
            putAttrIfPresent(pm, p, "column", "column");
            putAttrIfPresent(pm, p, "type", "type");
            props.add(pm);
        }
        if (!props.isEmpty()) {
            m.put("properties", props);
        }
        return m;
    }

    /**
     * Lift a {@code <KeyExpression>}/{@code <MeasureExpression>}/etc.
     * wrapper into a YAML list of {@code {dialect, text}} maps. Skips
     * silently when the wrapper isn't present.
     *
     * <p>SQL body capture is "full text content" — any inline
     * {@code <Column .../>} child elements get serialized back as
     * literal XML markup inside the captured text (DOM
     * {@link Node#getNodeValue()} on the text nodes only captures
     * text, so we reconstruct the full original element body via a
     * fresh DOM walk).
     */
    private static void putSqlDialectList(
        Map<String, Object> out, Element parent,
        String wrapperTag, String yamlKey)
    {
        Element wrapper = firstChild(parent, wrapperTag);
        if (wrapper == null) {
            return;
        }
        List<Map<String, Object>> blocks = new ArrayList<>();
        for (Element sql : directChildren(wrapper, "SQL")) {
            Map<String, Object> b = new LinkedHashMap<>();
            String dialect = sql.getAttribute("dialect");
            b.put("dialect", dialect.isEmpty() ? "generic" : dialect);
            b.put("text", elementBodyAsText(sql));
            blocks.add(b);
        }
        if (!blocks.isEmpty()) {
            out.put(yamlKey, blocks);
        }
    }

    /**
     * Serialise an element's body (children + text) back into a
     * string. Used to preserve inline {@code <Column .../>} refs
     * inside {@code <SQL>} dialect blocks as literal XML markup —
     * the user's YAML round-trip then contains the same markup the
     * original XML had.
     */
    private static String elementBodyAsText(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            switch (n.getNodeType()) {
            case Node.TEXT_NODE:
            case Node.CDATA_SECTION_NODE:
                sb.append(n.getNodeValue());
                break;
            case Node.ELEMENT_NODE:
                Element child = (Element) n;
                sb.append('<').append(child.getTagName());
                org.w3c.dom.NamedNodeMap attrs = child.getAttributes();
                for (int a = 0; a < attrs.getLength(); a++) {
                    Node att = attrs.item(a);
                    sb.append(' ').append(att.getNodeName())
                      .append("=\"").append(att.getNodeValue())
                      .append('"');
                }
                if (!child.hasChildNodes()) {
                    sb.append("/>");
                } else {
                    sb.append('>')
                      .append(elementBodyAsText(child))
                      .append("</").append(child.getTagName()).append('>');
                }
                break;
            default:
                // skip comments / processing instructions
            }
        }
        return sb.toString().trim();
    }

    private static Map<String, Object> toCalculatedMember(Element cm) {
        Map<String, Object> m = new LinkedHashMap<>();
        putAttrIfPresent(m, cm, "name", "name");
        putAttrIfPresent(m, cm, "dimension", "dimension");
        putAttrIfPresent(m, cm, "caption", "caption");
        putAttrIfPresent(m, cm, "visible", "visible");
        putAttrIfPresent(m, cm, "formula", "formula");
        Element f = firstChild(cm, "Formula");
        if (f != null) {
            m.put("formula_body", textOf(f));
        }
        List<Element> props = directChildren(cm, "CalculatedMemberProperty");
        if (!props.isEmpty()) {
            Map<String, Object> pmap = new LinkedHashMap<>();
            for (Element p : props) {
                pmap.put(p.getAttribute("name"), p.getAttribute("value"));
            }
            m.put("properties", pmap);
        }
        return m;
    }

    private static List<Map<String, Object>> collectNamedSets(Element scope) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element ns : directChildren(scope, "NamedSet")) {
            Map<String, Object> m = new LinkedHashMap<>();
            putAttrIfPresent(m, ns, "name", "name");
            putAttrIfPresent(m, ns, "caption", "caption");
            putAttrIfPresent(m, ns, "formula", "formula");
            Element f = firstChild(ns, "Formula");
            if (f != null) {
                m.put("formula_body", textOf(f));
            }
            out.add(m);
        }
        return out;
    }

    private static Map<String, String> collectAnnotations(Element scope) {
        Element ae = firstChild(scope, "Annotations");
        if (ae == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Element a : directChildren(ae, "Annotation")) {
            out.put(a.getAttribute("name"), textOf(a));
        }
        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> toSchemaGrant(Element sg) {
        Map<String, Object> m = new LinkedHashMap<>();
        putAttrIfPresent(m, sg, "access", "access");
        List<Map<String, Object>> cubes = new ArrayList<>();
        for (Element c : directChildren(sg, "CubeGrant")) {
            cubes.add(toCubeGrant(c));
        }
        if (!cubes.isEmpty()) {
            m.put("cubes", cubes);
        }
        return m;
    }

    private static Map<String, Object> toCubeGrant(Element cg) {
        Map<String, Object> m = new LinkedHashMap<>();
        putAttrIfPresent(m, cg, "cube", "cube");
        putAttrIfPresent(m, cg, "access", "access");
        List<Map<String, Object>> hs = new ArrayList<>();
        for (Element h : directChildren(cg, "HierarchyGrant")) {
            hs.add(toHierarchyGrant(h));
        }
        if (!hs.isEmpty()) {
            m.put("hierarchies", hs);
        }
        return m;
    }

    private static Map<String, Object> toHierarchyGrant(Element hg) {
        Map<String, Object> m = new LinkedHashMap<>();
        putAttrIfPresent(m, hg, "hierarchy", "hierarchy");
        putAttrIfPresent(m, hg, "access", "access");
        putAttrIfPresent(m, hg, "topLevel", "top_level");
        putAttrIfPresent(m, hg, "bottomLevel", "bottom_level");
        putAttrIfPresent(m, hg, "rollupPolicy", "rollup_policy");
        List<Map<String, Object>> ms = new ArrayList<>();
        for (Element mg : directChildren(hg, "MemberGrant")) {
            Map<String, Object> mm = new LinkedHashMap<>();
            putAttrIfPresent(mm, mg, "member", "member");
            putAttrIfPresent(mm, mg, "access", "access");
            ms.add(mm);
        }
        if (!ms.isEmpty()) {
            m.put("members", ms);
        }
        return m;
    }

    // ---------- DOM helpers ----------

    private static List<Element> directChildren(Element parent, String tag) {
        List<Element> out = new ArrayList<>();
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE
                && tag.equals(((Element) n).getTagName()))
            {
                out.add((Element) n);
            }
        }
        return out;
    }

    private static Element firstChild(Element parent, String tag) {
        List<Element> all = directChildren(parent, tag);
        return all.isEmpty() ? null : all.get(0);
    }

    private static String textOf(Element e) {
        StringBuilder sb = new StringBuilder();
        NodeList kids = e.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node n = kids.item(i);
            if (n.getNodeType() == Node.TEXT_NODE
                || n.getNodeType() == Node.CDATA_SECTION_NODE)
            {
                sb.append(n.getNodeValue());
            }
        }
        return sb.toString().trim();
    }

    /**
     * Append {@code yamlKey: value} iff the XML element carries the
     * given attribute (non-empty). XML's idiomatic empty-string for
     * "attribute absent" is the trigger.
     */
    private static void putAttrIfPresent(
        Map<String, Object> m, Element e, String xmlAttr, String yamlKey)
    {
        if (!e.hasAttribute(xmlAttr)) {
            return;
        }
        String v = e.getAttribute(xmlAttr);
        if (v.isEmpty()) {
            return;
        }
        m.put(yamlKey, v);
    }

    /** True iff the element's attribute set is exactly the given names. */
    private static boolean hasOnlyAttrs(Element e, String... allowed) {
        int n = e.getAttributes().getLength();
        if (n != allowed.length) {
            return false;
        }
        for (String a : allowed) {
            if (!e.hasAttribute(a)) {
                return false;
            }
        }
        return true;
    }
}

// End XmlSchemaToYaml.java
