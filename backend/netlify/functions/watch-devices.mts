import type { Config } from "@netlify/functions";
import { checkSilentDevices } from "../lib/model.ts";

/**
 * Raises a "phone went silent" alert for any paired phone that was checking in
 * and then stopped — the main signal, on a normal (non-Device-Owner) install,
 * that VEIL was uninstalled or the phone was turned off. Needs no AI key.
 */
export default async () => {
  const raised = await checkSilentDevices();
  if (raised > 0) console.log(`watch-devices: raised ${raised} silent-phone alert(s)`);
};

export const config: Config = {
  schedule: "*/15 * * * *",
};
