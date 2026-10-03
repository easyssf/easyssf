package org.easyssf.receiver.spring.boot.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.easyssf.receiver.push.SsfPushHandler;
import org.easyssf.receiver.push.SsfPushResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Spring MVC endpoint that receives SETs delivered using HTTP push (RFC 8935) and hands
 * them to the {@link SsfPushHandler}.
 */
public class SsfPushEndpoint {

    private final SsfPushHandler pushHandler;

    public SsfPushEndpoint(SsfPushHandler pushHandler) {
        Assert.notNull(pushHandler, "pushHandler must not be null");
        this.pushHandler = pushHandler;
    }

    public ServerResponse handle(ServerRequest request) throws IOException {
        byte[] body;
        try (InputStream input = request.servletRequest().getInputStream()) {
            body = input.readNBytes(SsfPushHandler.MAX_SET_SIZE + 1);
        }
        SsfPushResponse response = this.pushHandler.handle(request.headers().firstHeader(HttpHeaders.AUTHORIZATION),
                body);
        if (response.body() == null) {
            return ServerResponse.status(response.status()).build();
        }
        byte[] content = response.body().getBytes(StandardCharsets.UTF_8);
        return ServerResponse.status(response.status()).build((servletRequest, servletResponse) -> {
            servletResponse.setContentType(SsfPushResponse.CONTENT_TYPE);
            servletResponse.setHeader(HttpHeaders.CONTENT_LANGUAGE, SsfPushResponse.CONTENT_LANGUAGE);
            servletResponse.setContentLength(content.length);
            servletResponse.getOutputStream().write(content);
            return null;
        });
    }

}
