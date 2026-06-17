export interface PlayerData {
  uuid: string;
  name: string;
  health: number;
  maxHealth: number;
  foodLevel: number;
  saturation: number;
  experienceLevel: number;
  totalExperience: number;
  gameMode: string;
  position: Position;
  dimension: string;
  yaw: number;
  pitch: number;
  activeEffects: ActiveEffect[];
}

export interface Position {
  x: number;
  y: number;
  z: number;
}

export interface ActiveEffect {
  id: string;
  duration: number;
  amplifier: number;
}

export interface InventorySlot {
  slot: number;
  empty?: boolean;
  id?: string;
  count?: number;
}

export interface ItemSpec {
  id: string;
  count?: number;
  components?: Record<string, unknown>;
}

export interface GiveResult {
  added: boolean;
  remainingCount: number;
}

export interface RemoveResult {
  removedCount: number;
}

export interface SpawnResult {
  uuid: string;
}

export interface EntityData {
  uuid: string;
  type: string;
  x: number;
  y: number;
  z: number;
  customName?: string;
}

export interface BlockData {
  block: string;
}

export interface TimeData {
  dayTime: number;
  gameTime: number;
}

export interface FillResult {
  blocksChanged: number;
}

export type GameMode = "survival" | "creative" | "adventure" | "spectator";
export type Weather = "clear" | "rain" | "thunder";
export type Difficulty = "peaceful" | "easy" | "normal" | "hard";
