package com.gsmautoanswer.app.data

import java.util.Locale

object SimPolicy {
    fun isAllowed(configuredSubscriptionId: Int, callSubscriptionId: Int?): Boolean =
        configuredSubscriptionId == AppSettings.ALL_SIMS ||
            (callSubscriptionId != null && configuredSubscriptionId == callSubscriptionId)

    fun slotFromAccountId(accountId: String?): Int? =
        accountId
            ?.lowercase(Locale.ROOT)
            ?.let { Regex("sim\\s*([12])").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
}
