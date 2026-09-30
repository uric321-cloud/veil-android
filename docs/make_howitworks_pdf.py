"""Builds 'VEIL - How it works.pdf': a plain-language explainer of build one."""
from reportlab.lib.pagesizes import letter
from reportlab.lib.units import inch
from reportlab.lib import colors
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.enums import TA_LEFT
from reportlab.platypus import (SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle,
                                PageBreak, Flowable, KeepTogether)

NAVY = colors.HexColor("#1F2A44")
ACCENT = colors.HexColor("#2F6BFF")
GOOD = colors.HexColor("#1E9E5A")
BAD = colors.HexColor("#D14343")
INK = colors.HexColor("#1B1F2A")
MUTED = colors.HexColor("#667085")
LINE = colors.HexColor("#D0D5DD")
TINT = colors.HexColor("#EEF2FF")
CARD = colors.HexColor("#F5F6FA")

W, H = letter
MARGIN = 0.8 * inch
CONTENT_W = W - 2 * MARGIN

base = ParagraphStyle("base", fontName="Helvetica", fontSize=10.5, leading=15, textColor=INK)
h1 = ParagraphStyle("h1", parent=base, fontName="Helvetica-Bold", fontSize=22, leading=27, textColor=NAVY, spaceAfter=6)
h2 = ParagraphStyle("h2", parent=base, fontName="Helvetica-Bold", fontSize=14.5, leading=19, textColor=NAVY, spaceBefore=12, spaceAfter=5)
h3 = ParagraphStyle("h3", parent=base, fontName="Helvetica-Bold", fontSize=11, leading=15, textColor=INK, spaceBefore=8, spaceAfter=3)
small = ParagraphStyle("small", parent=base, fontSize=9, leading=12.5, textColor=MUTED)
lead = ParagraphStyle("lead", parent=base, fontSize=12, leading=17.5, textColor=INK)
cell = ParagraphStyle("cell", parent=base, fontSize=9.2, leading=12.4)
cellb = ParagraphStyle("cellb", parent=cell, fontName="Helvetica-Bold")
bullet = ParagraphStyle("bullet", parent=base, leftIndent=14, bulletIndent=2, spaceAfter=3)


def P(text, style=base):
    return Paragraph(text, style)


def bullets(items):
    return [Paragraph(t, bullet, bulletText="•") for t in items]


def table(rows, widths, header=True):
    data = []
    for i, r in enumerate(rows):
        st = cellb if (header and i == 0) else cell
        data.append([Paragraph(c, st) for c in r])
    t = Table(data, colWidths=widths, repeatRows=1 if header else 0)
    style = [
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, -1), 0.5, LINE),
        ("TOPPADDING", (0, 0), (-1, -1), 4),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 4),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
    ]
    if header:
        style += [("BACKGROUND", (0, 0), (-1, 0), TINT), ("LINEBELOW", (0, 0), (-1, 0), 1, ACCENT)]
    t.setStyle(TableStyle(style))
    return t


# ---------------------------------------------------------------- drawing helpers

class Diagram(Flowable):
    def __init__(self, width, height, painter):
        super().__init__()
        self.width, self.height, self.painter = width, height, painter

    def wrap(self, aw, ah):
        return self.width, self.height

    def draw(self):
        self.painter(self.canv, self.width, self.height)


def rbox(c, x, y, w, h, text, fill=None, stroke=LINE, bold=False, size=9.5, color=INK, sub=None):
    c.saveState()
    c.setLineWidth(1)
    c.setStrokeColor(stroke)
    c.setFillColor(fill or colors.white)
    c.roundRect(x, y, w, h, 7, stroke=1, fill=1)
    c.setFillColor(color)
    c.setFont("Helvetica-Bold" if bold else "Helvetica", size)
    lines = text.split("\n")
    nsub = (len(sub) if isinstance(sub, list) else 1) if sub else 0
    total = len(lines) * (size + 3) + nsub * 10 + (2 if sub else 0)
    ty = y + h / 2 + total / 2 - size
    for ln in lines:
        c.drawCentredString(x + w / 2, ty, ln)
        ty -= size + 3
    if sub:
        c.setFont("Helvetica", 8)
        c.setFillColor(MUTED)
        subs = sub if isinstance(sub, list) else [sub]
        for k, ln in enumerate(subs):
            c.drawCentredString(x + w / 2, ty - 1 - k * 10, ln)
    c.restoreState()


def arrow(c, x1, y1, x2, y2, color=MUTED, label=None, lx=0, ly=0):
    c.saveState()
    c.setStrokeColor(color)
    c.setFillColor(color)
    c.setLineWidth(1)
    c.line(x1, y1, x2, y2)
    import math
    ang = math.atan2(y2 - y1, x2 - x1)
    L = 6
    p = c.beginPath()
    p.moveTo(x2, y2)
    p.lineTo(x2 - L * math.cos(ang - 0.45), y2 - L * math.sin(ang - 0.45))
    p.lineTo(x2 - L * math.cos(ang + 0.45), y2 - L * math.sin(ang + 0.45))
    p.close()
    c.drawPath(p, stroke=0, fill=1)
    if label:
        c.setFont("Helvetica", 8)
        c.setFillColor(MUTED)
        c.drawCentredString((x1 + x2) / 2 + lx, (y1 + y2) / 2 + ly, label)
    c.restoreState()


def elbow(c, pts, color=MUTED, label=None, lx=0, ly=0):
    """Polyline with an arrowhead on the last segment."""
    for i in range(len(pts) - 2):
        c.saveState(); c.setStrokeColor(color); c.setLineWidth(1)
        c.line(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]); c.restoreState()
    arrow(c, pts[-2][0], pts[-2][1], pts[-1][0], pts[-1][1], color, label, lx, ly)


def diagram_lookup(c, w, h):
    """One DNS lookup, left to right: app -> Android -> VEIL -> three outcomes."""
    bw, bh = 118, 46
    y = h - 56
    x0 = 0
    rbox(c, x0, y, bw, bh, "App or browser\nasks for a site", sub="e.g. www.example.com")
    x1 = x0 + bw + 34
    rbox(c, x1, y, bw, bh, "Android resolver", sub="sends every DNS question")
    x2 = x1 + bw + 34
    rbox(c, x2, y, 128, bh, "VEIL tunnel", fill=TINT, stroke=ACCENT, bold=True, sub="checks the rules")
    arrow(c, x0 + bw, y + bh / 2, x1, y + bh / 2)
    arrow(c, x1 + bw, y + bh / 2, x2, y + bh / 2)

    oh = 64
    oy = y - 34 - oh
    ow = 150
    gap = (w - 3 * ow) / 2
    ox = [0, ow + gap, 2 * (ow + gap)]
    rbox(c, ox[0], oy, ow, oh, "Blocked", fill=colors.HexColor("#FBECEC"), stroke=BAD, bold=True, color=BAD,
         sub=["adult list, keywords, block list,", "encrypted-DNS bypass", "answer: name does not exist"])
    rbox(c, ox[1], oy, ow, oh, "Safe-search rewrite", fill=colors.HexColor("#FFF6E5"), stroke=colors.HexColor("#B7791F"),
         bold=True, color=colors.HexColor("#8A5A12"),
         sub=["Google, YouTube, Bing, DuckDuckGo", "answer: the safe endpoint's", "address"])
    rbox(c, ox[2], oy, ow, oh, "Allowed", fill=colors.HexColor("#E8F6EE"), stroke=GOOD, bold=True, color=GOOD,
         sub=["everything else", "forwarded to Cloudflare or Quad9,", "answer handed back"])
    cx = x2 + 64
    run = y - 17
    for i in range(3):
        tx = ox[i] + ow / 2
        if i == 1 and abs(tx - cx) < 1:
            arrow(c, cx, y, tx, oy + oh)
        else:
            elbow(c, [(cx, y), (cx, run), (tx, run), (tx, oy + oh)])
    c.saveState(); c.setFont("Helvetica", 8.5); c.setFillColor(INK)
    c.drawString(0, oy - 18, "The answer travels back the same way. The browser only ever sees a normal DNS reply; no web page passes through VEIL.")
    c.restoreState()


def diagram_layers(c, w, h):
    """What runs where: the phone, and the two things it talks to."""
    px, py, pw, ph = 0, 24, 300, h - 30
    c.saveState(); c.setFillColor(CARD); c.setStrokeColor(LINE); c.roundRect(px, py, pw, ph, 10, stroke=1, fill=1)
    c.setFont("Helvetica-Bold", 10); c.setFillColor(NAVY); c.drawString(px + 12, py + ph - 18, "Your phone: the VEIL app")
    c.restoreState()
    bw, bh = 270, 40
    x = px + 15
    top = py + ph - 32
    yy = top - bh
    rbox(c, x, yy, bw, bh, "Rules", fill=TINT, stroke=ACCENT, bold=True, sub="adult list, keywords, allow/block, safe search")
    yy -= bh + 12
    tunnel_y = yy
    rbox(c, x, yy, bw, bh, "DNS tunnel (local VPN)", bold=True, sub="sees every lookup; refuses encrypted-DNS servers")
    yy -= bh + 12
    rbox(c, x, yy, bw, bh, "Watchdog", bold=True, sub="Private DNS, another VPN, tampering: you get an alert")
    yy -= bh + 12
    rbox(c, x, yy, bw, bh, "PIN, activity log, notifications", bold=True, sub="off switch and rule edits ask for the PIN")

    rx = px + pw + 56
    rw = w - rx
    ry1 = tunnel_y + 26
    rbox(c, rx, ry1, rw, 44, "Public resolver", bold=True, sub=["Cloudflare 1.1.1.1 or Quad9", "plain DNS, allowed lookups only"])
    ry2 = tunnel_y - 34
    rbox(c, rx, ry2, rw, 44, "Cloudflare for Families", bold=True, sub=["optional extra adult filter", "1.1.1.3, when the toggle is on"])
    ty = tunnel_y + bh / 2
    elbow(c, [(x + bw, ty), (x + bw + 28, ty), (x + bw + 28, ry1 + 22), (rx, ry1 + 22)])
    elbow(c, [(x + bw + 28, ty), (x + bw + 28, ry2 + 22), (rx, ry2 + 22)])
    rbox(c, rx, py + 4, rw, 44, "No VEIL server", fill=colors.HexColor("#E8F6EE"), stroke=GOOD, bold=True, color=GOOD,
         sub=["no account, no upload:", "browsing never leaves the phone"])


# ---------------------------------------------------------------- page furniture

def on_page(c, doc):
    c.saveState()
    c.setFont("Helvetica", 8.5)
    c.setFillColor(MUTED)
    c.drawString(MARGIN, 0.5 * inch, "VEIL · How it works · build one · September 2026")
    c.drawRightString(W - MARGIN, 0.5 * inch, "Page %d" % doc.page)
    c.setStrokeColor(LINE); c.setLineWidth(0.5)
    c.line(MARGIN, 0.5 * inch + 12, W - MARGIN, 0.5 * inch + 12)
    c.restoreState()


# ---------------------------------------------------------------- content

def build(path):
    doc = SimpleDocTemplate(path, pagesize=letter, leftMargin=MARGIN, rightMargin=MARGIN,
                            topMargin=0.75 * inch, bottomMargin=0.85 * inch,
                            title="VEIL - How it works", author="VEIL", subject="Build one explainer")
    s = []

    s.append(P("VEIL", ParagraphStyle("brand", parent=small, fontName="Helvetica-Bold", textColor=ACCENT, fontSize=11, spaceAfter=2)))
    s.append(P("How it works", h1))
    s.append(P("VEIL is an Android app that blocks adult sites and anything else you put on its block list, "
               "in every app and browser on the phone, and forces the safe mode of Google, YouTube, Bing and "
               "DuckDuckGo. It does this by controlling one thing every app depends on: the lookup that turns a "
               "site's name into an address.", lead))
    s.append(Spacer(1, 10))

    s.append(P("The idea in one paragraph", h2))
    s.append(P("Before a phone can load <b>www.example.com</b>, it has to ask a DNS server what address that name "
               "points to. VEIL installs itself as the phone's DNS server, using Android's built-in VPN feature in a "
               "very limited way: it captures only these name lookups, not the web traffic itself. Every lookup is checked "
               "against the rules in a few thousandths of a second. If the name is blocked, VEIL answers "
               "“that name does not exist” and the page fails to load. If it is a search engine, VEIL "
               "answers with the address of the search engine's safe version. Everything else is passed to a "
               "normal public resolver and the answer is handed back. No page, image or video ever passes through VEIL."))
    s.append(Spacer(1, 6))
    s.append(Diagram(CONTENT_W, 212, diagram_lookup))

    s.append(P("What it cannot see, on purpose and by design", h2))
    s.extend(bullets([
        "Inside pages. An explicit image on an otherwise allowed site (a social feed, a chat) is not filtered by DNS. That needs the browser reader and the on-device image filter of the next builds.",
        "Paths. reddit.com/r/something looks the same as reddit.com to DNS. Block the whole site or allow it; path-level rules arrive with the browser reader.",
        "Apps that ship their own addresses. A handful of apps never ask for a name at all. Rare, and the same limitation every DNS-based filter has.",
        "Local network names such as a printer's. These will not resolve while protection is on.",
    ]))

    s.append(PageBreak())
    s.append(P("The rules, in the order they are checked", h2))
    s.append(P("The first rule that matches decides. That order is what makes the allow list win over everything, "
               "and what lets you override a blanket category with one exception."))
    s.append(Spacer(1, 6))
    s.append(table([
        ["#", "Rule", "What it does"],
        ["1", "Your allow list", "Always allowed, no other rule is consulted. Lookups for these go to the unfiltered resolver, so they work even if the optional upstream filter would block them."],
        ["2", "Encrypted-DNS bypass", "Names of public encrypted-DNS services (Google, Cloudflare, Quad9, NextDNS, and about 70 more) are refused, so a browser's “secure DNS” setting cannot route around VEIL."],
        ["3", "Your block list", "Anything you added, with all its subdomains. “www.” is dropped automatically."],
        ["4", "Keyword rules", "Any site name containing one of your keywords (porn, xxx, hentai, onlyfans, …). Keep them specific; “sex” would also block essex.gov.uk."],
        ["5", "Adult content list", "47,663 known adult domains bundled with the app, built from two public lists, matched with all their subdomains. Updatable from Settings."],
        ["6", "Safe search", "google.com and its country versions, youtube.com (including the YouTube app), bing.com and duckduckgo.com are answered with the address of their safe-search or restricted-mode endpoint."],
        ["7", "Everything else", "Forwarded to Cloudflare (1.1.1.1) or Quad9, or to Cloudflare for Families (1.1.1.3) when the optional extra upstream filter is on."],
    ], [0.3 * inch, 1.5 * inch, CONTENT_W - 1.8 * inch]))

    s.append(P("How it resists being switched off", h2))
    s.append(table([
        ["Attempt", "What happens in build one"],
        ["Turning protection off in the app", "Asks for the PIN, if one is set. Give the PIN to someone you trust if you want them to hold the key."],
        ["Changing rules or the lists", "Asks for the PIN."],
        ["Chrome “Secure DNS” set to Cloudflare or Google", "Blocked at the network level: traffic to those servers is pulled into the tunnel and refused, so Chrome falls back to VEIL."],
        ["Android “Private DNS” set to a hostname", "Detected within about a minute: a warning on the Home screen plus an alert. Lookups fail entirely while it is on, so it is obvious."],
        ["Turning on another VPN app", "Android allows only one VPN at a time, so VEIL is switched off. It records the event, alerts you, and shows “Protection was turned off” until it is turned back on."],
        ["Rebooting the phone", "Protection restarts by itself. Turning on Android's “Always-on VPN” for VEIL makes the system enforce this too."],
        ["Uninstalling the app", "Not prevented in build one. Uninstall blocking needs Android's Device Owner mode and comes in a later build."],
    ], [2.2 * inch, CONTENT_W - 2.2 * inch]))

    s.append(PageBreak())
    s.append(P("What runs where", h2))
    s.append(P("Everything that matters runs on the phone. The only outside services are ordinary public DNS resolvers, "
               "which see the same lookups any phone sends them. There is no VEIL server, no account, and nothing "
               "about browsing is uploaded anywhere."))
    s.append(Spacer(1, 6))
    s.append(Diagram(CONTENT_W, 250, diagram_layers))

    s.append(P("From code to your phone", h2))
    s.append(P("The app is about 2,500 lines of Kotlin with no third-party libraries. The Android build tools cannot run "
               "in the environment where it was written, so the compile step runs on GitHub's servers instead:"))
    s.append(Spacer(1, 4))
    s.append(table([
        ["Step", "Who", "What"],
        ["1", "Claude", "Writes the code, type-checks it against the Android SDK, and runs the engine tests (DNS parsing, packet checksums, rule matching, live resolver forwarding). All 49 pass."],
        ["2", "You, once", "Create an empty private GitHub repository and connect GitHub to Claude."],
        ["3", "Claude", "Pushes the project. The push starts a GitHub Actions job that compiles a signed .apk and publishes it under Releases."],
        ["4", "You", "Open the Releases page on the phone, download the .apk, install it, tap Turn on protection, accept the VPN prompt."],
        ["5", "Both", "You test; if anything is off, Settings → Share report sends a diagnostic (settings, recent blocks, VEIL's own log, no browsing history). Claude fixes, pushes, GitHub rebuilds. Each new build installs over the last one."],
    ], [0.45 * inch, 0.85 * inch, CONTENT_W - 1.3 * inch]))

    s.append(PageBreak())
    s.append(P("The twelve-step test for a new build", h2))
    s.append(table([
        ["#", "Do this", "Expected"],
        ["1", "Install, open VEIL, tap Turn on protection, accept the VPN prompt", "Home shows “Protected”; a VEIL notification appears"],
        ["2", "Open Chrome and visit a known adult site", "Fails to load; appears under Activity; a “Blocked” notification"],
        ["3", "Visit wikipedia.org and a news site", "Load normally"],
        ["4", "Search on google.com", "Results page shows SafeSearch is on"],
        ["5", "Rules → Block list: add reddit.com", "Reddit stops loading; remove it afterwards"],
        ["6", "Rules → Allow list: add a blocked site", "It loads again; remove it afterwards"],
        ["7", "Chrome → Settings → Privacy → Use secure DNS → Cloudflare", "Adult sites are still blocked"],
        ["8", "Android Settings → Network → Private DNS → hostname dns.google", "Warning within a minute; set it back to Automatic"],
        ["9", "Settings → Set a PIN, then try to turn protection off", "Asks for the PIN"],
        ["10", "Reboot the phone", "Protection comes back on by itself"],
        ["11", "Leave it on overnight", "VEIL near the bottom of Android's battery screen"],
        ["12", "Anything fails", "Settings → Diagnostics → Share report"],
    ], [0.4 * inch, 3.25 * inch, CONTENT_W - 3.65 * inch]))

    s.append(P("What comes next", h2))
    s.append(table([
        ["Build", "Adds", "Why it matters"],
        ["Two", "Browser address-bar reading, unsupported-browser blocking, in-page block screen with a Request button, guarding of VEIL's own settings", "Path-level rules and a proper block page instead of a “site can't be reached” error"],
        ["Three", "Request-to-approve flow, a key-holder mode for a partner or parent, web dashboard", "Time-boxed exceptions granted from another phone; the start of Guardian mode"],
        ["Four", "On-device image and video filtering (Android 17's system classifier, a small bundled model on older phones), blur or block per app", "Explicit content on allowed sites and apps, with nothing leaving the phone"],
        ["Five", "Device Owner build for real lockdown, Samsung Knox, Family Link coexistence", "Uninstall and VPN changes blocked for families who want it"],
    ], [0.55 * inch, 3.2 * inch, CONTENT_W - 3.75 * inch]))

    doc.build(s, onFirstPage=on_page, onLaterPages=on_page)


if __name__ == "__main__":
    import sys
    build(sys.argv[1] if len(sys.argv) > 1 else "VEIL - How it works.pdf")
