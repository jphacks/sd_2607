import { bounds, latLng, latLngBounds, point, type LatLng, type LatLngBounds, type LatLngExpression } from 'leaflet';
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { AttributionControl, Circle, CircleMarker, MapContainer, Popup, Rectangle, TileLayer, useMap, useMapEvents, ZoomControl } from 'react-leaflet';
import type { Area, Post } from './mosimo';

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

export function postBounds(post: Post): LatLngBounds | undefined {
  const b = latLngBounds([]);
  if (post.origin) b.extend(post.origin);
  for (const a of post.areas) b.extend(areaBounds(a));
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
    const b = selected && postBounds(selected);
    // While the list follows the map (表示範囲内のみ), moving would refilter it under the operator.
    if (!selected || !b || inView) return;
    const { center, zoom } = dest.current ?? { center: map.getCenter(), zoom: map.getZoom() };
    const c = map.project(center, zoom), half = map.getSize().divideBy(2).subtract([MARGIN, MARGIN]);
    const inner = bounds(c.subtract(half), c.add(half));
    const at = (ll: LatLngExpression) => map.project(ll, zoom);
    // A post already on screen leaves the camera where the operator put it: no pan, no zoom.
    // geo §1: the origin and the areas are independent, so each must be on screen.
    const seen = (!selected.origin || inner.contains(at(selected.origin)))
      && selected.areas.every(a => { const ab = areaBounds(a); return inner.intersects(bounds(at(ab.getSouthWest()), at(ab.getNorthEast()))); });
    if (zoom >= CITY && seen) return;
    // Otherwise bring it in once: zoom out only as far as the whole post needs, never in (except framing from country scale).
    const fit = map.getBoundsZoom(b, false, point(2 * MARGIN, 2 * MARGIN));
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

// The top SVG shape swallows clicks, so pick by geometry: pins under the cursor first, else every area containing it.
function Pick({ posts, selected, meta, onSelect }: { posts: Post[]; selected?: Post; meta: (p: Post) => ReactNode; onSelect: (uri: string) => void }) {
  const [choice, setChoice] = useState<{ at: LatLng; posts: Post[] }>();
  const map = useMapEvents({
    click: ({ latlng, containerPoint }) => {
      const pins = posts.filter(p => p.origin && map.latLngToContainerPoint(p.origin).distanceTo(containerPoint) <= 12);
      const hits = pins.length ? pins : posts.filter(p => p.areas.some(a =>
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
      {shown.flatMap(p => {
        const pick = p === selected;
        const color = colorOf(p.uri);
        const area = { color, weight: pick ? 3 : 2, dashArray: pick ? undefined : '6 4', fillOpacity: pick ? 0.2 : 0.08 };
        return [
          ...p.areas.map((a, i) =>
            a.shape === 'circle' ? (
              <Circle key={`${p.uri}#a${i}`} center={a.center} radius={a.radiusMeters} pathOptions={area} />
            ) : (
              <Rectangle key={`${p.uri}#a${i}`} bounds={a.bounds} pathOptions={area} />
            ),
          ),
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
