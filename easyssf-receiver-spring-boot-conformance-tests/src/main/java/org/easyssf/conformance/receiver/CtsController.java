package org.easyssf.conformance.receiver;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.easyssf.core.SsfDeliveryMethod;
import org.easyssf.receiver.push.SsfPushResponse;
import org.easyssf.test.conformance.receiver.ConformanceRun;
import org.easyssf.test.conformance.receiver.ConformanceRunner;
import org.easyssf.test.conformance.receiver.ConformanceScenario;
import org.easyssf.test.conformance.receiver.ConformanceSuiteModules;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Starts test runs and receives the SETs the suite pushes.
 */
@RestController
public class CtsController {

    private final ConformanceRunner runner;

    private final ConformanceSuiteModules suiteModules;

    CtsController(ConformanceRunner runner, ConformanceSuiteModules suiteModules) {
        this.runner = runner;
        this.suiteModules = suiteModules;
    }

    public record RunView(String id, String scenario, String issuer, SsfDeliveryMethod delivery,
            ConformanceRun.Status status, String streamId, Instant startedAt, List<ConformanceRun.LogEntry> log,
            List<ConformanceRun.ReceivedSet> receivedSets) {

        static RunView of(ConformanceRun run) {
            return new RunView(run.getId(), run.getScenario().alias(), run.getIssuer(), run.getDeliveryMethod(),
                    run.getStatus(), run.streamId(), run.getStartedAt(), run.getLog(), run.getReceivedSets());
        }

    }

    /**
     * A run started for the module waiting in the suite.
     */
    public record ModuleRunView(ConformanceSuiteModules.Module module, RunView run) {
    }

    @GetMapping(path = "/", produces = MediaType.TEXT_PLAIN_VALUE)
    String usage() {
        return """
                easyssf receiver under test for the OpenID conformance suite

                POST /cts/runs/auto                  start the run the test module waiting in the suite expects
                     ?issuer=<issuer>                optional: a test instance of the suite, default cts.transmitter.issuer
                GET  /cts/suite/modules              the test modules running in the suite and the scenario each gets
                POST /cts/runs?scenario=<scenario>   start a run (stops the current one), scenarios: %s
                     &issuer=<issuer>                optional: the test instance of the suite, default cts.transmitter.issuer
                     &delivery=push|poll             optional: default cts.delivery.method
                GET  /cts/runs/current               what the current run did so far
                POST /cts/runs/current/stop          stop the current run and delete its stream
                POST /ssf/push                       push endpoint for the suite
                """
            .formatted(java.util.Arrays.stream(ConformanceScenario.values()).map(ConformanceScenario::alias).toList());
    }

    @PostMapping("/cts/runs")
    ResponseEntity<RunView> start(@RequestParam(defaultValue = "caep-interop") String scenario,
            @RequestParam(required = false) String issuer, @RequestParam(required = false) String delivery) {
        ConformanceScenario conformanceScenario;
        SsfDeliveryMethod deliveryMethod;
        try {
            conformanceScenario = ConformanceScenario.fromAlias(scenario);
            deliveryMethod = (delivery != null) ? SsfDeliveryMethod.valueOf(delivery.toUpperCase(Locale.ROOT)) : null;
        }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        return ResponseEntity.accepted()
            .body(RunView.of(this.runner.start(conformanceScenario, issuer, deliveryMethod)));
    }

    /**
     * Starts the run for the test module that waits for the receiver in the suite: the
     * scenario the module expects, against the module's test instance and with the
     * delivery method of its variant.
     */
    @PostMapping("/cts/runs/auto")
    ResponseEntity<ModuleRunView> startForWaitingModule(@RequestParam(required = false) String issuer) {
        ConformanceSuiteModules.TestInstance instance;
        List<ConformanceSuiteModules.Module> waiting;
        try {
            instance = this.suiteModules.testInstance(issuer);
            waiting = this.suiteModules.waiting(instance);
        }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
        if (waiting.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No test module is waiting for the receiver in the"
                    + " conformance suite at " + instance.suite() + "; start one there first");
        }
        if (waiting.size() > 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Several test modules are waiting for the receiver: "
                            + waiting.stream().map((module) -> module.name() + " (" + module.issuer() + ")").toList()
                            + "; pick one with ?issuer=<issuer>");
        }
        ConformanceSuiteModules.Module module = waiting.get(0);
        ConformanceRun run = this.runner.start(module.scenario(), module.issuer(), module.deliveryMethod());
        return ResponseEntity.accepted().body(new ModuleRunView(module, RunView.of(run)));
    }

    @GetMapping("/cts/suite/modules")
    List<ConformanceSuiteModules.Module> suiteModules(@RequestParam(required = false) String issuer) {
        try {
            return this.suiteModules.running(this.suiteModules.testInstance(issuer));
        }
        catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
        catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, ex.getMessage());
        }
    }

    @GetMapping("/cts/runs/current")
    RunView current() {
        return RunView.of(currentRun());
    }

    @PostMapping("/cts/runs/current/stop")
    RunView stop() {
        ConformanceRun run = currentRun();
        run.stop();
        return RunView.of(run);
    }

    @PostMapping("/ssf/push")
    ResponseEntity<String> push(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) byte[] body) {
        ConformanceRun run = this.runner.current().filter(ConformanceRun::isActive).orElse(null);
        if (run == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_LANGUAGE, SsfPushResponse.CONTENT_LANGUAGE)
                .body("{\"err\":\"invalid_request\",\"description\":\"No conformance run is active\"}");
        }
        SsfPushResponse response = run.push(authorization, (body != null) ? body : new byte[0]);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(response.status());
        if (response.body() == null) {
            return builder.build();
        }
        return builder.contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_LANGUAGE, SsfPushResponse.CONTENT_LANGUAGE)
            .body(response.body());
    }

    private ConformanceRun currentRun() {
        return this.runner.current()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No run was started yet"));
    }

}
