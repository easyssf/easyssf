package org.easyssf.receiver.http;

import java.io.IOException;

/**
 * Sends HTTP requests to the transmitter. All calls of the receiver go through this
 * interface, implement it to use the HTTP client of your framework.
 *
 * @see JdkSsfHttpClient
 */
@FunctionalInterface
public interface SsfHttpClient {

    /**
     * Sends the request and returns the response, whatever its status.
     * @throws IOException if no response was received
     */
    SsfHttpResponse execute(SsfHttpRequest request) throws IOException;

}
