package org.easyssf.conformance.receiver;

import org.easyssf.test.conformance.receiver.ConformancePlan;

class Ssf10PollReceiverConformanceTest extends SpringReceiverConformanceTest {

    @Override
    protected ConformancePlan plan() {
        return ConformancePlan.SSF_1_0_POLL;
    }

}
