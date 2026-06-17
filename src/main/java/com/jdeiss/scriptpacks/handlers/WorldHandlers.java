package com.jdeiss.scriptpacks.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.jdeiss.scriptpacks.rpc.RpcDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;

import static com.jdeiss.scriptpacks.handlers.HandlerUtils.resolveLevel;

public final class WorldHandlers {

    private static final int MAX_FILL_VOLUME = 32768;

    private WorldHandlers() {}

    public static void register(RpcDispatcher dispatcher) {
        // getBlock — live read
        dispatcher.registerMutating("getBlock", (server, params) -> {
            ServerLevel level = resolveLevel(server, params);
            BlockPos pos = new BlockPos(
                    params.get("x").getAsInt(),
                    params.get("y").getAsInt(),
                    params.get("z").getAsInt());
            BlockState state = level.getBlockState(pos);
            JsonObject result = new JsonObject();
            result.addProperty("block", state.getBlock().builtInRegistryHolder().key().identifier().toString());
            return result;
        });

        // setBlock
        dispatcher.registerMutating("setBlock", (server, params) -> {
            ServerLevel level = resolveLevel(server, params);
            BlockPos pos = new BlockPos(
                    params.get("x").getAsInt(),
                    params.get("y").getAsInt(),
                    params.get("z").getAsInt());
            BlockState state = resolveBlockState(params.get("block").getAsString());
            level.setBlock(pos, state, Block.UPDATE_ALL);
            return JsonNull.INSTANCE;
        });

        // fillBlocks — volume cap enforced
        dispatcher.registerMutating("fillBlocks", (server, params) -> {
            ServerLevel level = resolveLevel(server, params);
            int x1 = params.get("x1").getAsInt(), y1 = params.get("y1").getAsInt(), z1 = params.get("z1").getAsInt();
            int x2 = params.get("x2").getAsInt(), y2 = params.get("y2").getAsInt(), z2 = params.get("z2").getAsInt();

            int dx = Math.abs(x2 - x1) + 1;
            int dy = Math.abs(y2 - y1) + 1;
            int dz = Math.abs(z2 - z1) + 1;
            long volume = (long) dx * dy * dz;
            if (volume > MAX_FILL_VOLUME) {
                throw new IllegalArgumentException("Fill volume " + volume + " exceeds cap of " + MAX_FILL_VOLUME);
            }

            BlockState state = resolveBlockState(params.get("block").getAsString());
            int count = 0;
            int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
            int maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (level.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_ALL)) {
                            count++;
                        }
                    }
                }
            }

            JsonObject result = new JsonObject();
            result.addProperty("blocksChanged", count);
            return result;
        });

        // getEntities — live read
        dispatcher.registerMutating("getEntities", (server, params) -> {
            ServerLevel level = resolveLevel(server, params);
            double x = params.get("x").getAsDouble();
            double y = params.get("y").getAsDouble();
            double z = params.get("z").getAsDouble();
            double radius = params.has("radius") ? params.get("radius").getAsDouble() : 32.0;

            AABB box = new AABB(x - radius, y - radius, z - radius,
                    x + radius, y + radius, z + radius);
            List<Entity> entities = level.getEntities((Entity) null, box, e -> true);

            JsonArray arr = new JsonArray();
            for (Entity entity : entities) {
                JsonObject obj = new JsonObject();
                obj.addProperty("uuid", entity.getUUID().toString());
                obj.addProperty("type", EntityType.getKey(entity.getType()).toString());
                Vec3 pos = entity.position();
                obj.addProperty("x", pos.x);
                obj.addProperty("y", pos.y);
                obj.addProperty("z", pos.z);
                if (entity.hasCustomName()) {
                    obj.addProperty("customName", entity.getCustomName().getString());
                }
                arr.add(obj);
            }
            return arr;
        });

        // spawnEntity
        dispatcher.registerMutating("spawnEntity", (server, params) -> {
            ServerLevel level = resolveLevel(server, params);
            String typeId = params.get("type").getAsString();
            double x = params.get("x").getAsDouble();
            double y = params.get("y").getAsDouble();
            double z = params.get("z").getAsDouble();

            Optional<Holder.Reference<EntityType<?>>> typeHolder = BuiltInRegistries.ENTITY_TYPE.get(Identifier.parse(typeId));
            if (typeHolder.isEmpty()) {
                throw new IllegalArgumentException("Unknown entity type: " + typeId);
            }

            Entity entity = typeHolder.get().value().create(level, EntitySpawnReason.COMMAND);
            if (entity == null) {
                throw new IllegalArgumentException("Could not create entity of type: " + typeId);
            }
            entity.setPos(x, y, z);
            level.addFreshEntity(entity);

            JsonObject result = new JsonObject();
            result.addProperty("uuid", entity.getUUID().toString());
            return result;
        });

        // getTime — live read, use command for reliable access
        dispatcher.registerMutating("getTime", (server, params) -> {
            ServerLevel level = resolveLevel(server, params);
            JsonObject result = new JsonObject();
            result.addProperty("dayTime", level.getDefaultClockTime() % 24000);
            result.addProperty("gameTime", level.getLevelData().getGameTime());
            return result;
        });

        // setTime — use command for reliable time setting
        dispatcher.registerMutating("setTime", (server, params) -> {
            long time = params.get("time").getAsLong();
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack(), "time set " + time);
            return JsonNull.INSTANCE;
        });

        // setWeather — use command for reliable weather setting
        dispatcher.registerMutating("setWeather", (server, params) -> {
            String weather = params.get("weather").getAsString();
            int duration = params.has("duration") ? params.get("duration").getAsInt() : 6000;

            String cmd = switch (weather.toLowerCase()) {
                case "clear" -> "weather clear " + (duration / 20);
                case "rain" -> "weather rain " + (duration / 20);
                case "thunder" -> "weather thunder " + (duration / 20);
                default -> throw new IllegalArgumentException("Unknown weather: " + weather + " (clear/rain/thunder)");
            };
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            return JsonNull.INSTANCE;
        });

        // setGameRule — use command
        dispatcher.registerMutating("setGameRule", (server, params) -> {
            String ruleName = params.get("rule").getAsString();
            String value = params.get("value").getAsString();
            server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack(), "gamerule " + ruleName + " " + value);
            return JsonNull.INSTANCE;
        });

        // setDifficulty
        dispatcher.registerMutating("setDifficulty", (server, params) -> {
            String diff = params.get("difficulty").getAsString();
            Difficulty difficulty = switch (diff.toLowerCase()) {
                case "peaceful" -> Difficulty.PEACEFUL;
                case "easy" -> Difficulty.EASY;
                case "normal" -> Difficulty.NORMAL;
                case "hard" -> Difficulty.HARD;
                default -> throw new IllegalArgumentException("Unknown difficulty: " + diff);
            };
            server.setDifficulty(difficulty, true);
            return JsonNull.INSTANCE;
        });

        // playSound
        dispatcher.registerMutating("playSound", (server, params) -> {
            ServerLevel level = resolveLevel(server, params);
            double x = params.get("x").getAsDouble();
            double y = params.get("y").getAsDouble();
            double z = params.get("z").getAsDouble();
            String soundId = params.get("sound").getAsString();
            float volume = params.has("volume") ? params.get("volume").getAsFloat() : 1.0f;
            float pitch = params.has("pitch") ? params.get("pitch").getAsFloat() : 1.0f;

            Optional<Holder.Reference<SoundEvent>> soundHolder = BuiltInRegistries.SOUND_EVENT.get(Identifier.parse(soundId));
            if (soundHolder.isEmpty()) {
                throw new IllegalArgumentException("Unknown sound: " + soundId);
            }

            level.playSound(null, x, y, z, soundHolder.get(), SoundSource.MASTER, volume, pitch);
            return JsonNull.INSTANCE;
        });

        // spawnParticles — use command as particle types require codec parsing
        dispatcher.registerMutating("spawnParticles", (server, params) -> {
            String particleId = params.get("particle").getAsString();
            double x = params.get("x").getAsDouble();
            double y = params.get("y").getAsDouble();
            double z = params.get("z").getAsDouble();
            int count = params.has("count") ? params.get("count").getAsInt() : 1;
            double dx = params.has("dx") ? params.get("dx").getAsDouble() : 0;
            double dy = params.has("dy") ? params.get("dy").getAsDouble() : 0;
            double dz = params.has("dz") ? params.get("dz").getAsDouble() : 0;
            double speed = params.has("speed") ? params.get("speed").getAsDouble() : 0;

            String cmd = String.format("particle %s %.2f %.2f %.2f %.2f %.2f %.2f %.4f %d",
                    particleId, x, y, z, dx, dy, dz, speed, count);
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
            return JsonNull.INSTANCE;
        });

        // setWorldBorder
        dispatcher.registerMutating("setWorldBorder", (server, params) -> {
            ServerLevel level = resolveLevel(server, params);
            WorldBorder border = level.getWorldBorder();

            if (params.has("center")) {
                JsonObject center = params.getAsJsonObject("center");
                border.setCenter(center.get("x").getAsDouble(), center.get("z").getAsDouble());
            }
            if (params.has("size")) {
                double size = params.get("size").getAsDouble();
                if (params.has("time")) {
                    long timeSeconds = params.get("time").getAsLong();
                    // Use command for reliable border lerp
                    server.getCommands().performPrefixedCommand(
                            server.createCommandSourceStack(),
                            "worldborder set " + size + " " + timeSeconds);
                } else {
                    border.setSize(size);
                }
            }
            if (params.has("damagePerBlock")) {
                border.setDamagePerBlock(params.get("damagePerBlock").getAsDouble());
            }
            if (params.has("warningDistance")) {
                border.setWarningBlocks(params.get("warningDistance").getAsInt());
            }

            return JsonNull.INSTANCE;
        });
    }

    private static BlockState resolveBlockState(String blockId) {
        Optional<Holder.Reference<Block>> blockHolder = BuiltInRegistries.BLOCK.get(Identifier.parse(blockId));
        if (blockHolder.isEmpty()) {
            throw new IllegalArgumentException("Unknown block: " + blockId);
        }
        return blockHolder.get().value().defaultBlockState();
    }
}
