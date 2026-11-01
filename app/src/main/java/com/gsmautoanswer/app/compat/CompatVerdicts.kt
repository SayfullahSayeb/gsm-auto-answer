package com.gsmautoanswer.app.compat

import com.gsmautoanswer.app.data.LiveTestResult

object CompatVerdicts {
    fun classify(
        policyKnown: Boolean,
        frameworkRoute: Boolean,
        vendorEvidence: Boolean,
        vendorNegative: Boolean,
        liveResult: LiveTestResult,
        routeVerified: Boolean,
    ): CompatVerdict = when {
        liveResult == LiveTestResult.PASSED -> CompatVerdict.VERIFIED
        policyKnown && !frameworkRoute -> CompatVerdict.NOT_SUPPORTED
        liveResult == LiveTestResult.FAILED && routeVerified -> CompatVerdict.TEST_FAILED
        liveResult == LiveTestResult.FAILED -> CompatVerdict.TEST_INCONCLUSIVE
        !policyKnown -> CompatVerdict.UNKNOWN
        vendorEvidence -> CompatVerdict.LIKELY_SUPPORTED
        vendorNegative -> CompatVerdict.FRAMEWORK_ONLY
        else -> CompatVerdict.CANDIDATE
    }
}
