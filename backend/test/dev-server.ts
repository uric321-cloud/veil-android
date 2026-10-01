/**
 * Local stand-in for Netlify: serves public/, routes /api/* to the API function
 * and runs AI jobs inline. Data lives in memory.
 *
 *   node --experimental-strip-types test/dev-server.ts            # AI off
 *   VEIL_FAKE_AI=1 node --experimental-strip-types test/dev-server.ts   # canned AI answers
 *   ANTHROPIC_API_KEY=... node --experimental-strip-types test/dev-server.ts   # real Claude calls
 */
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { extname, join, normalize } from "node:path";
import { fileURLToPath } from "node:url";
import { memoryKV, useKV } from "../netlify/lib/store.ts";

const here = fileURLToPath(new URL(".", import.meta.url));
const publicDir = join(here, "..", "public");
const port = Number(process.env.PORT || 8888);

(globalThis as any).Netlify = { env: { get: (k: string) => process.env[k] }, context: { deploy: { context: "dev" } } };
useKV(memoryKV());

const ai = await import("../netlify/lib/ai.ts");
const { default: api } = await import("../netlify/functions/api.mts");
const { default: worker } = await import("../netlify/functions/ai-background.mts");

if (process.env.VEIL_FAKE_AI) {
  ai.useAnthropicForTests({
    beta: {
      messages: {
        create: async (p: any) => {
          const schema = JSON.stringify(p.output_config.format.schema);
          let out: unknown;
          if (schema.includes("recommendation")) out = { recommendation: "approve_limited", suggestedMinutes: 60, category: "School portal", risk: "low", explanation: "This is a school learning portal; the block came from a keyword inside its name. Allowing it for an hour covers the homework session." };
          else if (schema.includes("results")) out = { results: [] };
          else if (schema.includes("concernLevel")) out = { text: "A quiet day overall. VEIL blocked a handful of adult-site lookups in the late evening and one attempt to switch off the screen filter, which was turned back on within minutes.", highlights: ["3 adult-site lookups blocked after 22:00", "Screen filter switched off once", "No new unblock requests"], concernLevel: "medium" };
          else out = { reply: "Most blocks this week came from the adult content list, mostly in the evening. If you'd like, I can turn on strict YouTube mode.", hasProposal: true, proposalSummary: "Turn on strict YouTube mode", proposalPatchJson: "{\"youtubeStrict\":true}" };
          return { stop_reason: "end_turn", content: [{ type: "text", text: JSON.stringify(out) }] };
        },
      },
    },
  });
}

const TYPES: Record<string, string> = { ".html": "text/html; charset=utf-8", ".js": "text/javascript", ".css": "text/css", ".svg": "image/svg+xml" };

createServer(async (req, res) => {
  const url = new URL(req.url ?? "/", `http://localhost:${port}`);
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const body = chunks.length ? Buffer.concat(chunks) : undefined;
  const request = new Request(url, { method: req.method, headers: req.headers as Record<string, string>, body: req.method === "GET" || req.method === "HEAD" ? undefined : body });
  let response: Response;
  if (url.pathname.startsWith("/api/")) {
    // The API sets Secure cookies; browsers accept them on http://localhost.
    response = await api(request, {} as any);
  } else if (url.pathname === "/.netlify/functions/ai-background") {
    worker(request, {} as any).catch((e: unknown) => console.error(e));
    response = new Response(null, { status: 202 });
  } else {
    const file = normalize(join(publicDir, url.pathname === "/" ? "index.html" : url.pathname));
    if (!file.startsWith(publicDir)) response = new Response("no", { status: 403 });
    else {
      try {
        response = new Response(await readFile(file), { headers: { "content-type": TYPES[extname(file)] ?? "application/octet-stream" } });
      } catch {
        response = new Response("Not found", { status: 404 });
      }
    }
  }
  res.writeHead(response.status, Object.fromEntries(response.headers));
  res.end(Buffer.from(await response.arrayBuffer()));
}).listen(port, () => console.log(`VEIL dev server on http://localhost:${port}`));
