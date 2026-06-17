const RPC_PORT = process.env.RPC_PORT || "9736";
const NAMESPACE = process.env.SCRIPTPACK_NAMESPACE || "unknown";
const TOKEN = process.env.SCRIPTPACK_TOKEN || "";

type RpcResult<T> = { ok: true; value: T } | { ok: false; error: string };

export async function rpc<T>(
  method: string,
  params: Record<string, unknown> = {}
): Promise<T> {
  const res = await fetch(`http://127.0.0.1:${RPC_PORT}/rpc`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Scriptpack": NAMESPACE,
      "X-Token": TOKEN,
    },
    body: JSON.stringify({ method, params }),
  });

  if (res.status === 429) {
    throw new Error("RPC server overloaded (429)");
  }

  const data = (await res.json()) as RpcResult<T>;
  if (!data.ok) {
    throw new Error(data.error);
  }
  return data.value;
}
