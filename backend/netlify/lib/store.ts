import { getDeployStore, getStore } from "@netlify/blobs";

/**
 * The small key-value surface the backend needs. Production uses one strongly
 * consistent Netlify Blobs store; deploy previews get a deploy-scoped store so
 * test data never mixes with real devices; tests swap in memoryKV().
 */
export interface KV {
  get<T>(key: string): Promise<T | null>;
  set(key: string, value: unknown): Promise<void>;
  del(key: string): Promise<void>;
  list(prefix: string): Promise<string[]>;
}

let override: KV | null = null;
let cached: KV | null = null;

export function useKV(kv: KV | null): void {
  override = kv;
}

export function kv(): KV {
  if (override) return override;
  if (cached) return cached;
  const netlify = (globalThis as { Netlify?: { context?: { deploy?: { context?: string } } } }).Netlify;
  const production = netlify?.context?.deploy?.context === "production";
  const store = production
    ? getStore({ name: "veil", consistency: "strong" })
    : getDeployStore({ name: "veil", consistency: "strong" });
  cached = {
    get: async <T>(key: string) => (await store.get(key, { type: "json" })) as T | null,
    set: async (key, value) => { await store.setJSON(key, value); },
    del: async (key) => { await store.delete(key); },
    list: async (prefix) => (await store.list({ prefix })).blobs.map((b) => b.key),
  };
  return cached;
}

export function memoryKV(): KV & { data: Map<string, string> } {
  const data = new Map<string, string>();
  return {
    data,
    get: async <T>(key: string) => (data.has(key) ? (JSON.parse(data.get(key)!) as T) : null),
    set: async (key, value) => { data.set(key, JSON.stringify(value)); },
    del: async (key) => { data.delete(key); },
    list: async (prefix) => [...data.keys()].filter((k) => k.startsWith(prefix)).sort(),
  };
}
