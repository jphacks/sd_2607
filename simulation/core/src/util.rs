//! Geometry, piecewise-linear curves and the knowledge bit set.

use serde::{Deserialize, Serialize};

/// Local planar coordinates in meters (east, north) around the scenario origin.
#[derive(Clone, Copy, Debug, Default, PartialEq, Serialize, Deserialize)]
pub struct Vec2 {
    pub x: f32,
    pub y: f32,
}

impl Vec2 {
    pub const fn new(x: f32, y: f32) -> Self {
        Self { x, y }
    }
    pub fn dist(self, o: Vec2) -> f32 {
        ((self.x - o.x).powi(2) + (self.y - o.y).powi(2)).sqrt()
    }
    pub fn dist2(self, o: Vec2) -> f32 {
        (self.x - o.x).powi(2) + (self.y - o.y).powi(2)
    }
    pub fn lerp(self, o: Vec2, t: f32) -> Vec2 {
        Vec2::new(self.x + (o.x - self.x) * t, self.y + (o.y - self.y) * t)
    }
}

/// Equirectangular projection; accurate to well under 1% across the Noto peninsula (~150 km).
#[derive(Clone, Copy, Debug, Serialize)]
pub struct Projection {
    pub lat0: f64,
    pub lon0: f64,
    pub kx: f64,
    pub ky: f64,
}

impl Projection {
    pub fn new(lat0: f64, lon0: f64) -> Self {
        let ky = 110_574.0;
        let kx = 111_320.0 * lat0.to_radians().cos();
        Self { lat0, lon0, kx, ky }
    }
    pub fn to_xy(&self, p: [f64; 2]) -> Vec2 {
        Vec2::new(((p[1] - self.lon0) * self.kx) as f32, ((p[0] - self.lat0) * self.ky) as f32)
    }
    pub fn to_latlon(&self, v: Vec2) -> [f64; 2] {
        [self.lat0 + v.y as f64 / self.ky, self.lon0 + v.x as f64 / self.kx]
    }
}

/// Piecewise-linear function of time. Points are `[hours, value]`, sorted by time;
/// the value is held constant before the first and after the last point.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(transparent)]
pub struct Curve(pub Vec<[f64; 2]>);

impl Curve {
    pub fn constant(v: f64) -> Self {
        Curve(vec![[0.0, v]])
    }
    /// Value at `t_min` minutes after the quake.
    pub fn at(&self, t_min: f64) -> f64 {
        let h = t_min / 60.0;
        let pts = &self.0;
        match pts.len() {
            0 => 0.0,
            1 => pts[0][1],
            _ => {
                if h <= pts[0][0] {
                    return pts[0][1];
                }
                for w in pts.windows(2) {
                    let (a, b) = (w[0], w[1]);
                    if h <= b[0] {
                        let span = b[0] - a[0];
                        if span <= 0.0 {
                            return b[1];
                        }
                        return a[1] + (b[1] - a[1]) * (h - a[0]) / span;
                    }
                }
                pts[pts.len() - 1][1]
            }
        }
    }
}

/// Dense bit set over message ids with a population count and an order-independent digest,
/// so two holders with the same set can skip an exchange without comparing words.
#[derive(Clone, Default, Debug)]
pub struct BitSet {
    words: Vec<u64>,
    count: u32,
    digest: u64,
}

fn mix(i: u32) -> u64 {
    // splitmix64 finalizer
    let mut z = (i as u64).wrapping_add(0x9E37_79B9_7F4A_7C15);
    z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
    z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
    z ^ (z >> 31)
}

impl BitSet {
    pub fn get(&self, i: u32) -> bool {
        let w = (i / 64) as usize;
        w < self.words.len() && self.words[w] >> (i % 64) & 1 == 1
    }
    /// Returns true if the bit was newly set.
    pub fn insert(&mut self, i: u32) -> bool {
        let w = (i / 64) as usize;
        if w >= self.words.len() {
            self.words.resize(w + 1, 0);
        }
        let m = 1u64 << (i % 64);
        if self.words[w] & m != 0 {
            return false;
        }
        self.words[w] |= m;
        self.count += 1;
        self.digest ^= mix(i);
        true
    }
    pub fn intersects(&self, o: &BitSet) -> bool {
        self.words.iter().zip(&o.words).any(|(a, b)| a & b != 0)
    }
    pub fn count(&self) -> u32 {
        self.count
    }
    pub fn same_as(&self, o: &BitSet) -> bool {
        self.count == o.count && self.digest == o.digest
    }
    /// Ids in `other` (and in `mask`, when given) that are missing from `self`.
    pub fn missing_from(&self, other: &BitSet, mask: Option<&BitSet>, out: &mut Vec<u32>) {
        out.clear();
        for (wi, &ow) in other.words.iter().enumerate() {
            let mut d = ow & !self.words.get(wi).copied().unwrap_or(0);
            if let Some(m) = mask {
                d &= m.words.get(wi).copied().unwrap_or(0);
            }
            while d != 0 {
                let b = d.trailing_zeros();
                out.push(wi as u32 * 64 + b);
                d &= d - 1;
            }
        }
    }
    /// Push up to `k` ids that are in `other` and `mask` but not in `self`, newest (highest id) first.
    pub fn newest_missing(&self, other: &BitSet, mask: &BitSet, from_id: u32, k: usize, out: &mut Vec<u32>) {
        let n = other.words.len().min(mask.words.len());
        for wi in ((from_id / 64) as usize..n).rev() {
            let mut d = other.words[wi] & mask.words[wi] & !self.words.get(wi).copied().unwrap_or(0);
            while d != 0 {
                if out.len() >= k {
                    return;
                }
                let b = 63 - d.leading_zeros();
                out.push(wi as u32 * 64 + b);
                d &= !(1u64 << b);
            }
        }
    }
    pub fn iter(&self) -> impl Iterator<Item = u32> + '_ {
        self.words.iter().enumerate().flat_map(|(wi, &w)| {
            let mut d = w;
            std::iter::from_fn(move || {
                if d == 0 {
                    None
                } else {
                    let b = d.trailing_zeros();
                    d &= d - 1;
                    Some(wi as u32 * 64 + b)
                }
            })
        })
    }
}

/// Merge `b` into sorted, deduplicated `a`, keeping only ids for which `keep` is true.
/// Returns how many ids were added to `a`.
pub fn merge_sorted(a: &mut Vec<u32>, b: &[u32], keep: impl Fn(u32) -> bool) -> usize {
    if b.is_empty() {
        return 0;
    }
    let before = a.len();
    let mut out = Vec::with_capacity(a.len() + b.len());
    let (mut i, mut j) = (0, 0);
    while i < a.len() || j < b.len() {
        let v = if j >= b.len() || (i < a.len() && a[i] < b[j]) {
            i += 1;
            a[i - 1]
        } else if i >= a.len() || b[j] < a[i] {
            j += 1;
            b[j - 1]
        } else {
            i += 1;
            j += 1;
            a[i - 1]
        };
        if keep(v) {
            out.push(v);
        }
    }
    *a = out;
    a.len().saturating_sub(before)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn curve_interpolates_and_clamps() {
        let c = Curve(vec![[0.0, 0.0], [10.0, 1.0]]);
        assert_eq!(c.at(-5.0), 0.0);
        assert!((c.at(300.0) - 0.5).abs() < 1e-9);
        assert_eq!(c.at(6000.0), 1.0);
    }

    #[test]
    fn bitset_digest_matches_for_equal_sets() {
        let mut a = BitSet::default();
        let mut b = BitSet::default();
        for i in [3, 70, 200] {
            a.insert(i);
        }
        for i in [200, 3, 70] {
            b.insert(i);
        }
        assert!(a.same_as(&b));
        let mut miss = Vec::new();
        b.insert(5);
        a.missing_from(&b, None, &mut miss);
        assert_eq!(miss, vec![5]);
    }

    #[test]
    fn merge_sorted_unions_and_filters() {
        let mut a = vec![1, 4, 9];
        let added = merge_sorted(&mut a, &[2, 4, 10, 11], |v| v != 11);
        assert_eq!(a, vec![1, 2, 4, 9, 10]);
        assert_eq!(added, 2);
    }
}
