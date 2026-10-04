package org.easyssf.examples.scim;

import java.util.Collection;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Shows the mirrored directory.
 */
@RestController
class UsersController {

    private final UserDirectory directory;

    UsersController(UserDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/users")
    Collection<User> users() {
        return this.directory.all();
    }

    @GetMapping("/users/{id}")
    ResponseEntity<User> user(@PathVariable String id) {
        User user = this.directory.find(id);
        return (user != null) ? ResponseEntity.ok(user) : ResponseEntity.notFound().build();
    }

}
