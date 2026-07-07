package com.positivity.bulkloader.internal.parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import org.jspecify.annotations.NonNull;

/**
 * Discovers the record list inside a generic object tree (maps, lists, scalars — as produced by
 * JSON/YAML/XML parsing) and flattens each record into string key/value pairs. Nested objects are
 * flattened with dot-separated paths; lists of scalars are joined with {@code "; "}.
 */
final class StructuredRecords {

    private StructuredRecords() {}

    static RecordSource toRecordSource(Object tree, @NonNull String fileName) {
        List<Map<?, ?>> recordNodes = findRecordList(tree);
        if (recordNodes.isEmpty()) {
            throw new RecordFileParseException("Could not locate a list of records in structured file: " + fileName);
        }
        List<Map<String, String>> records = new ArrayList<>(recordNodes.size());
        Set<String> headers = new LinkedHashSet<>();
        for (Map<?, ?> node : recordNodes) {
            Map<String, String> flattened = new LinkedHashMap<>();
            flatten("", node, flattened);
            headers.addAll(flattened.keySet());
            records.add(flattened);
        }
        return new ListBackedRecordSource(new ArrayList<>(headers), records);
    }

    /**
     * Locates the list of records: the root itself when it is a list of maps, otherwise the largest
     * list-of-maps found anywhere in the tree (breadth-first). A root map with no such list is
     * treated as a single record.
     */
    private static List<Map<?, ?>> findRecordList(Object root) {
        List<Map<?, ?>> best = List.of();
        Deque<Object> queue = new ArrayDeque<>();
        if (root != null) {
            queue.add(root);
        }
        while (!queue.isEmpty()) {
            Object node = queue.poll();
            if (node instanceof List<?> list) {
                List<Map<?, ?>> maps = asListOfMaps(list);
                if (maps.size() > best.size()) {
                    best = maps;
                }
                if (maps.isEmpty()) {
                    queue.addAll(
                            list.stream().filter(java.util.Objects::nonNull).toList());
                }
            } else if (node instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value != null) {
                        queue.add(value);
                    }
                }
            }
        }
        if (best.isEmpty() && root instanceof Map<?, ?> single) {
            return List.of(single);
        }
        return best;
    }

    private static List<Map<?, ?>> asListOfMaps(List<?> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<Map<?, ?>> maps = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> map)) {
                return List.of();
            }
            maps.add(map);
        }
        return maps;
    }

    private static void flatten(String prefix, Map<?, ?> node, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : node.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            switch (value) {
                case null -> target.put(key, "");
                case Map<?, ?> nested -> flatten(key, nested, target);
                case List<?> list -> target.put(key, joinScalars(list));
                default -> target.put(key, String.valueOf(value).trim());
            }
        }
    }

    private static String joinScalars(List<?> list) {
        StringJoiner joiner = new StringJoiner("; ");
        for (Object element : list) {
            if (element != null && !(element instanceof Map) && !(element instanceof List)) {
                joiner.add(String.valueOf(element).trim());
            }
        }
        return joiner.toString();
    }
}
