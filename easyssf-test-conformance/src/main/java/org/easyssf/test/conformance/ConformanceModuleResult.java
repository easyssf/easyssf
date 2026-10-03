package org.easyssf.test.conformance;

import java.net.URI;
import java.util.List;

import org.easyssf.test.conformance.ConformanceApiClient.Plan;
import org.easyssf.test.conformance.ConformanceApiClient.PlanModule;

import tools.jackson.databind.JsonNode;

/**
 * The outcome of a module run as the suite reports it.
 */
public record ConformanceModuleResult(Plan plan, PlanModule module, String moduleId, String status, String result,
        JsonNode logs, URI url) {

    public boolean finishedWith(String expectedResult) {
        return "FINISHED".equals(this.status) && expectedResult.equals(this.result);
    }

    /**
     * The failures and warnings the suite logged.
     */
    public List<String> problems() {
        if (this.logs == null || !this.logs.isArray()) {
            return List.of();
        }
        return this.logs.valueStream()
            .filter((entry) -> "FAILURE".equals(entry.path("result").asString())
                    || "WARNING".equals(entry.path("result").asString()))
            .map(ConformanceModuleResult::format)
            .toList();
    }

    public String summary() {
        StringBuilder summary = new StringBuilder("Conformance module ").append(this.module)
            .append(" of plan ")
            .append(this.plan.name())
            .append(' ')
            .append(this.plan.variant())
            .append(": status ")
            .append(this.status)
            .append(", result ")
            .append(this.result)
            .append(" (")
            .append(this.url)
            .append(')');
        List<String> problems = problems();
        if (problems.isEmpty()) {
            summary.append("\n  no failures or warnings logged");
        }
        problems.stream().limit(20).forEach((problem) -> summary.append("\n  ").append(problem));
        return summary.toString();
    }

    private static String format(JsonNode entry) {
        String source = entry.path("src").asString(entry.path("condition").asString(""));
        String message = entry.path("msg").asString(entry.path("message").asString(entry.path("error").asString("")));
        StringBuilder formatted = new StringBuilder(entry.path("result").asString()).append(' ')
            .append(source)
            .append(": ")
            .append(message);
        for (String detail : List.of("url", "match", "detail", "requirements")) {
            if (entry.hasNonNull(detail)) {
                formatted.append(' ').append(detail).append('=').append(entry.get(detail));
            }
        }
        return formatted.toString();
    }

}
