package org.easyssf.conformance.receiver;

import org.easyssf.test.conformance.receiver.ConformancePlan;

class Ssf10PushReceiverConformanceTest extends SpringReceiverConformanceTest {

    @Override
    protected ConformancePlan plan() {
        return ConformancePlan.SSF_1_0_PUSH;
    }

}
