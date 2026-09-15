#!/usr/bin/env python3
"""Generate the Seafile Sync app icon from a single source of truth.

    python3 art/generate.py

Emits the SVG masters in art/, the VectorDrawables in app/src/main/res/drawable/, and every
launcher raster in app/src/main/res/mipmap-*/. Run it only when the icon changes; it is not
part of the Gradle build. Needs rsvg-convert (librsvg) and Pillow.

The mark is a cloud above a breaking wave, in the family of the official Seafile logo but drawn
from scratch. The wave is a numerically sampled ribbon over a bezier-plus-spiral centreline,
which is why this is generated rather than hand-written XML.

Two constraints the geometry has to respect, both learned by rendering it wrong first:

  * Every subpath winds clockwise. Mixed winding makes fillType="nonZero" subtract overlapping
    puffs instead of unioning them into one cloud.
  * The adaptive safe zone is a 66dp CIRCLE, not a 66dp square. Shapes are fitted by maximum
    radius about the icon centre, or the wave's tail and curl get clipped by a circle mask.

Path data stays inside the M/L/A/Z subset that SVG and VectorDrawable both understand, so the
same strings feed the vectors and the rasters and the two can never drift apart.
"""

import math
import os
import subprocess

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ART = os.path.join(ROOT, "art")
RES = os.path.join(ROOT, "app", "src", "main", "res")

AMBER_TOP, AMBER_BOTTOM = "#FAD956", "#FFA10F"
NAVY_TOP, NAVY_BOTTOM = "#14405F", "#0A2133"

# ------------------------------------------------------------------ primitives


def _fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def circle(cx, cy, r):
    """Clockwise circle, plus sampled points for the fitting maths."""
    d = (f"M{_fmt(cx - r)},{_fmt(cy)}"
         f"A{_fmt(r)},{_fmt(r)} 0 0,1 {_fmt(cx + r)},{_fmt(cy)}"
         f"A{_fmt(r)},{_fmt(r)} 0 0,1 {_fmt(cx - r)},{_fmt(cy)}Z")
    pts = [(cx + r * math.cos(t * math.tau / 48), cy + r * math.sin(t * math.tau / 48))
           for t in range(48)]
    return d, pts


def rrect(x0, y0, x1, y1, r):
    """Clockwise rounded rectangle."""
    d = (f"M{_fmt(x0 + r)},{_fmt(y0)}L{_fmt(x1 - r)},{_fmt(y0)}"
         f"A{_fmt(r)},{_fmt(r)} 0 0,1 {_fmt(x1)},{_fmt(y0 + r)}"
         f"L{_fmt(x1)},{_fmt(y1 - r)}"
         f"A{_fmt(r)},{_fmt(r)} 0 0,1 {_fmt(x1 - r)},{_fmt(y1)}"
         f"L{_fmt(x0 + r)},{_fmt(y1)}"
         f"A{_fmt(r)},{_fmt(r)} 0 0,1 {_fmt(x0)},{_fmt(y1 - r)}"
         f"L{_fmt(x0)},{_fmt(y0 + r)}"
         f"A{_fmt(r)},{_fmt(r)} 0 0,1 {_fmt(x0 + r)},{_fmt(y0)}Z")
    pts = []
    for (ccx, ccy, a0) in ((x1 - r, y0 + r, 270), (x1 - r, y1 - r, 0),
                           (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)):
        for k in range(9):
            a = math.radians(a0 + 90 * k / 8)
            pts.append((ccx + r * math.cos(a), ccy + r * math.sin(a)))
    return d, pts


def bez(p0, p1, p2, p3, n=44):
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u**3 * p0[0] + 3*u*u*t * p1[0] + 3*u*t*t * p2[0] + t**3 * p3[0],
                    u**3 * p0[1] + 3*u*u*t * p1[1] + 3*u*t*t * p2[1] + t**3 * p3[1]))
    return out


def spiral_from(start, a0, a1, r0, r1, n=120, ease=0.75):
    """Spiral whose first sample lands exactly on `start`, so it stitches seamlessly."""
    cx = start[0] - r0 * math.cos(math.radians(a0))
    cy = start[1] - r0 * math.sin(math.radians(a0))
    out = []
    for i in range(n + 1):
        t = i / n
        ang = math.radians(a0 + (a1 - a0) * t)
        r = r0 + (r1 - r0) * (t ** ease)
        out.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    return out


def rdp(pts, eps):
    """Douglas-Peucker, so the sampled outline does not bloat the drawable."""
    if len(pts) < 3:
        return list(pts)
    ax, ay = pts[0]
    bx, by = pts[-1]
    dx, dy = bx - ax, by - ay
    span = math.hypot(dx, dy)
    worst, idx = -1.0, 0
    for i in range(1, len(pts) - 1):
        px, py = pts[i]
        if span < 1e-9:
            dist = math.hypot(px - ax, py - ay)
        else:
            dist = abs(dy * px - dx * py + bx * ay - by * ax) / span
        if dist > worst:
            worst, idx = dist, i
    if worst <= eps:
        return [pts[0], pts[-1]]
    return rdp(pts[:idx + 1], eps)[:-1] + rdp(pts[idx:], eps)


def ribbon(pts, width_of, eps=0.15):
    """Band centred on `pts`; width_of(t) -> half-width at t in [0,1]. Tapers to a point."""
    n = len(pts)
    left, right = [], []
    for i, (x, y) in enumerate(pts):
        t = i / (n - 1)
        if i == 0:
            dx, dy = pts[1][0] - x, pts[1][1] - y
        elif i == n - 1:
            dx, dy = x - pts[-2][0], y - pts[-2][1]
        else:
            dx, dy = pts[i+1][0] - pts[i-1][0], pts[i+1][1] - pts[i-1][1]
        ln = math.hypot(dx, dy) or 1.0
        nx, ny = -dy / ln, dx / ln
        w = width_of(t)
        left.append((x + nx * w, y + ny * w))
        right.append((x - nx * w, y - ny * w))

    left = rdp(left, eps)
    right = rdp(right, eps)
    outline = left + right[::-1]
    d = "M" + f"{_fmt(outline[0][0])},{_fmt(outline[0][1])}"
    d += "".join(f"L{_fmt(x)},{_fmt(y)}" for x, y in outline[1:]) + "Z"
    return d, outline


# ----------------------------------------------------------------------- mark

def wave_profile(peak, t_peak=0.30):
    """0 at the trailing tip, `peak` through the body, 0 at the curl tip."""
    def f(t):
        if t <= t_peak:
            return peak * (t / t_peak) ** 0.55
        u = (t - t_peak) / (1 - t_peak)
        return peak * (1 - u) ** 0.95
    return f


def build(scale=1.0, ox=0.0, oy=0.0, curl_a1=-240.0, curl_r=12.0, wave_w=6.6, eps=0.15):
    """Cloud over a curling wave. Returns (cloud_d, wave_d, all_points)."""
    def P(x, y):
        return (54 + (x - 54) * scale + ox, 54 + (y - 54) * scale + oy)

    def S(v):
        return v * scale

    cloud_d, pts = "", []
    for args in ((61, 40, 14.5), (43.5, 45.5, 10.2)):
        d, p = circle(*P(args[0], args[1]), S(args[2]))
        cloud_d += d
        pts += p
    d, p = rrect(*P(33, 43.5), *P(75.5, 57.5), S(7.0))
    cloud_d += d
    pts += p

    crest = bez(P(23, 78), P(32, 84), P(50, 82), P(62, 70), 46)
    curl = spiral_from(P(62, 70), a0=192, a1=curl_a1, r0=S(curl_r), r1=S(1.6), n=120)
    wave_d, outline = ribbon(crest + curl[1:], wave_profile(S(wave_w)), eps=eps * scale)
    return cloud_d, wave_d, pts + outline


def fit(target_d, **kw):
    """Scale and offset so the mark exactly fills a circle of `target_d` about (54,54)."""
    _, _, pts = build(**kw)
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    ox0, oy0 = 54 - cx, 54 - cy
    _, _, pts = build(ox=ox0, oy=oy0, **kw)
    r1 = max(math.hypot(x - 54, y - 54) for x, y in pts)
    s = (target_d / 2) / r1
    return s, ox0 * s, oy0 * s


def bounds(cloud_d, wave_d, pts):
    ys = [p[1] for p in pts]
    return min(ys), max(ys)


# ------------------------------------------------------------------- emitters

def svg_doc(body, view=108, size=None, bg=None):
    size = size or view * 6
    rect = f'<rect width="{view}" height="{view}" fill="url(#bg)"/>' if bg else ""
    defs = ""
    if bg:
        defs += (f'<linearGradient id="bg" x1="0" y1="0" x2="0" y2="{view}" '
                 f'gradientUnits="userSpaceOnUse">'
                 f'<stop offset="0" stop-color="{bg[0]}"/>'
                 f'<stop offset="1" stop-color="{bg[1]}"/></linearGradient>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {view} {view}" '
            f'width="{size}" height="{size}">'
            f'<defs>{defs}{body[0]}</defs>{rect}{body[1]}</svg>')


def vector_doc(paths, view=108, group_scale=None):
    head = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{view}dp"\n'
            f'    android:height="{view}dp"\n'
            f'    android:viewportWidth="{view}"\n'
            f'    android:viewportHeight="{view}">\n')
    body = "".join(paths)
    if group_scale:
        # Scale by group transform, never by rewriting path text: a naive numeric rescale
        # also hits the arc large-arc/sweep flags and silently corrupts every curve.
        body = (f'    <group android:scaleX="{group_scale:.6f}" '
                f'android:scaleY="{group_scale:.6f}">\n'
                + "".join("    " + line + "\n" for line in body.splitlines())
                + '    </group>\n')
    return head + body + "</vector>\n"


def vpath_flat(d, color):
    return f'    <path\n        android:fillColor="{color}"\n        android:pathData="{d}" />\n'


def vpath_gradient(d, top, bottom, y0, y1):
    return (f'    <path android:pathData="{d}">\n'
            f'        <aapt:attr name="android:fillColor">\n'
            f'            <gradient\n'
            f'                android:type="linear"\n'
            f'                android:startX="0"\n'
            f'                android:startY="{_fmt(y0)}"\n'
            f'                android:endX="0"\n'
            f'                android:endY="{_fmt(y1)}">\n'
            f'                <item android:offset="0" android:color="{top}" />\n'
            f'                <item android:offset="1" android:color="{bottom}" />\n'
            f'            </gradient>\n'
            f'        </aapt:attr>\n'
            f'    </path>\n')


def write(path, text):
    with open(path, "w") as fh:
        fh.write(text)
    print(f"  {os.path.relpath(path, ROOT)}  ({len(text)} bytes)")


def main():
    os.makedirs(ART, exist_ok=True)
    print("geometry")

    # foreground: the mark filling a 68dp circle
    s, ox, oy = fit(68.0)
    cloud, wave, pts = build(scale=s, ox=ox, oy=oy)
    y0, y1 = bounds(cloud, wave, pts)
    r = max(math.hypot(x - 54, y - 54) for x, y in pts)
    print(f"  foreground: scale {s:.4f} offset ({ox:+.3f},{oy:+.3f}) radius {r:.2f}")

    # monochrome: the same silhouette, one dp tighter
    sm, oxm, oym = fit(66.0)
    cloud_m, wave_m, pts_m = build(scale=sm, ox=oxm, oy=oym)
    rm = max(math.hypot(x - 54, y - 54) for x, y in pts_m)
    print(f"  monochrome: scale {sm:.4f} offset ({oxm:+.3f},{oym:+.3f}) radius {rm:.2f}")

    # ---- vector drawables
    print("vector drawables")
    write(os.path.join(RES, "drawable", "ic_launcher_background.xml"),
          vector_doc([vpath_gradient("M0,0h108v108h-108z", NAVY_TOP, NAVY_BOTTOM, 0, 108)]))
    write(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"),
          vector_doc([vpath_gradient(cloud, AMBER_TOP, AMBER_BOTTOM, y0, y1),
                      vpath_gradient(wave, AMBER_TOP, AMBER_BOTTOM, y0, y1)]))
    write(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"),
          vector_doc([vpath_flat(cloud_m, "#FFFFFFFF"), vpath_flat(wave_m, "#FFFFFFFF")]))

    # ---- notification: redrawn at 24dp, bolder wave and a wider curl eye
    # Bolder and less rolled than the launcher mark: at mdpi the tighter 240 degree curl
    # closes its eye into a blob. Chosen by rendering the candidates at 24px.
    NOTIF = dict(curl_a1=-150.0, curl_r=13.5, wave_w=9.0, eps=0.10)
    sn, oxn, oyn = fit(23.0 * 108 / 24, **NOTIF)
    cloud_n, wave_n, _ = build(scale=sn, ox=oxn, oy=oyn, **NOTIF)
    scale24 = 24 / 108

    write(os.path.join(RES, "drawable", "ic_stat_sync.xml"),
          vector_doc([vpath_flat(cloud_n, "#FFFFFFFF"), vpath_flat(wave_n, "#FFFFFFFF")],
                     view=24, group_scale=scale24))

    # ---- svg masters (same path data, so raster and vector cannot drift)
    print("svg masters")
    grad = (f'<linearGradient id="m" x1="0" y1="{_fmt(y0)}" x2="0" y2="{_fmt(y1)}" '
            f'gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{AMBER_TOP}"/>'
            f'<stop offset="1" stop-color="{AMBER_BOTTOM}"/></linearGradient>')
    body = f'<path d="{cloud}" fill="url(#m)"/><path d="{wave}" fill="url(#m)"/>'
    icon_svg = svg_doc((grad, body), bg=(NAVY_TOP, NAVY_BOTTOM))
    write(os.path.join(ART, "icon.svg"), icon_svg)
    write(os.path.join(ART, "icon-mono.svg"),
          svg_doc(("", f'<path d="{cloud_m}" fill="#FFFFFF"/><path d="{wave_m}" fill="#FFFFFF"/>')))
    write(os.path.join(ART, "icon-notification.svg"),
          svg_doc(("", f'<g transform="scale({scale24:.6f})">'
                       f'<path d="{cloud_n}" fill="#FFFFFF"/>'
                       f'<path d="{wave_n}" fill="#FFFFFF"/></g>'), view=24))

    # ---- rasters
    print("rasters")
    master = os.path.join(ART, "icon.svg")
    full = os.path.join(ART, ".render.png")
    subprocess.run(["rsvg-convert", "-w", "1080", "-h", "1080", master, "-o", full], check=True)
    base = Image.open(full).convert("RGBA")
    # the launcher shows the central 72 of 108; crop to it so legacy rasters are not tiny
    c = int(base.size[0] * 72 / 108)
    o = (base.size[0] - c) // 2
    visible = base.crop((o, o, o + c, o + c))

    def masked(px, shape):
        im = visible.resize((px, px), Image.LANCZOS)
        ss = 4
        m = Image.new("L", (px * ss, px * ss), 0)
        d = ImageDraw.Draw(m)
        if shape == "circle":
            d.ellipse((0, 0, px * ss - 1, px * ss - 1), fill=255)
        else:
            d.rounded_rectangle((0, 0, px * ss - 1, px * ss - 1),
                                radius=int(px * ss * 0.22), fill=255)
        im.putalpha(m.resize((px, px), Image.LANCZOS))
        return im

    for bucket, px in (("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
                       ("xxhdpi", 144), ("xxxhdpi", 192)):
        for name, shape in (("ic_launcher", "squircle"), ("ic_launcher_round", "circle")):
            p = os.path.join(RES, f"mipmap-{bucket}", f"{name}.webp")
            masked(px, shape).save(p, "WEBP", lossless=True, quality=100)
            print(f"  {os.path.relpath(p, ROOT)}  {px}px")

    store = visible.resize((512, 512), Image.LANCZOS).convert("RGB")
    store.save(os.path.join(ART, "icon-512.png"), "PNG")
    print(f"  art/icon-512.png  512px opaque")
    masked(256, "squircle").save(os.path.join(ART, "icon-rounded.png"), "PNG")
    print(f"  art/icon-rounded.png  256px")
    os.remove(full)


if __name__ == "__main__":
    main()
