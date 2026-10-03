package org.easyssf.core.event;

/**
 * Which convention for the subject of a SET a peer follows. SSF 1.0 requires a top-level
 * {@code sub_id} claim in every SET, including those of the CAEP and RISC event types
 * defined before it; earlier drafts put the subject into the event payload instead. A
 * receiver validates by it, a transmitter would emit by it.
 */
public enum SubjectCompatibilityMode {

    /**
     * SSF 1.0: a SET without a top-level {@code sub_id} is rejected as
     * {@code invalid_request}. The default.
     */
    STRICT_SSF_1_0,

    /**
     * The top-level {@code sub_id} is optional, for transmitters that follow earlier
     * drafts; {@code SsfEventContext.subjectFor} then reads the {@code subject} member of
     * the event payload.
     */
    LEGACY

}
