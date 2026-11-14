import { bounds, latLng, latLngBounds, point, type LatLng, type LatLngBounds, type LatLngExpression, type LatLngTuple, type PathOptions } from 'leaflet';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AttributionControl, Circle, CircleMarker, MapContainer, Pane, Polygon, Polyline, Popup, TileLayer, useMap, useMapEvents, ZoomControl } from 'react-leaflet';
import { geohashOutline, type Area, type Post } from './mosimo';

// VITE_MAP_BOUNDS="south,west,north,east" (degrees) sets the initial view, e.g. the office's area; unset or invalid keeps all of Japan.
// Same rule as mapBounds in default.nix: four decimals, south < north within ±90, west < east within ±180.
const BOUNDS = (() => {
  const s = String(import.meta.env.VITE_MAP_BOUNDS ?? '');
  const [south, west, north, east] = s.split(',').map(Number);
  return /^(-?(0|[1-9]\d*)(\.\d+)?,){3}-?(0|[1-9]\d*)(\.\d+)?$/.test(s) && -90 <= south && south < north && north <= 90 && -180 <= west && west < east && east <= 180
    ? latLngBounds([south, west], [north, east]) : undefined;
})();
const VIEW = BOUNDS ? { bounds: BOUNDS } : { center: latLng(37.5, 138), zoom: 5 };

const areaBounds = (a: Area) => (a.shape === 'rect' ? latLngBounds(a.bounds) : latLng(a.center).toBounds(a.radiusMeters * 2));

// Each part on its own for 表示範囲内のみ: one box around far-apart parts (Tokyo's Ogasawara cells) would claim the land between.
export const postParts = (post: Post): LatLngBounds[] =>
  [...(post.origin ? [latLngBounds([post.origin, post.origin])] : []), ...post.areas.map(areaBounds)];

// The part of an approximate cover a pick frames: the groups of touching cells at least half the size of the largest (the apps
// anchor a union's pin in its largest part), so a prefecture's far islands (Tokyo's Ogasawara) do not frame the open ocean,
// while Tokyo's mainland and Izu, twelve cells each, stay together. An author's own regions are never dropped.
// ponytail: O(cells²) on each pick, fine for a prefecture's ≤64 cells; a grid flood fill if posts carry thousands.
function mainCells(post: Post): LatLngBounds | undefined {
  const left = new Set(post.areas.flatMap(a => (a.shape === 'rect' ? [areaBounds(a)] : [])));
  const parts: LatLngBounds[][] = [];
  while (left.size) {
    const [first] = left;
    left.delete(first);
    const part = [first];
    for (let i = 0; i < part.length; i++) for (const c of left) if (c.intersects(part[i])) { part.push(c); left.delete(c); }
    parts.push(part);
  }
  const b = latLngBounds([]), big = Math.max(...parts.map(p => p.length));
  for (const p of parts) if (p.length * 2 >= big) for (const c of p) b.extend(c);
  return b.isValid() ? b : undefined;
}

// The rows and popup sit in scroll boxes that clip an outside focus ring, so draw it inside.
export const insetFocus = 'focus-visible:-outline-offset-4 focus-visible:ring-6 focus-visible:ring-inset';

// How far inside the edge (px) a post must sit to count as on screen; also keeps it clear of the zoom buttons.
const MARGIN = 40;
// Below CITY every pin overlaps, so a pick (a shared ?uri= link included) frames the post at up to FRAME.
const CITY = 10, FRAME = 14;

function Sync({ selected, inView, onBounds }: { selected?: Post; inView: boolean; onBounds: (b: LatLngBounds) => void }) {
  // Where a running pan or flight will stop, so a pick made mid-flight is judged against that view.
  const dest = useRef<{ center: LatLng; zoom: number }>(undefined);
  const map = useMapEvents({
    moveend: () => {
      dest.current = undefined;
      // A flight cut short by a drag stops between zoom levels, where tiles blur; settle on the nearest level.
      if (map.getZoom() % 1) map.setZoom(Math.round(map.getZoom()));
      onBounds(map.getBounds());
    },
  });
  useEffect(() => onBounds(map.getBounds()), [map, onBounds]);
  useEffect(() => {
    // While the list follows the map (表示範囲内のみ), moving would refilter it under the operator.
    if (!selected || inView) return;
    // An approximate cover (a BSAF prefecture) is one shape; an author's own regions each count (geo §1).
    const cover = selected.areaApprox ? mainCells(selected) : undefined;
    const exact = cover ? selected.areas.filter(a => a.shape === 'circle') : selected.areas;
    const b = latLngBounds([]);
    if (selected.origin) b.extend(selected.origin);
    for (const a of exact) b.extend(areaBounds(a));
    if (cover) b.extend(cover);
    if (!b.isValid()) return;
    const { center, zoom } = dest.current ?? { center: map.getCenter(), zoom: map.getZoom() };
    const c = map.project(center, zoom), half = map.getSize().divideBy(2).subtract([MARGIN, MARGIN]);
    const inner = bounds(c.subtract(half), c.add(half));
    const at = (ll: LatLngExpression) => map.project(ll, zoom);
    const box = (ll: LatLngBounds) => bounds(at(ll.getSouthWest()), at(ll.getNorthEast()));
    const onScreen = (a: Area) => inner.intersects(box(areaBounds(a)));
    // A post already on screen leaves the camera where the operator put it: no pan, no zoom.
    // geo §1: the origin and the areas are independent, so each must be on screen. A cover at city zoom: any cell over the
    // streets in view will do; further out a sliver of a prefecture is no view of it, so all of it.
    const seen = (!selected.origin || inner.contains(at(selected.origin))) && exact.every(onScreen)
      && (!cover || (zoom >= CITY ? selected.areas.some(a => a.shape === 'rect' && onScreen(a)) : inner.contains(box(cover))));
    const fit = map.getBoundsZoom(b, false, point(2 * MARGIN, 2 * MARGIN));
    // Pins stand apart only from CITY. A post too big to frame there (a prefecture) is in view once it fills half the screen
    // (one level below its fit), so a neighbouring prefecture already on screen does not zoom in; from the national view it is framed.
    if (seen && zoom >= Math.min(CITY, fit - 1)) return;
    // Otherwise bring it in once: zoom out only as far as the whole post needs, never in (except framing from country scale).
    const to = b.getCenter(), z = zoom < CITY ? Math.min(fit, FRAME) : Math.round(Math.min(zoom, fit));
    const near = z === map.getZoom() && map.getSize().contains(map.project(to).subtract(map.project(map.getCenter())));
    // A hop within a screen pans; anything further flies, whose brief zoom-out keeps the operator oriented.
    if (near) map.panTo(to, { duration: 0.5 }); else map.flyTo(to, z, { duration: 1 });
    dest.current = { center: to, zoom: z }; // after the call: stopping the previous animation fires its moveend
  }, [map, selected?.uri]); // eslint-disable-line react-hooks/exhaustive-deps -- move only when the selection changes
  return null;
}

function Invalidate() {
  // The grid cell can resize after mount (fonts, mobile stacking); keep Leaflet's size in step.
  const map = useMap();
  useEffect(() => {
    const ro = new ResizeObserver(() => map.invalidateSize());
    ro.observe(map.getContainer());
    return () => ro.disconnect();
  }, [map]);
  return null;
}

// Stable per-post hue so a pin and its areas read as one post. A fixed OKLCH lightness keeps every hue,
// yellows included, at 4.4:1 or more against the pale map (WCAG 1.4.11 asks 3:1).
const colorOf = (uri: string) => {
  let h = 0;
  for (const c of uri) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return `oklch(50% 0.15 ${h % 360})`;
};

type Cells = { key: string; fill: LatLngTuple[][][]; line: LatLngTuple[][] };
const cellCache = new WeakMap<Post, Cells | null>();
// A geohash union is drawn as the apps draw it: one fill without inner edges plus one outline of its perimeter, so a prefecture
// reads as one shape, not a grid. Built once per post: fresh arrays would make react-leaflet re-project it on every render.
function cellsOf(post: Post): Cells | undefined {
  if (!cellCache.has(post)) {
    const rects = post.areas.flatMap(a => (a.shape === 'rect' ? [a] : []));
    cellCache.set(post, rects.length ? {
      key: rects.map(r => r.hash).join(),
      fill: rects.map(({ bounds: [[s, w], [n, e]] }) => [[[s, w], [s, e], [n, e], [n, w]]]),
      line: geohashOutline(rects.map(r => r.bounds)),
    } : null);
  }
  return cellCache.get(post) ?? undefined;
}

// Unselected approximate ranges (BSAF prefecture covers, dozens across Japan) share one light, solid tone under the other shapes.
const COVER: PathOptions = { color: 'oklch(45% 0.06 255)', weight: 1, opacity: 0.6, fillOpacity: 0.06 };
const COVER_FILL: PathOptions = { ...COVER, stroke: false, fillRule: 'nonzero' };

// The top SVG shape swallows clicks, so pick by geometry: pins under the cursor first, else every area containing it.
function Pick({ posts, selected, meta, onSelect }: { posts: Post[]; selected?: Post; meta: (p: Post) => ReactNode; onSelect: (uri: string) => void }) {
  const [choice, setChoice] = useState<{ at: LatLng; posts: Post[] }>();
  const map = useMapEvents({
    click: ({ latlng, containerPoint }) => {
      const pins = posts.filter(p => p.origin && map.latLngToContainerPoint(p.origin).distanceTo(containerPoint) <= 12);
      // From CITY a prefecture cover fills the screen, and a click on the streets is not aimed at it; pick those from the list.
      const hits = pins.length ? pins : posts.filter(p => (!p.areaApprox || map.getZoom() < CITY) && p.areas.some(a =>
        a.shape === 'circle' ? map.distance(a.center, latlng) <= a.radiusMeters : latLngBounds(a.bounds).contains(latlng)));
      setChoice(hits.length > 1 ? { at: latlng, posts: hits } : undefined);
      if (hits.length === 1) onSelect(hits[0].uri);
    },
  });
  // A pick made elsewhere (the list) closes the popup.
  useEffect(() => setChoice(undefined), [selected?.uri]);
  // react-leaflet re-runs the popup's autoPan whenever its children change, and the app re-renders on every moveend,
  // so fresh JSX here would drag the camera back to the popup after each pan. Build it once per opening.
  return useMemo(() => choice && (
    <Popup position={choice.at}>
      <div className="mb-1 text-dns-14B-130">この地点の投稿（{choice.posts.length}件）</div>
      <ul className="max-h-60 space-y-1 overflow-y-auto">
        {choice.posts.map(p => (
          <li key={p.uri}>
            <button
              type="button"
              aria-current={p === selected || undefined}
              onClick={() => { setChoice(undefined); onSelect(p.uri); }}
              className={`flex w-full items-start gap-2 rounded-4 px-1 py-0.5 text-left ${insetFocus} ${p === selected ? 'bg-key-50' : 'hover:bg-solid-gray-50'}`}
            >
              <span aria-hidden className="mt-1 size-3 shrink-0 rounded-full" style={{ background: colorOf(p.uri) }} />
              <span className="min-w-0">
                <span className="line-clamp-2 text-dns-14N-130">{p.text}</span>
                <span className="mt-0.5 flex flex-wrap items-center gap-1 text-dns-14N-130 text-solid-gray-600">{meta(p)}</span>
              </span>
            </button>
          </li>
        ))}
      </ul>
    </Popup>
  ), [choice]); // eslint-disable-line react-hooks/exhaustive-deps -- selected/meta/onSelect as of the opening serve the popup's lifetime
}

export default function MapView({ posts, selected, inView, onSelect, onBounds, meta }: {
  posts: Post[];
  selected?: Post;
  inView: boolean;
  onSelect: (uri: string) => void;
  onBounds: (b: LatLngBounds) => void;
  meta: (p: Post) => ReactNode;
}) {
  const shown = selected && !posts.includes(selected) ? [...posts, selected] : posts;
  // Many posts name the same prefecture; its cover is drawn once.
  const covers = new Map<string, Cells>();
  for (const p of shown) { const c = p.areaApprox && p !== selected && cellsOf(p); if (c) covers.set(c.key, c); }
  return (
    <MapContainer {...VIEW} zoomControl={false} attributionControl={false} className="h-full min-h-80 w-full">
      <ZoomControl zoomInTitle="拡大" zoomOutTitle="縮小" />
      <AttributionControl prefix='<a href="https://leafletjs.com">Leaflet</a>' />
      <TileLayer
        url="https://cyberjapandata.gsi.go.jp/xyz/pale/{z}/{x}/{y}.png"
        attribution='<a href="https://maps.gsi.go.jp/development/ichiran.html">国土地理院</a>'
        className="grayscale"
        maxZoom={18}
      />
      {/* Covers are never interactive (a creation option, not a style): Pick finds them by geometry, and from CITY, where it
          ignores them, a cover is the whole screen and must not show a pointer. */}
      <Pane name="covers" style={{ zIndex: '350' }}>
        {[...covers.values()].flatMap(c => [
          <Polygon key={`${c.key}#f`} positions={c.fill} pathOptions={COVER_FILL} interactive={false} />,
          <Polyline key={`${c.key}#l`} positions={c.line} pathOptions={COVER} interactive={false} />,
        ])}
      </Pane>
      {shown.flatMap(p => {
        const pick = p === selected;
        const color = colorOf(p.uri);
        const area = { color, weight: pick ? 3 : 2, dashArray: pick ? undefined : '6 4', fillOpacity: pick ? 0.2 : 0.08 };
        const cells = (pick || !p.areaApprox) && cellsOf(p);
        return [
          ...p.areas.map((a, i) => a.shape === 'circle' && (
            <Circle key={`${p.uri}#a${i}`} center={a.center} radius={a.radiusMeters} pathOptions={area} />
          )),
          cells && <Polygon key={`${p.uri}#f`} positions={cells.fill} pathOptions={{ ...area, stroke: false, fillRule: 'nonzero' }} interactive={!p.areaApprox} />,
          cells && <Polyline key={`${p.uri}#l`} positions={cells.line} pathOptions={area} interactive={!p.areaApprox} />,
          // A ring in the list's selection blue (key-800) finds the pick inside a dense cluster.
          pick && p.origin && (
            <CircleMarker key={`${p.uri}#h`} pane="markerPane" center={p.origin} radius={16} interactive={false} pathOptions={{ color: '#0031d8', weight: 3, fillColor: '#0031d8', fillOpacity: 0.15 }} />
          ),
          // Key flips on selection so the pin remounts last in its pane, on top of any pins stacked at the same spot.
          p.origin && (
            <CircleMarker key={`${p.uri}#o${pick ? '!' : ''}`} pane="markerPane" center={p.origin} radius={pick ? 9 : 6} pathOptions={{ color: pick ? '#000' : '#fff', weight: 2, fillColor: color, fillOpacity: 1 }} />
          ),
        ];
      })}
      <Pick posts={shown} selected={selected} meta={meta} onSelect={onSelect} />
      <Sync selected={selected} inView={inView} onBounds={onBounds} />
      <Invalidate />
    </MapContainer>
  );
}
