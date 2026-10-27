/* Luxe Park site data. Everything below was taken from the live luxeparkga.com
   availability pages and interactive property map on 2026-10-27.
   To update pricing or availability, edit UNITS and PLANS here only. */
window.LP = (function () {
  const IMG = (f, w) => `https://resource.rentcafe.com/image/upload/q_auto,f_auto,c_limit,w_${w || 1200}/s3/2/130001/${f}`;
  const OLE = "https://luxeparkga.securecafe.com/onlineleasing/luxe-park/oleapplication.aspx?stepname=RentalOptions&myOlePropertyId=1623054";

  const UPDATED = "2026-10-27 17:00";  // set by tools/update_pricing.py
  // matterport: paste the model id from a Matterport share link (https://my.matterport.com/show/?m=XXXX) once each plan is scanned.
  const PLANS = {
    shortleaf: { slug: "shortleaf", matterport: null, name: "The Shortleaf", beds: 1, baths: 1, sqft: 715,  from: 1275, fpId: 4624296, img: "1(3).jpg",
      tone: "#8c6d49", blurb: "A smart, light-filled one bedroom with an open kitchen, granite counters and a private patio or balcony.",
      rooms: ["Open kitchen with breakfast bar", "Bedroom with large closet", "Full bath", "Private patio or balcony", "Washer / dryer hookups"] },
    longleaf:  { slug: "longleaf",  name: "The Longleaf",  beds: 1, baths: 1, sqft: 842,  from: 1355, fpId: 4624310, img: "screenshot%202024-06-03%20161723.png",
      tone: "#7a5f3f", blurb: "Our largest one bedroom. Extra living space, a generous bedroom and room for a real dining table.",
      rooms: ["Kitchen with island seating", "Dining area", "Oversized bedroom", "Walk-in closet (select homes)", "Full bath", "Private patio or balcony"] },
    slash:     { slug: "slash",     name: "The Slash",     beds: 2, baths: 2, sqft: 1109, from: 1399, fpId: 4624311, img: "screenshot%202024-06-03%20161500.png",
      tone: "#b9956a", blurb: "Two bedrooms on opposite sides of a wide-open living area. Ideal for roommates or a home office.",
      rooms: ["Split-bedroom layout", "Two full baths", "Open living and dining", "Kitchen with island", "Two large closets", "Private patio or balcony"] },
    loblolly:  { slug: "loblolly",  name: "The Loblolly",  beds: 3, baths: 2, sqft: 1386, from: 1699, fpId: 4624312, img: "screenshot%202024-06-03%20161107.png",
      tone: "#e8d6b3", blurb: "Three bedrooms, two full baths and the biggest kitchen in the community. Join the waitlist to be first in line.",
      rooms: ["Three bedrooms", "Two full baths", "Large kitchen with island", "Open living and dining", "Walk-in closets", "Private patio or balcony"], waitlistUnit: 34251302 },
  };

  // num = building + floor + position. e.g. 4315 = Building 4, Floor 3, home 15.
  const U = (num, plan, rent, avail, unitId) => ({ num: String(num), plan, rent, avail, unitId,
    bldg: +String(num)[0], floor: +String(num)[1], slot: String(num).slice(2) });
  const UNITS = [
    U(2209, "shortleaf", 1275, null,         34251103),
    U(4109, "shortleaf", 1295, "2026-12-31", 34251306),
    U(4309, "shortleaf", 1275, "2026-12-31", 34251308),
    U(6111, "longleaf",  1355, null,         34251109),
    U(5311, "longleaf",  1360, null,         34251107),
    U(2111, "longleaf",  1370, "2027-01-14", 34251326),
    U(7311, "longleaf",  1360, null,         34251115),
    U(3111, "longleaf",  1370, "2026-12-31", 34251330),
    U(4111, "longleaf",  1370, null,         34251334),
    U(2205, "slash",     1399, "2026-11-06", 34251360),
    U(3113, "slash",     1419, "2027-01-01", 34251380),
    U(6307, "slash",     1434, "2027-01-01", 34251265),
    U(4315, "slash",     1449, null,         34251219),
    U(1115, "slash",     1454, "2026-12-26", 34251177),
    U(2217, "slash",     1399, "2026-12-08", 34251364),
    U(4417, "slash",     1419, "2026-12-01", 34251226),
    U(3201, "slash",     1399, "2027-01-02", 34251186),
    U(5405, "slash",     1419, null,         34251246),
    U(3305, "slash",     1399, null,         34251193),
  ];

  // Site map overlay. Coordinates are in the 2000 x 1500 space of the site plan image.
  const BUILDINGS = [
    { n: 1, cx: 1032, cy: 399,  pts: "862,468 858,328 1206,330 1206,468 867,471" },
    { n: 2, cx: 1521, cy: 397,  pts: "1349,328 1688,329 1694,340 1695,466 1347,466" },
    { n: 3, cx: 1725, cy: 744,  pts: "1717,515 1718,867 1862,865 1859,517" },
    { n: 4, cx: 1607, cy: 1053, pts: "1437,1083 1702,903 1779,1019 1507,1203 1490,1200 1435,1122" },
    { n: 5, cx: 1124, cy: 1124, pts: "953,1056 1296,1054 1295,1194 953,1195" },
    { n: 6, cx: 640,  cy: 1123, pts: "467,1052 466,1195 814,1192 813,1052" },
    { n: 7, cx: 381,  cy: 890,  pts: "311,742 349,717 451,716 449,1064 311,1064" },
  ];
  const FLOORS = [1, 2, 3, 4];

  // Floor plate overlay. Every building uses the same plate; every floor has these ten homes.
  // Rects are in the 1200 x 900 space of the plate image.
  const SLOTS = {
    "03": { plan: "loblolly",  x: 100, y: 285, w: 222, h: 155, label: "03" },
    "07": { plan: "slash",     x: 345, y: 283, w: 172, h: 140, label: "07" },
    "11": { plan: "longleaf",  x: 520, y: 250, w: 125, h: 170, label: "11" },
    "15": { plan: "slash",     x: 648, y: 283, w: 172, h: 140, label: "15" },
    "19": { plan: "loblolly",  x: 848, y: 285, w: 222, h: 155, label: "19" },
    "01": { plan: "slash",     x: 165, y: 442, w: 160, h: 163, label: "01" },
    "05": { plan: "slash",     x: 345, y: 470, w: 172, h: 135, label: "05" },
    "09": { plan: "shortleaf", x: 520, y: 470, w: 125, h: 160, label: "09" },
    "13": { plan: "slash",     x: 648, y: 470, w: 172, h: 135, label: "13" },
    "17": { plan: "slash",     x: 850, y: 442, w: 160, h: 163, label: "17" },
  };

  const DEALS = [
    { month: "2026-10", title: "October move-in", lines: ["Pay only $31 for October rent", "November rent waived", "$300 Amazon gift card issued in December"],
      calc: (r) => [31, 0, r] },
    { month: "2026-11", title: "November move-in", lines: ["$1,000 off November rent", "$500 Amazon gift card issued in December"],
      calc: (r) => [Math.max(0, r - 1000), r, r] },
    { month: "2026-12", title: "December move-in", lines: ["January rent half off", "$500 Amazon gift card"],
      calc: (r) => [r, Math.round(r / 2), r] },
  ];

  // Room hotspots on each plan render (percent of image width / height) with the matching real photo.
  const PH = {
    kitchen2: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(10).png", range2: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(9).png",
    living2a: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(6).png", living2b: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(8).png",
    living2c: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(5).png", bath2a: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(3).png",
    bed2a: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(2).png", bath2b: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(1).png",
    bed2b: "luxe-park%202br,%202ba%20-%201,109sf%20-%20bathroom%202%20(4).png",
    kitchen3: "luxe-park-3br,%202ba%20-%201,386sf%20-%20bathroom%202%20(1).png", living3a: "luxe-park-3br,%202ba%20-%201,386sf%20-%20bathroom%202%20(3).png",
    bath3: "luxe-park-3br,%202ba%20-%201,386sf%20-%20bathroom%202%20(2).png", living3b: "luxe-park-3br,%202ba%20-%201,386sf%20-%20bathroom%202%20(4).png",
    living3c: "luxe-park-3br,%202ba%20-%201,386sf%20-%20bathroom%202%20(5).png", living3d: "luxe-park-3br,%202ba%20-%201,386sf%20-%20bathroom%202%20(6).png",
    pool: "luxe%20park%20swimming%20pool.png",
  };
  const H = (x, y, label, note, photo) => ({ x, y, label, note, photo });
  const HOTSPOTS = {
    shortleaf: [H(66, 13, "Private balcony", "Your own outdoor seat off the living room", PH.pool), H(68, 32, "Living room", "Open to the kitchen, high ceilings, ceiling fan", PH.living2a),
      H(70, 52, "Kitchen island", "Granite counters, white cabinets, stainless appliances", PH.kitchen2), H(45, 22, "Bedroom", "Fits a king, large closet beside the bath", PH.bed2a),
      H(40, 40, "Bathroom", "Tub-shower with a wide vanity", PH.bath2a), H(72, 78, "Laundry", "Full-size washer and dryer hookups", PH.range2)],
    longleaf: [H(54, 16, "Private balcony", "Opens straight off the living room", PH.pool), H(52, 38, "Living room", "Room for a sectional and a dining table", PH.living2b),
      H(48, 66, "Kitchen", "Island seating, granite, stainless appliances", PH.kitchen2), H(72, 25, "Bedroom", "Oversized, with a window wall", PH.bed2b),
      H(76, 35, "Walk-in closet", "Walk-in in select homes", PH.bed2a), H(76, 47, "Bathroom", "Tub-shower, wide granite vanity", PH.bath2b), H(77, 70, "Laundry", "Washer and dryer hookups", PH.range2)],
    slash: [H(58, 17, "Private balcony", "Off the living room, between the bedrooms", PH.pool), H(58, 42, "Living room", "Wide open living with the kitchen in view", PH.living2a),
      H(59, 58, "Dining", "A real dining table, not a breakfast nook", PH.living2b), H(42, 72, "Kitchen", "Granite island, white cabinets, stainless", PH.kitchen2),
      H(42, 20, "Bedroom 1", "Its own closet and full bath", PH.bed2a), H(36, 52, "Bath 1", "Tub-shower and vanity", PH.bath2a),
      H(78, 24, "Bedroom 2", "Split from bedroom 1 for privacy", PH.bed2b), H(80, 42, "Walk-in closet", "Walk-in off bedroom 2", PH.bed2b), H(80, 56, "Bath 2", "Second full bath", PH.bath2b), H(85, 68, "Laundry", "Washer and dryer hookups", PH.range2)],
    loblolly: [H(58, 16, "Private balcony", "Off the living room", PH.pool), H(76, 28, "Living room", "The largest living room in the community", PH.living3a),
      H(74, 43, "Dining", "Seats six beside the kitchen", PH.living3c), H(68, 62, "Kitchen", "The biggest kitchen we build, with island", PH.kitchen3),
      H(30, 62, "Primary bedroom", "Private wing with its own bath and closet", PH.living3b), H(32, 42, "Primary closet", "Walk-in closet", PH.living3d),
      H(43, 30, "Bedroom 2", "Window wall and closet", PH.living3d), H(56, 33, "Bedroom 3", "Office, nursery or guest room", PH.living3b),
      H(46, 66, "Two baths", "Back-to-back full baths", PH.bath3), H(85, 62, "Laundry", "Washer and dryer hookups", PH.range2)],
  };
  const PHOTOS = {
    shortleaf: [PH.kitchen2, PH.living2a, PH.bed2a, PH.bath2a, PH.range2],
    longleaf: [PH.living2b, PH.kitchen2, PH.bed2b, PH.bath2b, PH.living2c],
    slash: [PH.kitchen2, PH.living2a, PH.living2b, PH.bed2a, PH.bath2a, PH.bed2b, PH.bath2b, PH.range2, PH.living2c],
    loblolly: [PH.kitchen3, PH.living3a, PH.living3c, PH.living3b, PH.bath3, PH.living3d],
  };
  const PHOTO_NOTE = { shortleaf: "Finishes photographed in a two bedroom. Same kitchen, floors and bath in every home.", longleaf: "Finishes photographed in a two bedroom. Same kitchen, floors and bath in every home.", slash: "Photographed in a finished Slash.", loblolly: "Photographed in a finished Loblolly." };

  const LINKS = {
    phone: "888-546-7290", tel: "tel:+18885467290",
    selfTour: "https://app.tour24now.com/prospect/tour/luxe-park/register/",
    guidedTour: "https://www.luxeparkga.com/scheduletour",
    apply: "https://luxeparkga.securecafe.com/onlineleasing/luxe-park/guestlogin.aspx",
    resident: "https://luxeparkga.securecafe.com/residentservices/luxe-park/userlogin.aspx",
    siteMap: "https://resource.rentcafe.com/image/upload/f_auto,q_auto,w_1600/s3/2/130001/p1591381-luxe-sitemap.jpg",
    plate: "https://resource.rentcafe.com/image/upload/f_auto,q_auto,w_1400/s3/2/130001/p1591381-luxe-fp-1.jpg",
  };

  function applyUrl(u) {
    const d = u.avail ? new Date(u.avail + "T12:00:00") : new Date();
    const mid = `${d.getMonth() + 1}/${d.getDate()}/${d.getFullYear()}`;
    return `${OLE}&FloorPlanID=${PLANS[u.plan].fpId}&UnitID=${u.unitId}&MoveInDate=${mid}`;
  }
  function waitlistUrl(plan) {
    const p = PLANS[plan]; const d = new Date();
    return `${OLE}&FloorPlanID=${p.fpId}&UnitID=${p.waitlistUnit || 34251300}&MoveInDate=${d.getMonth() + 1}/${d.getDate()}/${d.getFullYear()}`;
  }

  return { IMG, UPDATED, PLANS, UNITS, BUILDINGS, FLOORS, SLOTS, DEALS, LINKS, HOTSPOTS, PHOTOS, PHOTO_NOTE, applyUrl, waitlistUrl };
})();
