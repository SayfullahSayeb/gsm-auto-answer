package com.gsmautoanswer.app.compat

import com.gsmautoanswer.app.data.LiveTestResult
import org.junit.Assert.assertEquals
import org.junit.Test

class CompatVerdictsTest {
    private fun classify(
        policyKnown: Boolean = true,
        frameworkRoute: Boolean = true,
        vendorEvidence: Boolean = false,
        vendorNegative: Boolean = false,
        liveResult: LiveTestResult = LiveTestResult.NOT_RUN,
        routeVerified: Boolean = false,
    ) = CompatVerdicts.classify(
        policyKnown,
        frameworkRoute,
        vendorEvidence,
        vendorNegative,
        liveResult,
        routeVerified,
    )

    @Test
    fun passedLiveTestWins() {
        assertEquals(CompatVerdict.VERIFIED, classify(liveResult = LiveTestResult.PASSED))
    }

    @Test
    fun missingFrameworkRouteFailsEvenAfterFailedTest() {
        assertEquals(
            CompatVerdict.NOT_SUPPORTED,
            classify(
                frameworkRoute = false,
                liveResult = LiveTestResult.FAILED,
                routeVerified = true,
            ),
        )
    }

    @Test
    fun failedTestWithVerifiedRouteMeansModemDroppedAudio() {
        assertEquals(
            CompatVerdict.TEST_FAILED,
            classify(liveResult = LiveTestResult.FAILED, routeVerified = true),
        )
    }

    @Test
    fun failedTestWithoutRouteIsInconclusive() {
        assertEquals(
            CompatVerdict.TEST_INCONCLUSIVE,
            classify(liveResult = LiveTestResult.FAILED, routeVerified = false),
        )
    }

    @Test
    fun unreadablePolicyIsUnknown() {
        assertEquals(CompatVerdict.UNKNOWN, classify(policyKnown = false))
    }

    @Test
    fun vendorEvidenceIsLikelySupported() {
        assertEquals(
            CompatVerdict.LIKELY_SUPPORTED,
            classify(vendorEvidence = true),
        )
    }

    @Test
    fun frameworkRouteWithoutVendorPathIsFrameworkOnly() {
        assertEquals(
            CompatVerdict.FRAMEWORK_ONLY,
            classify(vendorNegative = true),
        )
    }

    @Test
    fun frameworkRouteWithUnknownVendorLayerIsCandidate() {
        assertEquals(CompatVerdict.CANDIDATE, classify())
    }
}
