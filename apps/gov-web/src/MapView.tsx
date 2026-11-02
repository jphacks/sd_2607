import { latLng, latLngBounds, type LatLngBounds } from 'leaflet';
import { useEffect } from 'react';
import { Circle, CircleMarker, MapContainer, Rectangle, TileLayer, useMap, useMapEvents } from 'react-leaflet';
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

const KEY = '#0031d8';
const PICK = '#c74700';

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
        maxZoom={18}
      />
      {shown.flatMap(p => {
        const color = p === selected ? PICK : KEY;
        const weight = p === selected ? 3 : 1.5;
        const click = { click: () => onSelect(p.uri) };
        return [
          ...p.areas.map((a, i) =>
            a.shape === 'circle' ? (
              <Circle key={`${p.uri}#a${i}`} center={a.center} radius={a.radiusMeters} pathOptions={{ color, weight, dashArray: '6 4', fillOpacity: 0.08 }} eventHandlers={click} />
            ) : (
              <Rectangle key={`${p.uri}#a${i}`} bounds={a.bounds} pathOptions={{ color, weight, dashArray: '6 4', fillOpacity: 0.08 }} eventHandlers={click} />
            ),
          ),
          p.origin && (
            <CircleMarker key={`${p.uri}#o`} center={p.origin} radius={p === selected ? 9 : 6} pathOptions={{ color: p === selected ? '#000' : '#fff', weight: 2, fillColor: color, fillOpacity: 1 }} eventHandlers={click} />
          ),
        ];
      })}
      <Sync selected={selected} onBounds={onBounds} />
      <Invalidate />
    </MapContainer>
  );
}
