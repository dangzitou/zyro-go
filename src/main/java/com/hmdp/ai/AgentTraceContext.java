package com.hmdp.ai;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentTraceContext {

    private final ThreadLocal<List<String>> toolTraceHolder = ThreadLocal.withInitial(ArrayList::new);

    public void reset() {
        toolTraceHolder.get().clear();
    }

    public void record(String trace) {
        toolTraceHolder.get().add(trace);
    }

    public List<String> snapshot() {
        return List.copyOf(toolTraceHolder.get());
    }

    public void clear() {
        toolTraceHolder.remove();
    }
}
