import { rpc } from "./rpc";
import type {
  PlayerData,
  InventorySlot,
  ItemSpec,
  GiveResult,
  RemoveResult,
  GameMode,
} from "./types";

/** List all online players (snapshot read). */
export async function listPlayers(): Promise<PlayerData[]> {
  return rpc<PlayerData[]>("listPlayers");
}

/** Get snapshot data for a specific player. */
export async function getPlayerData(uuid: string): Promise<PlayerData> {
  return rpc<PlayerData>("getPlayerData", { uuid });
}

/** Get a player's full inventory (live read). */
export async function getInventory(uuid: string): Promise<InventorySlot[]> {
  return rpc<InventorySlot[]>("getInventory", { uuid });
}

/** Give a player an item built from an ItemSpec. */
export async function givePlayerItem(
  uuid: string,
  item: ItemSpec
): Promise<GiveResult> {
  return rpc<GiveResult>("givePlayerItem", { uuid, item });
}

/** Remove items from a specific inventory slot. */
export async function removeItem(
  uuid: string,
  slot: number,
  count?: number
): Promise<RemoveResult> {
  return rpc<RemoveResult>("removeItem", { uuid, slot, count });
}

/** Clear a player's entire inventory. */
export async function clearInventory(uuid: string): Promise<void> {
  await rpc<null>("clearInventory", { uuid });
}

/** Teleport a player to coordinates, optionally across dimensions. */
export async function teleportPlayer(
  uuid: string,
  x: number,
  y: number,
  z: number,
  options?: { dimension?: string; yaw?: number; pitch?: number }
): Promise<void> {
  await rpc<null>("teleportPlayer", { uuid, x, y, z, ...options });
}

/** Set a player's game mode. */
export async function setGameMode(
  uuid: string,
  gameMode: GameMode
): Promise<void> {
  await rpc<null>("setGameMode", { uuid, gameMode });
}

/** Set a player's health. */
export async function setHealth(
  uuid: string,
  health: number
): Promise<void> {
  await rpc<null>("setHealth", { uuid, health });
}

/** Set a player's hunger and optionally saturation. */
export async function setHunger(
  uuid: string,
  foodLevel: number,
  saturation?: number
): Promise<void> {
  await rpc<null>("setHunger", { uuid, foodLevel, saturation });
}

/** Give experience levels and/or points. */
export async function setXp(
  uuid: string,
  options: { levels?: number; points?: number }
): Promise<void> {
  await rpc<null>("setXp", { uuid, ...options });
}

/** Apply a status effect to a player. */
export async function applyEffect(
  uuid: string,
  effect: string,
  options?: { duration?: number; amplifier?: number }
): Promise<void> {
  await rpc<null>("applyEffect", { uuid, effect, ...options });
}

/** Clear a specific effect or all effects from a player. */
export async function clearEffect(
  uuid: string,
  effect?: string
): Promise<void> {
  await rpc<null>("clearEffect", { uuid, effect });
}

/** Set a player's attribute base value. */
export async function setAttribute(
  uuid: string,
  attribute: string,
  value: number
): Promise<void> {
  await rpc<null>("setAttribute", { uuid, attribute, value });
}

/** Send a chat message or action bar message to a player. */
export async function sendMessage(
  uuid: string,
  message: string,
  actionBar?: boolean
): Promise<void> {
  await rpc<null>("sendMessage", { uuid, message, actionBar });
}

/** Send a title and/or subtitle to a player. */
export async function sendTitle(
  uuid: string,
  options: { title?: string; subtitle?: string }
): Promise<void> {
  await rpc<null>("sendTitle", { uuid, ...options });
}
