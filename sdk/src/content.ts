import { rpc } from "./rpc";
import type { ItemSpec } from "./types";

/** Build an ItemStack from an item spec (snapshot read, pure). */
export async function buildItemStack(item: ItemSpec): Promise<unknown> {
  return rpc<unknown>("buildItemStack", { item });
}

/** Apply components to an item spec, returning canonical form (snapshot read, pure). */
export async function applyComponents(item: ItemSpec): Promise<unknown> {
  return rpc<unknown>("applyComponents", { item });
}

/** Reload datapacks on the server. */
export async function reloadData(): Promise<void> {
  await rpc<null>("reloadData");
}

/**
 * Helper to create a custom item spec.
 * Uses vanilla item type with data components for custom identity.
 */
export function customItem(
  baseItem: string,
  namespace: string,
  itemId: string,
  options?: {
    count?: number;
    customName?: string;
    lore?: string[];
    customData?: Record<string, unknown>;
  }
): ItemSpec {
  const components: Record<string, unknown> = {
    "minecraft:item_model": `${namespace}:${itemId}`,
  };

  if (options?.customName) {
    components["minecraft:custom_name"] = JSON.stringify({
      text: options.customName,
    });
  }

  if (options?.lore && options.lore.length > 0) {
    components["minecraft:lore"] = options.lore.map((line) =>
      JSON.stringify({ text: line })
    );
  }

  if (options?.customData) {
    components["minecraft:custom_data"] = options.customData;
  }

  return {
    id: baseItem,
    count: options?.count ?? 1,
    components,
  };
}
