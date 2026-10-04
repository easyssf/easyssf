package org.easyssf.examples.scim;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The API of the demo SCIM service provider, a tiny stand-in for the {@code /Users}
 * endpoint of a SCIM server: each request changes a user and transmits the SCIM Event
 * about it, which the receiver of this application mirrors into {@code /users} on its
 * next poll. Every response is the SET transmitted, so that the event is visible next to
 * its cause. See {@code example-scim-provisioning.http}.
 */
@RestController
@RequestMapping("/demo/scim")
@Profile("demo")
class DemoScimProviderController {

    private static final Logger logger = LoggerFactory.getLogger(DemoScimProviderController.class);

    private final DemoScimProvider provider;

    DemoScimProviderController(DemoScimProvider provider) {
        this.provider = provider;
    }

    /**
     * Creates a user from its SCIM representation: {@code feed:add} and
     * {@code prov:create:full}. The {@code externalId} of the representation, if any,
     * goes into the subject of the events.
     */
    @PostMapping("/Users")
    ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> user) {
        String uri = DemoScimProvider.newUserUri();
        String externalId = (user.get("externalId") instanceof String value) ? value : null;
        narrate("POST /demo/scim/Users: " + user.getOrDefault("userName", uri) + " is created and joins the feed");
        Map<String, Object> set = this.provider.create(uri, externalId, user);
        return ResponseEntity.created(URI.create("/demo/scim" + uri)).body(set);
    }

    /**
     * Replaces a user: {@code prov:put:full}.
     */
    @PutMapping("/Users/{id}")
    Map<String, Object> replace(@PathVariable String id, @RequestBody Map<String, Object> user) {
        narrate("PUT /demo/scim/Users/" + id + ": the user is replaced");
        return this.provider.replace("/Users/" + id, user);
    }

    /**
     * Modifies a user with a SCIM {@code PatchOp}: {@code prov:patch:full}, or with
     * {@code ?mode=notice} a {@code prov:patch:notice} that only names the paths of the
     * operations.
     */
    @PatchMapping("/Users/{id}")
    Map<String, Object> patch(@PathVariable String id, @RequestParam(defaultValue = "full") String mode,
            @RequestBody Map<String, Object> patchOp) {
        String uri = "/Users/" + id;
        if ("notice".equals(mode)) {
            narrate("PATCH /demo/scim/Users/" + id + "?mode=notice: the user is modified, reported without data");
            return this.provider.patchNotice(uri, paths(patchOp));
        }
        narrate("PATCH /demo/scim/Users/" + id + ": the user is modified");
        return this.provider.patch(uri, patchOp);
    }

    @PostMapping("/Users/{id}/activate")
    Map<String, Object> activate(@PathVariable String id) {
        narrate("POST /demo/scim/Users/" + id + "/activate: the user is activated");
        return this.provider.activate("/Users/" + id);
    }

    @PostMapping("/Users/{id}/deactivate")
    Map<String, Object> deactivate(@PathVariable String id) {
        narrate("POST /demo/scim/Users/" + id + "/deactivate: the user is deactivated");
        return this.provider.deactivate("/Users/" + id);
    }

    /**
     * Deletes a user: {@code prov:delete}.
     */
    @DeleteMapping("/Users/{id}")
    Map<String, Object> delete(@PathVariable String id) {
        narrate("DELETE /demo/scim/Users/" + id + ": the user is deleted");
        return this.provider.delete("/Users/" + id);
    }

    /**
     * The SETs transmitted so far, oldest first, each as the compact JWT and its decoded
     * claims.
     */
    @GetMapping("/events")
    List<Map<String, Object>> events() {
        return this.provider.transmitted();
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> notReady(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", ex.getMessage()));
    }

    private static void narrate(String whatHappens) {
        // the line break leaves an empty line in the log before the headline
        logger.info("\n### {}", whatHappens);
    }

    private static List<String> paths(Map<String, Object> patchOp) {
        List<String> paths = new ArrayList<>();
        if (patchOp.get("Operations") instanceof List<?> operations) {
            for (Object operation : operations) {
                if (operation instanceof Map<?, ?> map) {
                    if (map.get("path") instanceof String path) {
                        paths.add(path);
                    }
                    else if (map.get("value") instanceof Map<?, ?> attributes) {
                        attributes.keySet().forEach((attribute) -> paths.add(String.valueOf(attribute)));
                    }
                }
            }
        }
        return paths;
    }

}
