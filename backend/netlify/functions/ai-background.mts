import type { Context } from "@netlify/functions";
import { runJob } from "../lib/ai.ts";
import { getJob, internalSecret } from "../lib/model.ts";

/** Runs one queued AI job. Background functions get 15 minutes, so slow model calls never block the API. */
export default async (req: Request, _ctx: Context) => {
  if (req.headers.get("x-veil-internal") !== (await internalSecret())) {
    console.warn("ai-background: rejected call without the internal secret");
    return;
  }
  const { jobId } = (await req.json()) as { jobId?: string };
  const job = jobId ? await getJob(jobId) : null;
  if (!job || job.status !== "queued") return;
  await runJob(job);
};
