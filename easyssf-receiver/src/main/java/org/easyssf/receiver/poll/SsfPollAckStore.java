package org.easyssf.receiver.poll;

import java.util.Collection;
import java.util.List;

/**
 * Keeps the acknowledgements and error reports the poller owes a transmitter until a poll
 * request has carried them (RFC 8936, section 2.4: they ride on the next request). The
 * {@link InMemorySsfPollAckStore} loses them when the application stops, so a SET handled
 * but not yet acknowledged is delivered again after a restart and skipped as a duplicate.
 * A durable implementation, such as the JDBC store of {@code easyssf-receiver-jdbc},
 * acknowledges it with the first poll after the restart instead.
 *
 * <p>
 * Entries are kept per transmitter, identified by its issuer, because a {@code jti} is
 * only unique per issuer and a store may serve several transmitters. Implementations have
 * to be safe for concurrent use.
 */
public interface SsfPollAckStore {

    /**
     * Records an acknowledgement or error report. Recording the same {@code jti} again
     * does nothing.
     * @param issuer the issuer of the transmitter the SET came from
     * @param ack what to tell the transmitter
     */
    void record(String issuer, SsfPendingAck ack);

    /**
     * The acknowledgements and error reports waiting for the transmitter, oldest first.
     * @param issuer the issuer of the transmitter
     * @param limit the maximum number to return
     */
    List<SsfPendingAck> pending(String issuer, int limit);

    /**
     * Removes entries the transmitter has received.
     * @param issuer the issuer of the transmitter
     * @param jtis the identifiers of the SETs whose entries were delivered
     */
    void remove(String issuer, Collection<String> jtis);

    /**
     * @return the number of entries waiting for the transmitter
     */
    int size(String issuer);

}
