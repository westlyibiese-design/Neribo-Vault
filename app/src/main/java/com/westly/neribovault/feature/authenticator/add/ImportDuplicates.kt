package com.westly.neribovault.feature.authenticator.add

import com.westly.neribovault.feature.authenticator.engine.OtpEntry

/**
 * Whether [a] and [b] are the same account: equal secret bytes and equal algorithm, digits and
 * period. Issuer and account names are deliberately not compared.
 */
fun sameAccount(a: OtpEntry, b: OtpEntry): Boolean =
    a.secret.contentEquals(b.secret) &&
        a.algorithm == b.algorithm &&
        a.digits == b.digits &&
        a.periodSeconds == b.periodSeconds
