package org.easyssf.conformance.receiver;

import org.easyssf.test.conformance.receiver.ConformancePlan;

class CaepInterop10PollReceiverConformanceTest extends SpringReceiverConformanceTest {

    @Override
    protected ConformancePlan plan() {
        return ConformancePlan.CAEP_INTEROP_1_0_POLL;
    }

}
