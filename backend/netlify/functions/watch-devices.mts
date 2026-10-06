import type { Config } from "@netlify/functions";
import { checkSilentDevices } from "../lib/model.ts";
import { notifyAdmin } from "../lib/notify.ts";

/**
 * Raises a "phone went silent" alert for any paired phone that was checking in
 * and then stopped — the main signal, on a normal (non-Device-Owner) install,
 * that VEIL was uninstalled or the phone was turned off. Needs no AI key.
 */
export default async () => {
  const raised = await checkSilentDevices();
  for (const d of raised) {
    await notifyAdmin(d.adminId, { title: "A phone stopped checking in", body: `${d.name} is no longer reporting. VEIL may have been removed, or the phone is off.`, path: `/#/device/${d.deviceId}`, tag: `silent-${d.deviceId}` });
  }
  if (raised.length > 0) console.log(`watch-devices: ${raised.length} silent-phone alert(s)`);
};

export const config: Config = {
  schedule: "*/15 * * * *",
};
