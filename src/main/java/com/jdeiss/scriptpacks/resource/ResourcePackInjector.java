package com.jdeiss.scriptpacks.resource;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;

public class ResourcePackInjector {

    private static final Logger LOGGER = LoggerFactory.getLogger("scriptpacks/resource");

    private static volatile MinecraftServer.ServerResourcePackInfo injectedPack;

    public static Optional<MinecraftServer.ServerResourcePackInfo> getInjectedPack() {
        return Optional.ofNullable(injectedPack);
    }

    public static void setResourcePack(String url, String sha1) {
        injectedPack = new MinecraftServer.ServerResourcePackInfo(
                UUID.nameUUIDFromBytes(("scriptpacks:" + sha1).getBytes()),
                url,
                sha1,
                false, // require-pack posture is the owner's product decision
                Component.literal("Scriptpacks resource pack")
        );
        LOGGER.info("Resource pack set: {} (SHA-1: {})", url, sha1);
    }

    public static void clearResourcePack() {
        injectedPack = null;
    }

    public static void pushToAllPlayers(MinecraftServer server) {
        MinecraftServer.ServerResourcePackInfo pack = injectedPack;
        if (pack == null) return;

        ClientboundResourcePackPushPacket packet = new ClientboundResourcePackPushPacket(
                pack.id(), pack.url(), pack.hash(), pack.isRequired(),
                Optional.ofNullable(pack.prompt()));
        server.getPlayerList().broadcastAll(packet);
        LOGGER.info("Pushed resource pack to {} player(s)", server.getPlayerList().getPlayerCount());
    }
}
