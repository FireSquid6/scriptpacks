import { rpc } from "./rpc";
import type {
  BlockData,
  EntityData,
  FillResult,
  SpawnResult,
  TimeData,
  Weather,
  Difficulty,
} from "./types";

/** Get the block at a position (live read). */
export async function getBlock(
  x: number,
  y: number,
  z: number,
  dimension?: string
): Promise<BlockData> {
  return rpc<BlockData>("getBlock", { x, y, z, dimension });
}

/** Set a block at a position. */
export async function setBlock(
  x: number,
  y: number,
  z: number,
  block: string,
  dimension?: string
): Promise<void> {
  await rpc<null>("setBlock", { x, y, z, block, dimension });
}

/** Fill a region with a block (max 32768 blocks). */
export async function fillBlocks(
  x1: number,
  y1: number,
  z1: number,
  x2: number,
  y2: number,
  z2: number,
  block: string,
  dimension?: string
): Promise<FillResult> {
  return rpc<FillResult>("fillBlocks", { x1, y1, z1, x2, y2, z2, block, dimension });
}

/** Get entities in a radius around a point (live read). */
export async function getEntities(
  x: number,
  y: number,
  z: number,
  options?: { radius?: number; dimension?: string }
): Promise<EntityData[]> {
  return rpc<EntityData[]>("getEntities", { x, y, z, ...options });
}

/** Spawn an entity at a position. */
export async function spawnEntity(
  type: string,
  x: number,
  y: number,
  z: number,
  dimension?: string
): Promise<SpawnResult> {
  return rpc<SpawnResult>("spawnEntity", { type, x, y, z, dimension });
}

/** Get the current time (live read). */
export async function getTime(dimension?: string): Promise<TimeData> {
  return rpc<TimeData>("getTime", { dimension });
}

/** Set the world time (in ticks). */
export async function setTime(time: number): Promise<void> {
  await rpc<null>("setTime", { time });
}

/** Set the weather. Duration in ticks. */
export async function setWeather(
  weather: Weather,
  duration?: number
): Promise<void> {
  await rpc<null>("setWeather", { weather, duration });
}

/** Set a game rule. */
export async function setGameRule(
  rule: string,
  value: string | number | boolean
): Promise<void> {
  await rpc<null>("setGameRule", { rule, value: String(value) });
}

/** Set the server difficulty. */
export async function setDifficulty(difficulty: Difficulty): Promise<void> {
  await rpc<null>("setDifficulty", { difficulty });
}

/** Play a sound at a position. */
export async function playSound(
  sound: string,
  x: number,
  y: number,
  z: number,
  options?: { volume?: number; pitch?: number; dimension?: string }
): Promise<void> {
  await rpc<null>("playSound", { sound, x, y, z, ...options });
}

/** Spawn particles at a position. */
export async function spawnParticles(
  particle: string,
  x: number,
  y: number,
  z: number,
  options?: {
    count?: number;
    dx?: number;
    dy?: number;
    dz?: number;
    speed?: number;
    dimension?: string;
  }
): Promise<void> {
  await rpc<null>("spawnParticles", { particle, x, y, z, ...options });
}

/** Configure the world border. */
export async function setWorldBorder(options: {
  center?: { x: number; z: number };
  size?: number;
  time?: number;
  damagePerBlock?: number;
  warningDistance?: number;
  dimension?: string;
}): Promise<void> {
  await rpc<null>("setWorldBorder", options);
}

/** Run an arbitrary Minecraft command (fallback). */
export async function runCommand(command: string): Promise<void> {
  await rpc<null>("runCommand", { command });
}
