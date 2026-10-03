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
     * @return an unmodifiable copy of the map, {@code null} for {@code null}
     */
    public static <K, V> Map<K, V> copyOf(Map<K, V> map) {
        return (map != null) ? Collections.unmodifiableMap(new LinkedHashMap<>(map)) : null;
    }

    /**
     * @return an unmodifiable copy of the list, {@code null} for {@code null}
     */
    public static <T> List<T> copyOf(List<T> list) {
        return (list != null) ? Collections.unmodifiableList(new ArrayList<>(list)) : null;
    }

}
