import { latLng, latLngBounds, type LatLng, type LatLngBounds } from 'leaflet';
import { useEffect, useState } from 'react';
import { Circle, CircleMarker, MapContainer, Popup, Rectangle, TileLayer, useMap, useMapEvents } from 'react-leaflet';
import type { Post } from './mosimo';

export function postBounds(post: Post): LatLngBounds | undefined {
  const b = latLngBounds([]);
  if (post.origin) b.extend(post.origin);
  for (const a of post.areas) b.extend(a.shape === 'rect' ? a.bounds : latLng(a.center).toBounds(a.radiusMeters * 2));
  return b.isValid() ? b : undefined;
}

function Sync({ selected, onBounds }: { selected?: Post; onBounds: (b: LatLngBounds) => void }) {
  const map = useMapEvents({ moveend: () => onBounds(map.getBounds()) });
  useEffect(() => onBounds(map.getBounds()), [map, onBounds]);
  useEffect(() => {
    const b = selected && postBounds(selected);
    if (b) map.flyToBounds(b, { maxZoom: 15, padding: [40, 40], duration: 1 });
  }, [map, selected?.uri]); // eslint-disable-line react-hooks/exhaustive-deps -- fly only when the selection changes
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

// Stable per-post hue so a pin and its areas read as one post.
const colorOf = (uri: string) => {
  let h = 0;
  for (const c of uri) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return `hsl(${h % 360}, 80%, 40%)`;
};

// The top SVG shape swallows clicks, so pick by geometry: pins under the cursor first, else every area containing it.
function Pick({ posts, onSelect }: { posts: Post[]; onSelect: (uri: string) => void }) {
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
  return choice && (
    <Popup position={choice.at}>
      <div className="mb-1 text-dns-14B-130">この地点の投稿（{choice.posts.length}件）</div>
      <ul className="max-h-60 space-y-1 overflow-y-auto">
        {choice.posts.map(p => (
          <li key={p.uri}>
            <button type="button" onClick={() => { setChoice(undefined); onSelect(p.uri); }} className="flex w-full items-start gap-2 rounded-4 px-1 py-0.5 text-left hover:bg-solid-gray-50">
              <span aria-hidden className="mt-1 size-3 shrink-0 rounded-full" style={{ background: colorOf(p.uri) }} />
              <span className="line-clamp-2 text-dns-14N-130">{p.text}</span>
            </button>
          </li>
        ))}
      </ul>
    </Popup>
  );
}

export default function MapView({ posts, selected, onSelect, onBounds }: {
  posts: Post[];
  selected?: Post;
  onSelect: (uri: string) => void;
  onBounds: (b: LatLngBounds) => void;
}) {
  const shown = selected && !posts.includes(selected) ? [...posts, selected] : posts;
  return (
    <MapContainer center={[37.5, 138]} zoom={5} className="h-full min-h-80 w-full">
      <TileLayer
        url="https://cyberjapandata.gsi.go.jp/xyz/std/{z}/{x}/{y}.png"
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
          // Key flips on selection so the pin remounts last in its pane, on top of any pins stacked at the same spot.
          p.origin && (
            <CircleMarker key={`${p.uri}#o${pick ? '!' : ''}`} pane="markerPane" center={p.origin} radius={pick ? 9 : 6} pathOptions={{ color: pick ? '#000' : '#fff', weight: 2, fillColor: color, fillOpacity: 1 }} />
          ),
        ];
      })}
      <Pick posts={shown} onSelect={onSelect} />
      <Sync selected={selected} onBounds={onBounds} />
      <Invalidate />
    </MapContainer>
  );
}
