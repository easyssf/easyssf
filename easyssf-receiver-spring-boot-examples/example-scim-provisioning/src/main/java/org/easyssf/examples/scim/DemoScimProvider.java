package org.easyssf.examples.scim;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.easyssf.core.event.SsfEventTypes;
import org.easyssf.core.event.SsfSubjectIdentifiers;
import org.easyssf.test.TestTransmitter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.nimbusds.jwt.JWTClaimsSet;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Plays the SCIM service provider: every operation on a user becomes a SCIM Event (RFC
 * 9967) that the demo transmitter delivers to the receiver of this very application. The
 * operations are driven by {@link DemoScimProviderController} ({@code /demo/scim}) and,
 * unless {@code demo.autoplay=false}, by {@link #play()}, which tells the life of two
 * users once the receiver has registered its stream, one SET every few seconds. Only with
 * the {@code demo} profile, which {@link ExampleScimProvisioningApplication#main}
 * activates.
 *
 * <p>
 * The SETs transmitted are kept, decoded, for {@code GET /demo/scim/events}: that is what
 * a SCIM Event looks like on the wire.
 */
@Component
@Profile("demo")
class DemoScimProvider {

    private static final Logger logger = LoggerFactory.getLogger(DemoScimProvider.class);

    private static final Duration PAUSE = Duration.ofSeconds(4);

    static final String ALICE = "/Users/2b2f880af6674ac284bae9381673d462";

    static final String BOB = "/Users/c3a6e1f0b2d94f0e9a7c5d8b6e4f2a10";

    private final TestTransmitter transmitter;

    private final JsonMapper json;

    private final boolean autoplay;

    private final Map<String, Integer> versions = new ConcurrentHashMap<>();

    private final Map<String, String> externalIds = new ConcurrentHashMap<>();

    private final List<Map<String, Object>> transmitted = new CopyOnWriteArrayList<>();

    DemoScimProvider(TestTransmitter transmitter, JsonMapper json, @Value("${demo.autoplay:true}") boolean autoplay) {
        this.transmitter = transmitter;
        this.json = json;
        this.autoplay = autoplay;
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() {
        if (this.autoplay) {
            Thread.ofVirtual().name("demo-scim-provider").start(this::play);
        }
        else {
            logger.info("The demo SCIM service provider waits for requests to /demo/scim, demo.autoplay is off");
        }
    }

    /**
     * The life of two users: created, modified, replaced, deactivated, deleted. The
     * resources and the patch are written as JSON here so that the SCIM payloads are easy
     * to read; an application would build them from its own objects.
     */
    void play() {
        try {
            while (this.transmitter.streams().isEmpty()) {
                Thread.sleep(200);
            }
            logger.info("The receiver registered its stream, the demo SCIM service provider starts");
            narrate("Alice is created at the service provider and joins the feed: feed:add and prov:create:full "
                    + "in one SET, with her representation as data");
            create(ALICE, "alice", json("""
                    {
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                      "userName": "alice",
                      "displayName": "Alice Adams",
                      "emails": [{"type": "work", "value": "alice@example.com", "primary": true}],
                      "active": true
                    }
                    """));
            Thread.sleep(PAUSE);
            narrate("Bob is created as well");
            create(BOB, "bob", json("""
                    {
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                      "userName": "bob",
                      "displayName": "Bob Brown",
                      "emails": [{"type": "work", "value": "bob@example.com", "primary": true}],
                      "active": true
                    }
                    """));
            Thread.sleep(PAUSE);
            // alice marries: a SCIM PATCH with two operations
            narrate("Alice marries: a SCIM PATCH changes her display name and adds an email, prov:patch:full "
                    + "carries the PatchOp as data");
            patch(ALICE, json("""
                    {
                      "schemas": ["urn:ietf:params:scim:api:messages:2.0:PatchOp"],
                      "Operations": [
                        {"op": "replace", "path": "displayName", "value": "Alice Baker"},
                        {"op": "add", "path": "emails", "value": [
                          {"type": "work", "value": "alice@example.com"},
                          {"type": "home", "value": "alice.baker@home.example"}
                        ]}
                      ]
                    }
                    """));
            Thread.sleep(PAUSE);
            // bob is replaced wholesale: a SCIM PUT
            narrate("Bob is replaced wholesale with a SCIM PUT: prov:put:full carries the new representation");
            replace(BOB, json("""
                    {
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"],
                      "userName": "robert",
                      "displayName": "Robert Brown",
                      "emails": [{"type": "work", "value": "robert@example.com", "primary": true}],
                      "active": true
                    }
                    """));
            Thread.sleep(PAUSE);
            // a notice event: only the attributes that changed, no data
            narrate("Alice's phone number changes, reported as a notice: prov:patch:notice names the attribute "
                    + "but carries no data, the receiver would have to GET the resource");
            patchNotice(ALICE, List.of("phoneNumbers"));
            Thread.sleep(PAUSE);
            narrate("Alice leaves the company and is deactivated: prov:deactivate, an event without payload");
            deactivate(ALICE);
            Thread.sleep(PAUSE);
            narrate("Bob is deleted: prov:delete, which also removes him from the feed");
            delete(BOB);
            logger.info("The demo SCIM service provider is done, GET http://localhost:8083/users shows the result "
                    + "once the receiver has polled, GET /demo/scim/events the SETs");
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Creates the user: {@code feed:add} and {@code prov:create:full} in one SET, as RFC
     * 9967 allows for events about the same resource.
     * @return the SET transmitted, decoded
     */
    Map<String, Object> create(String uri, String externalId, Map<String, Object> user) {
        if (externalId != null) {
            this.externalIds.put(uri, externalId);
        }
        Map<String, Object> events = new LinkedHashMap<>();
        events.put(SsfEventTypes.SCIM_FEED_ADD, Map.of());
        events.put(SsfEventTypes.SCIM_PROV_CREATE_FULL, Map.of("version", nextVersion(uri), "data", user));
        return transmit(uri, events);
    }

    /**
     * Replaces the user with SCIM PUT: {@code prov:put:full} with the new representation.
     */
    Map<String, Object> replace(String uri, Map<String, Object> user) {
        return transmit(uri,
                Map.of(SsfEventTypes.SCIM_PROV_PUT_FULL, Map.of("version", nextVersion(uri), "data", user)));
    }

    /**
     * Modifies the user with SCIM PATCH: {@code prov:patch:full} with the
     * {@code PatchOp}.
     */
    Map<String, Object> patch(String uri, Map<String, Object> patchOp) {
        return transmit(uri,
                Map.of(SsfEventTypes.SCIM_PROV_PATCH_FULL, Map.of("version", nextVersion(uri), "data", patchOp)));
    }

    /**
     * Reports a modification without its data: {@code prov:patch:notice} naming the
     * attributes that changed.
     */
    Map<String, Object> patchNotice(String uri, List<String> attributes) {
        return transmit(uri, Map.of(SsfEventTypes.SCIM_PROV_PATCH_NOTICE,
                Map.of("version", nextVersion(uri), "attributes", attributes)));
    }

    Map<String, Object> activate(String uri) {
        return transmit(uri, Map.of(SsfEventTypes.SCIM_PROV_ACTIVATE, Map.of()));
    }

    Map<String, Object> deactivate(String uri) {
        return transmit(uri, Map.of(SsfEventTypes.SCIM_PROV_DEACTIVATE, Map.of()));
    }

    /**
     * Deletes the user: {@code prov:delete}, which has no payload attributes and removes
     * the resource from the feed as well.
     */
    Map<String, Object> delete(String uri) {
        Map<String, Object> set = transmit(uri, Map.of(SsfEventTypes.SCIM_PROV_DELETE, Map.of()));
        this.versions.remove(uri);
        this.externalIds.remove(uri);
        return set;
    }

    /**
     * The SETs transmitted so far, oldest first: the compact JWT as {@code set} and its
     * decoded {@code claims}.
     */
    List<Map<String, Object>> transmitted() {
        return List.copyOf(this.transmitted);
    }

    private Map<String, Object> transmit(String uri, Map<String, Object> events) {
        List<Map<String, Object>> streams = this.transmitter.streams();
        if (streams.isEmpty()) {
            throw new IllegalStateException("The receiver has not registered its stream yet, try again in a moment");
        }
        // the receiver expects the audience the transmitter assigned to the stream
        @SuppressWarnings("unchecked")
        List<String> audience = (List<String>) streams.get(0).get("aud");
        String eventType = events.keySet().iterator().next();
        JWTClaimsSet claims = this.transmitter
            .setClaims(eventType, SsfSubjectIdentifiers.scim(uri, this.externalIds.get(uri)), Map.of())
            .claim("events", events)
            .audience(audience)
            .claim("txn", UUID.randomUUID().toString().replace("-", ""))
            .build();
        String set = this.transmitter.signSet(claims);
        this.transmitter.queueSet(set);
        Map<String, Object> transmittedSet = new LinkedHashMap<>();
        transmittedSet.put("set", set);
        transmittedSet.put("claims", claims.toJSONObject());
        this.transmitted.add(transmittedSet);
        // the decoded SET in the log: this is what a SCIM Event looks like
        logger.info("Transmitted SET {} with {} for {}:\n{}", claims.getJWTID(),
                events.keySet().stream().map(SsfEventTypes::aliasOf).toList(), uri,
                this.json.writerWithDefaultPrettyPrinter().writeValueAsString(claims.toJSONObject()));
        return transmittedSet;
    }

    /**
     * A headline in the log for the step that follows, to scroll along in a demo.
     */
    private static void narrate(String whatHappens) {
        // the line break leaves an empty line in the log before the headline
        logger.info("\n### {}", whatHappens);
    }

    private String nextVersion(String uri) {
        return String.valueOf(this.versions.merge(uri, 1, Integer::sum));
    }

    static String newUserUri() {
        return "/Users/" + UUID.randomUUID().toString().replace("-", "");
    }

    private Map<String, Object> json(String text) {
        return this.json.readValue(text, new TypeReference<Map<String, Object>>() {
        });
    }

}
