package org.easyssf.examples.scim;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

import org.springframework.stereotype.Component;

/**
 * The local directory the SCIM Events are mirrored into, in memory. An application would
 * keep its own user table here.
 */
@Component
public class UserDirectory {

    private final Map<String, User> users = new ConcurrentHashMap<>();

    public void save(User user) {
        this.users.put(user.id(), user);
    }

    public User find(String id) {
        return this.users.get(id);
    }

    /**
     * Changes the user, if it is in the directory.
     * @return the changed user, {@code null} if the directory has no such user
     */
    public User update(String id, UnaryOperator<User> change) {
        return this.users.computeIfPresent(id, (key, user) -> change.apply(user));
    }

    public User remove(String id) {
        return this.users.remove(id);
    }

    public Collection<User> all() {
        return List.copyOf(this.users.values());
    }

}
