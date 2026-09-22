package com.datos.guilds.util;

import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Envoltorio simple de planificación: todo lo que toca mundo/jugadores
 * debe correr en el hilo principal; el resto en el pool asíncrono.
 */
public class SchedulerAdapter {

    private final MinecraftServer server;
    private final ExecutorService asyncPool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Guilds-Async");
        t.setDaemon(true);
        return t;
    });

    public SchedulerAdapter(MinecraftServer server) {
        this.server = server;
    }

    public void onMainThread(Runnable task) {
        server.execute(task);
    }

    public CompletableFuture<Void> onMainThreadFuture(Runnable task) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        server.execute(() -> {
            try {
                task.run();
                cf.complete(null);
            } catch (Throwable t) {
                cf.completeExceptionally(t);
            }
        });
        return cf;
    }

    public void async(Runnable task) {
        asyncPool.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                com.datos.guilds.GuildsMod.LOGGER.error("[Guilds] Error en tarea asíncrona", t);
            }
        });
    }

    /** Tarea repetitiva en el hilo principal, intervalo en ticks. */
    public void repeat(Runnable task, int intervalTicks) {
        new Thread(() -> {
            while (server.isRunning()) {
                try {
                    Thread.sleep(intervalTicks * 50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    server.execute(task);
                } catch (Throwable t) {
                    com.datos.guilds.GuildsMod.LOGGER.error("[Guilds] Error en tarea repetitiva", t);
                }
            }
        }, "Guilds-Repeater").start();
    }
}
