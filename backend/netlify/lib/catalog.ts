import { createHash } from "node:crypto";

/**
 * Proactive site catalog: content-free knowledge about MIXED mainstream sites -
 * ones that are fine overall but have risky sections. It lets the phone decide a
 * section before the page loads (anticipatory, not reactive): e.g. on
 * victoriassecret.com, /loungewear is fine but /lingerie and /swim are not.
 *
 * The catalog is about SITES, never people - it is shared by every phone and
 * carries no user data at all. It is curated here and can be grown by the AI
 * classifier over time; phones download it on check-in (like the in-app rules).
 */
export interface PathRule {
  /** Base domain this rule applies to; matches the host or any subdomain of it. */
  domain: string;
  /** Lowercase substring to look for in the URL's path+query. Empty = whole domain. */
  contains: string;
  /** "block" hides that section; "allow" marks it explicitly fine (suppresses keyword over-blocking). */
  action: "block" | "allow";
  /** Content-free category label, for the block reason the phone reports. */
  category: string;
}

/**
 * The curated seed. Mainstream shopping and social sites that families use, with
 * the sections that aren't appropriate marked. Allow rules keep the fine parts
 * from being caught by keyword rules.
 */
const SEED: PathRule[] = [
  // Victoria's Secret (and PINK, a subdomain) is a lingerie brand end to end:
  // even its "sleepwear" and "cami sets" are sheer lingerie - e.g. a page filed
  // under /womens-sleepwear/cami-sets/ - and the product imagery is intimate on
  // every section. Per-section rules missed anything filed under an unexpected
  // path, so the whole domain is blocked. (To allow its apparel sections for a
  // family, swap this for per-path allows like { contains: "/clothing", allow }.)
  { domain: "victoriassecret.com", contains: "", action: "block", category: "lingerie" },
  { domain: "adoreme.com", contains: "", action: "block", category: "lingerie" },
  { domain: "savagex.com", contains: "", action: "block", category: "lingerie" },
  { domain: "honeybirdette.com", contains: "", action: "block", category: "lingerie" },
  { domain: "frederics.com", contains: "", action: "block", category: "lingerie" },

  // General department stores: block the intimates / lingerie / sleepwear / swim
  // sections, keeping the rest of the store usable. Sleepwear is included here
  // because these stores shelve sheer "sleep" sets alongside pyjamas.
  { domain: "macys.com", contains: "/lingerie", action: "block", category: "lingerie" },
  { domain: "macys.com", contains: "/intimates", action: "block", category: "lingerie" },
  { domain: "nordstrom.com", contains: "/lingerie", action: "block", category: "lingerie" },
  { domain: "nordstrom.com", contains: "/intimates", action: "block", category: "lingerie" },
  { domain: "target.com", contains: "/intimates", action: "block", category: "lingerie" },
  { domain: "kohls.com", contains: "/intimates", action: "block", category: "lingerie" },
  { domain: "amazon.com", contains: "/lingerie", action: "block", category: "lingerie" },
  { domain: "shein.com", contains: "/lingerie", action: "block", category: "lingerie" },
  { domain: "shein.com", contains: "/sleep", action: "block", category: "lingerie" },
  { domain: "shein.com", contains: "/swimwear", action: "block", category: "swimwear" },

  // Social / media sites: block the risky discovery surfaces, keep the rest.
  { domain: "reddit.com", contains: "/r/nsfw", action: "block", category: "adult" },
  { domain: "reddit.com", contains: "/r/gonewild", action: "block", category: "adult" },
  { domain: "tumblr.com", contains: "/search/nsfw", action: "block", category: "adult" },
  { domain: "pinterest.com", contains: "/lingerie", action: "block", category: "lingerie" },
  { domain: "x.com", contains: "/explore/tabs/for-you", action: "allow", category: "feed" },
];

/** True if [host] equals [domain] or is a subdomain of it. */
function hostMatches(host: string, domain: string): boolean {
  const h = host.toLowerCase().replace(/^www\./, "");
  const d = domain.toLowerCase();
  return h === d || h.endsWith(`.${d}`);
}

/** The catalog served to phones: a stable version hash and the flat rule list. */
export function effectiveCatalog(): { version: string; rules: PathRule[] } {
  const rules = SEED;
  const version = createHash("sha256").update(JSON.stringify(rules)).digest("hex").slice(0, 16);
  return { version, rules };
}

/**
 * Pure verdict for a URL against the catalog (used on the backend and in tests;
 * the phone has an equivalent). Returns "block"+category, "allow", or null.
 */
export function catalogVerdict(url: string, rules: PathRule[] = SEED): { action: "block" | "allow"; category: string } | null {
  const lower = url.trim().toLowerCase();
  const scheme = lower.indexOf("://");
  const afterScheme = scheme >= 0 ? lower.slice(scheme + 3) : lower;
  const host = afterScheme.split("/")[0].split("?")[0].split(":")[0];
  const slash = afterScheme.indexOf("/");
  const pathAndQuery = slash >= 0 ? afterScheme.slice(slash) : "";
  let block: { action: "block"; category: string } | null = null;
  let allow: { action: "allow"; category: string } | null = null;
  for (const r of rules) {
    if (!hostMatches(host, r.domain)) continue;
    const c = r.contains.toLowerCase();
    if (c && !pathAndQuery.includes(c)) continue;
    if (r.action === "block") block = { action: "block", category: r.category };
    else allow = { action: "allow", category: r.category };
  }
  // Safety-first: a block section wins over an allow section if both matched.
  return block ?? allow;
}
