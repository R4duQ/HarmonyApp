//! Flush-to-zero / denormals-are-zero for the duration of one process call.
//!
//! Decaying IIR tails otherwise fall into denormals, which ARM and x86 handle
//! up to a hundred times slower: exactly when the screen is off and the track
//! fades out. Restored on drop, as the thread is shared with Java code.

pub struct FlushDenormals {
    #[cfg(any(target_arch = "aarch64", target_arch = "x86_64", target_arch = "x86"))]
    saved: u64,
}

impl FlushDenormals {
    #[inline]
    #[allow(clippy::new_without_default)] // a scope guard: always made with new()
    pub fn new() -> Self {
        #[cfg(target_arch = "aarch64")]
        {
            let saved: u64;
            // SAFETY: reading and setting FPCR.FZ (bit 24) only changes how
            // denormals round; it is restored in Drop.
            unsafe {
                std::arch::asm!("mrs {0}, fpcr", out(reg) saved, options(nomem, nostack, preserves_flags));
                std::arch::asm!("msr fpcr, {0}", in(reg) saved | (1 << 24), options(nomem, nostack, preserves_flags));
            }
            FlushDenormals { saved }
        }
        #[cfg(any(target_arch = "x86_64", target_arch = "x86"))]
        {
            let mut csr: u32 = 0;
            // SAFETY: as above, for MXCSR's FTZ (bit 15) and DAZ (bit 6).
            unsafe {
                std::arch::asm!("stmxcsr [{0}]", in(reg) &mut csr, options(nostack, preserves_flags));
                let flushed = csr | 0x8040;
                std::arch::asm!("ldmxcsr [{0}]", in(reg) &flushed, options(nostack, preserves_flags, readonly));
            }
            FlushDenormals { saved: csr as u64 }
        }
        #[cfg(not(any(target_arch = "aarch64", target_arch = "x86_64", target_arch = "x86")))]
        {
            FlushDenormals {}
        }
    }
}

impl Drop for FlushDenormals {
    #[inline]
    fn drop(&mut self) {
        #[cfg(target_arch = "aarch64")]
        // SAFETY: restores the FPCR read in new().
        unsafe {
            std::arch::asm!("msr fpcr, {0}", in(reg) self.saved, options(nomem, nostack, preserves_flags));
        }
        #[cfg(any(target_arch = "x86_64", target_arch = "x86"))]
        {
            let csr = self.saved as u32;
            // SAFETY: restores the MXCSR read in new().
            unsafe {
                std::arch::asm!("ldmxcsr [{0}]", in(reg) &csr, options(nostack, preserves_flags, readonly));
            }
        }
    }
}
