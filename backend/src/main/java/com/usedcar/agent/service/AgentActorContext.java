package com.usedcar.agent.service;

import java.util.function.Supplier;

/** Carries the server-authenticated viewer across the synchronous tool loop on an SSE worker. */
public final class AgentActorContext {
    private static final ThreadLocal<Long> VIEWER = new ThreadLocal<>();
    private AgentActorContext() {}
    public static Long viewerId() { return VIEWER.get(); }
    public static <T> T withViewer(Long viewerId, Supplier<T> action) {
        Long previous = VIEWER.get();
        try { VIEWER.set(viewerId); return action.get(); }
        finally { if (previous == null) VIEWER.remove(); else VIEWER.set(previous); }
    }
}
