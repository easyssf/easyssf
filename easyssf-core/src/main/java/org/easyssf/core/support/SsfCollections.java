package org.easyssf.core.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unmodifiable copies for the value objects of the protocol. Unlike {@link Map#copyOf},
 * they keep {@code null} values and the order of the entries, as JSON has both.
 */
public final class SsfCollections {

    private SsfCollections() {
    }

    /**
     * @return an unmodifiable copy of the map, {@code null} for {@code null}; maps and
     * lists among the values are copied the same way, so that nothing reachable from the
     * copy can be changed
     */
    @SuppressWarnings("unchecked")
    public static <K, V> Map<K, V> copyOf(Map<K, V> map) {
        if (map == null) {
            return null;
        }
        Map<K, V> copy = new LinkedHashMap<>(map.size());
        for (Map.Entry<K, V> entry : map.entrySet()) {
            copy.put(entry.getKey(), (V) copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * @return an unmodifiable copy of the list, {@code null} for {@code null}; maps and
     * lists among the elements are copied the same way
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> copyOf(List<T> list) {
        if (list == null) {
            return null;
        }
        List<T> copy = new ArrayList<>(list.size());
        for (T element : list) {
            copy.add((T) copyOf(element));
        }
        return Collections.unmodifiableList(copy);
    }

    /**
     * @return the value, with a map or a list (as JSON has them) replaced by an
     * unmodifiable deep copy
     */
    public static Object copyOf(Object value) {
        if (value instanceof Map<?, ?> map) {
            return copyOf(map);
        }
        if (value instanceof List<?> list) {
            return copyOf(list);
        }
        return value;
    }

}
