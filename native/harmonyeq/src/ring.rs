//! Lock-free single-producer / single-consumer ring of mono samples.
//!
//! The producer (audio thread) never blocks: when the consumer falls behind
//! (screen off, nothing polling), new samples are dropped instead. The slots
//! are relaxed atomics, which compile to plain loads and stores on ARM and
//! x86, so the ring is safe Rust at no cost; the indices carry the
//! acquire/release ordering, as in the C++ original.

use std::sync::atomic::{AtomicU32, Ordering};

pub const CAPACITY: u32 = 16384; // power of two
const MASK: u32 = CAPACITY - 1;

#[repr(align(64))]
struct Padded(AtomicU32);

pub struct SpscRing {
    write: Padded,
    read: Padded,
    data: Box<[AtomicU32]>,
}

impl Default for SpscRing {
    fn default() -> Self {
        SpscRing {
            write: Padded(AtomicU32::new(0)),
            read: Padded(AtomicU32::new(0)),
            data: (0..CAPACITY).map(|_| AtomicU32::new(0)).collect(),
        }
    }
}

impl SpscRing {
    /// Producer side. Returns how many samples were stored.
    pub fn push(&self, samples: &[f32]) -> u32 {
        let write = self.write.0.load(Ordering::Relaxed);
        let read = self.read.0.load(Ordering::Acquire);
        let room = CAPACITY - write.wrapping_sub(read);
        let n = (samples.len() as u32).min(room);
        for i in 0..n {
            self.data[(write.wrapping_add(i) & MASK) as usize].store(samples[i as usize].to_bits(), Ordering::Relaxed);
        }
        self.write.0.store(write.wrapping_add(n), Ordering::Release);
        n
    }

    /// Consumer side. Returns how many samples were copied into `out`.
    pub fn pop(&self, out: &mut [f32]) -> u32 {
        let read = self.read.0.load(Ordering::Relaxed);
        let write = self.write.0.load(Ordering::Acquire);
        let n = write.wrapping_sub(read).min(out.len() as u32);
        for i in 0..n {
            out[i as usize] = f32::from_bits(self.data[(read.wrapping_add(i) & MASK) as usize].load(Ordering::Relaxed));
        }
        self.read.0.store(read.wrapping_add(n), Ordering::Release);
        n
    }

    /// Consumer side: drop everything currently stored.
    pub fn discard(&self) {
        self.read.0.store(self.write.0.load(Ordering::Acquire), Ordering::Release);
    }
}
