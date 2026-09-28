package com.positivity.accounting.archfixture.bankfeed.file;

import com.positivity.accounting.archfixture.bankrec.service.CoreServiceReachingIntoAdapter;

/** Violation: an adapter class depending on a core service instead of the intake port (fixture, #2300). */
public class AdapterReachingIntoCoreService {

    public int delegate(CoreServiceReachingIntoAdapter core) {
        return core.importBytes("x");
    }
}
