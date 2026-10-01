import type { Config } from "@netlify/functions";
import { aiConfigured } from "../lib/ai.ts";
import { allActiveDevices, createJob, internalSecret } from "../lib/model.ts";

/**
 * Queues a daily report for every phone that checked in during the last two
 * days. Scheduled functions only get 30 seconds, so the model calls themselves
 * run in the background worker.
 */
export default async (req: Request) => {
  if (!aiConfigured()) return;
  const site = Netlify.env.get("URL") ?? new URL(req.url).origin;
  const secret = await internalSecret();
  const cutoff = Date.now() - 2 * 86_400_000;
  for (const d of await allActiveDevices()) {
    if (d.lastSeen < cutoff) continue;
    const job = await createJob("summary", { deviceId: d.id, periodDays: 1 });
    await fetch(new URL("/.netlify/functions/ai-background", site), {
      method: "POST",
      headers: { "content-type": "application/json", "x-veil-internal": secret },
      body: JSON.stringify({ jobId: job.id }),
    }).catch((err) => console.error(`daily summary for ${d.id}: ${err}`));
  }
};

export const config: Config = {
  schedule: "0 6 * * *",
};
