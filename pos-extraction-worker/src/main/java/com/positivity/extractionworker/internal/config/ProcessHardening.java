package com.positivity.extractionworker.internal.config;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Marks the web JVM non-dumpable ({@code prctl(PR_SET_DUMPABLE, 0)}) at start-up, best effort. On Linux that
 * makes this process's {@code /proc/self/*} files (environment, memory, file descriptors) owned by root, so
 * the parse children, which run as the same uid, cannot read the shared secret out of it. A child execs
 * afresh and is dumpable again; only the web process, which holds the secret, is hardened. Where the call is
 * unavailable (not Linux, native access refused) the worker logs that at INFO and carries on: the secret is
 * a mounted file, not an environment variable, and the README records the residual risk.
 */
@Component
@Slf4j
public class ProcessHardening implements InitializingBean {

    private static final int PR_SET_DUMPABLE = 4;

    @Override
    public void afterPropertiesSet() {
        if (disableDumpable()) {
            log.info("Web process marked non-dumpable (PR_SET_DUMPABLE=0)");
        } else {
            log.info("Could not mark the web process non-dumpable (not Linux, or native access unavailable)");
        }
    }

    /** Calls {@code prctl(PR_SET_DUMPABLE, 0)}; true when the kernel accepted it. */
    static boolean disableDumpable() {
        try {
            Linker linker = Linker.nativeLinker();
            var symbol = linker.defaultLookup().find("prctl");
            if (symbol.isEmpty()) {
                return false;
            }
            MethodHandle prctl = linker.downcallHandle(
                    symbol.get(),
                    FunctionDescriptor.of(
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_LONG),
                    Linker.Option.firstVariadicArg(1));
            int result = (int) prctl.invokeExact(PR_SET_DUMPABLE, 0L, 0L, 0L, 0L);
            return result == 0;
        } catch (Throwable e) { // NOSONAR: invokeExact throws Throwable; the call is best effort
            return false;
        }
    }
}
