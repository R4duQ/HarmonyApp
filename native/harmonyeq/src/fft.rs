//! In-place iterative radix-2 FFT of a fixed size, with tables built once.

use crate::design::PI;

pub struct Fft {
    size: usize,
    cos: Vec<f32>,
    sin: Vec<f32>,
    bit_reverse: Vec<u32>,
}

impl Fft {
    pub fn new(size: usize) -> Self {
        assert!(size.is_power_of_two() && size >= 2);
        let log2 = size.trailing_zeros();
        let cos = (0..size / 2).map(|k| (2.0 * PI * k as f64 / size as f64).cos() as f32).collect();
        let sin = (0..size / 2).map(|k| (2.0 * PI * k as f64 / size as f64).sin() as f32).collect();
        let bit_reverse = (0..size as u32)
            .map(|i| (0..log2).fold(0u32, |r, b| r | (((i >> b) & 1) << (log2 - 1 - b))))
            .collect();
        Fft { size, cos, sin, bit_reverse }
    }

    /// Transforms `re`/`im` (each `size` long) in place. No allocation.
    pub fn forward(&self, re: &mut [f32], im: &mut [f32]) {
        let n = self.size;
        let (re, im) = (&mut re[..n], &mut im[..n]);
        for i in 0..n {
            let j = self.bit_reverse[i] as usize;
            if j > i {
                re.swap(i, j);
                im.swap(i, j);
            }
        }
        let mut length = 2;
        while length <= n {
            let half = length >> 1;
            let step = n / length;
            for start in (0..n).step_by(length) {
                for k in 0..half {
                    let wr = self.cos[k * step];
                    let wi = -self.sin[k * step];
                    let (a, b) = (start + k, start + k + half);
                    let tr = re[b] * wr - im[b] * wi;
                    let ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
            length <<= 1;
        }
    }
}
