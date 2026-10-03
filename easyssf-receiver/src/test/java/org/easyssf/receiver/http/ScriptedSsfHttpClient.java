package org.easyssf.receiver.http;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * An {@link SsfHttpClient} for tests that answers with scripted responses and records the
 * requests.
 */
public class ScriptedSsfHttpClient implements SsfHttpClient {

    private final Deque<SsfHttpResponse> responses = new ArrayDeque<>();

    private final List<SsfHttpRequest> requests = new ArrayList<>();

    public ScriptedSsfHttpClient respond(int status, Map<String, List<String>> headers, String body) {
        this.responses.add(new SsfHttpResponse(status, headers, body));
        return this;
    }

    public ScriptedSsfHttpClient respond(int status, String body) {
        return respond(status, Map.of(), body);
    }

    public List<SsfHttpRequest> requests() {
        return this.requests;
    }

    @Override
    public SsfHttpResponse execute(SsfHttpRequest request) {
        this.requests.add(request);
        SsfHttpResponse response = this.responses.poll();
        if (response == null) {
            throw new IllegalStateException("No scripted response left for " + request.method() + " " + request.uri());
        }
        return response;
    }

}
