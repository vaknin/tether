//! The Lark receiver's audio as a call sends it: 48 kHz interleaved stereo in, 16 kHz mono out. The
//! path motoparty proved on the road (its `research/MIC.md`, `LarkDsp.kt`): left channel only (Mic1,
//! and in the receiver's default mono left equals right), a 150 Hz high-pass for the wind and the
//! engine, then a Kaiser-windowed low-pass so the hiss above 8 kHz doesn't fold into his voice.

use std::f64::consts::PI;

/// Bytes the app reads per 20 ms: 960 frames of two PCM16 samples.
pub const FRAME_BYTES_IN: usize = 3840;
pub const RATE_IN: u32 = 48_000;

const FACTOR: usize = 3;
const TAPS: usize = 127;
const CUTOFF_HZ: f64 = 6_000.0;
const WIND_HZ: f64 = 150.0;
const BETA: f64 = 8.0;

/// A 2nd-order Butterworth high-pass (RBJ biquad), transposed direct form II.
struct HighPass {
    b: [f64; 3],
    a: [f64; 2],
    z: [f64; 2],
}

impl HighPass {
    fn new(rate: f64, cutoff: f64) -> Self {
        let w0 = 2.0 * PI * cutoff / rate;
        let alpha = w0.sin() / (2.0 * std::f64::consts::FRAC_1_SQRT_2);
        let c = w0.cos();
        let a0 = 1.0 + alpha;
        HighPass { b: [(1.0 + c) / 2.0 / a0, -(1.0 + c) / a0, (1.0 + c) / 2.0 / a0], a: [-2.0 * c / a0, (1.0 - alpha) / a0], z: [0.0; 2] }
    }

    fn step(&mut self, x: f64) -> f64 {
        let y = self.b[0] * x + self.z[0];
        self.z[0] = self.b[1] * x - self.a[0] * y + self.z[1];
        self.z[1] = self.b[2] * x - self.a[1] * y;
        y
    }
}

/// Modified Bessel function of the first kind, order 0.
fn bessel_i0(x: f64) -> f64 {
    let (mut sum, mut term, mut k) = (1.0, 1.0, 1.0);
    while term > 1e-12 * sum {
        let q = x / (2.0 * k);
        term *= q * q;
        sum += term;
        k += 1.0;
    }
    sum
}

fn design() -> [f32; TAPS] {
    let m = (TAPS - 1) as f64;
    let fc = CUTOFF_HZ / RATE_IN as f64;
    let raw: Vec<f64> = (0..TAPS)
        .map(|i| {
            let n = i as f64 - m / 2.0;
            let x = 2.0 * fc * n;
            let sinc = if n == 0.0 { 1.0 } else { (PI * x).sin() / (PI * x) };
            let r = 2.0 * i as f64 / m - 1.0;
            2.0 * fc * sinc * bessel_i0(BETA * (1.0 - r * r).sqrt()) / bessel_i0(BETA)
        })
        .collect();
    let sum: f64 = raw.iter().sum();
    let mut h = [0.0; TAPS];
    for (o, v) in h.iter_mut().zip(&raw) {
        *o = (v / sum) as f32;
    }
    h
}

/// One open of the mic. The filters keep their state across frames.
pub struct Lark {
    wind: HighPass,
    h: [f32; TAPS],
    /// `TAPS - 1` samples of history, then the new ones.
    buf: Vec<f32>,
}

impl Default for Lark {
    fn default() -> Self {
        Lark { wind: HighPass::new(RATE_IN as f64, WIND_HZ), h: design(), buf: vec![0.0; TAPS - 1] }
    }
}

impl Lark {
    /// Interleaved PCM16 LE stereo in, PCM16 LE mono out. What's short of a whole group stays for the next one.
    pub fn feed(&mut self, stereo: &[u8]) -> Vec<u8> {
        for frame in stereo.chunks_exact(4) {
            let left = i16::from_le_bytes([frame[0], frame[1]]) as f64;
            self.buf.push(self.wind.step(left) as f32);
        }
        let groups = (self.buf.len() - (TAPS - 1)) / FACTOR;
        let mut out = Vec::with_capacity(groups * 2);
        for k in 0..groups {
            let newest = TAPS - 1 + FACTOR * k + (FACTOR - 1);
            let acc: f32 = self.h.iter().enumerate().map(|(j, c)| c * self.buf[newest - j]).sum();
            out.extend_from_slice(&(acc.round().clamp(i16::MIN as f32, i16::MAX as f32) as i16).to_le_bytes());
        }
        self.buf.drain(..groups * FACTOR);
        out
    }
}

/// Whether a read holds anything: the Lark's receiver gives exact zeros with its transmitter off.
pub fn silent(stereo: &[u8]) -> bool {
    stereo.chunks_exact(2).all(|b| i16::from_le_bytes([b[0], b[1]]).unsigned_abs() < 32)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tone(hz: f64, ms: usize, left: f64, right: f64) -> Vec<u8> {
        let n = RATE_IN as usize * ms / 1000;
        (0..n)
            .flat_map(|i| {
                let s = (2.0 * PI * hz * i as f64 / RATE_IN as f64).sin();
                let (l, r) = ((s * left) as i16, (s * right) as i16);
                [l.to_le_bytes(), r.to_le_bytes()].concat()
            })
            .collect()
    }

    fn rms(pcm: &[u8]) -> f64 {
        let s: Vec<f64> = pcm.chunks_exact(2).map(|b| i16::from_le_bytes([b[0], b[1]]) as f64).collect();
        let tail = &s[s.len() / 2..];
        (tail.iter().map(|v| v * v).sum::<f64>() / tail.len() as f64).sqrt()
    }

    #[test]
    fn twenty_ms_in_is_twenty_ms_out() {
        let out = Lark::default().feed(&vec![0; FRAME_BYTES_IN]);
        assert_eq!(out.len(), crate::FRAME_BYTES);
    }

    #[test]
    fn frames_give_what_one_long_read_gives() {
        let a = tone(1000.0, 200, 8000.0, 0.0);
        let whole = Lark::default().feed(&a);
        let mut l = Lark::default();
        let parts: Vec<u8> = a.chunks(FRAME_BYTES_IN).flat_map(|f| l.feed(f)).collect();
        assert_eq!(whole, parts);
    }

    #[test]
    fn his_voice_passes_and_the_right_channel_does_not() {
        assert!((rms(&Lark::default().feed(&tone(1000.0, 400, 8000.0, 0.0))) / (8000.0 / 2f64.sqrt()) - 1.0).abs() < 0.02);
        assert!(rms(&Lark::default().feed(&tone(1000.0, 400, 0.0, 8000.0))) < 1.0);
    }

    #[test]
    fn wind_and_hiss_stay_out() {
        // Two poles at 150 Hz: 40 Hz comes out about 23 dB down.
        assert!(rms(&Lark::default().feed(&tone(40.0, 800, 8000.0, 8000.0))) < 8000.0 / 2f64.sqrt() * 0.08, "rumble");
        assert!(rms(&Lark::default().feed(&tone(12_000.0, 400, 8000.0, 8000.0))) < 2.0, "folds into his voice otherwise");
    }
}
