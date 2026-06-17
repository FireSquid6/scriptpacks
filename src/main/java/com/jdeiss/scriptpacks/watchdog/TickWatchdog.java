package com.jdeiss.scriptpacks.watchdog;

import com.jdeiss.scriptpacks.rpc.RpcDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TickWatchdog {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/watchdog");
    private static final long CHECK_INTERVAL_MS = 5000;
    private static final long STUCK_THRESHOLD_NS = 30_000_000_000L; // 30 seconds

    private volatile long lastTickNanos = System.nanoTime();
    private final RpcDispatcher dispatcher;
    private Thread watchdogThread;
    private volatile boolean running;

    public TickWatchdog(RpcDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void onEndTick() {
        lastTickNanos = System.nanoTime();
    }

    public void start() {
        running = true;
        watchdogThread = Thread.ofVirtual().name("scriptpacks-watchdog").start(this::watchLoop);
        LOGGER.info("Tick watchdog started (threshold: {}s)", STUCK_THRESHOLD_NS / 1_000_000_000L);
    }

    public void stop() {
        running = false;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
        }
    }

    private void watchLoop() {
        while (running) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }

            long elapsed = System.nanoTime() - lastTickNanos;
            if (elapsed > STUCK_THRESHOLD_NS) {
                String handlerName = dispatcher.getCurrentMutatingHandler();
                if (handlerName != null) {
                    LOGGER.error("Server tick stuck for {}s — currently executing mutating handler: {}",
                            elapsed / 1_000_000_000L, handlerName);
                } else {
                    LOGGER.error("Server tick stuck for {}s — no active mutating handler (stuck in vanilla code?)",
                            elapsed / 1_000_000_000L);
                }
            }
        }
    }
}
