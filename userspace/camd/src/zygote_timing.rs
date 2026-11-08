//! Duck Detector's App Zygote attr/current timing (`app_zygote_timing.cpp`, `d6d96970` ..
//! `b63d6f62`, pinned at d60597ca), as a check of the SELinux hide hook
//! (`my_setprocattr` in `kernel/feature/selinux_hide.c`, CAM_CHANGES rows 71 and 101).
//!
//! Duck's preload writes two payloads of the same length to /proc/thread-self/attr/current in
//! AB/BA pairs: A names a type no policy has, B is A with a leading newline, both fail with
//! EINVAL. A positive median of A-B is Danger. Before 2026-10-23 the hook made A ~110-260 ns
//! slower than stock; after the fix, 6-15 ns.
//!
//! camd cannot take Duck's verdict as is: its carrier is not Duck's preload, and on a TB323FU
//! stock reads A-B >= 0 here while Duck reads -53..-104 ns. So it measures what the hook adds.
//! One child becomes the carrier (`u:r:app_zygote:s0`) and keeps 0 as its saved uid, so it can
//! switch its real uid between [`STOCK_UID`], which the hook passes straight to SELinux (it acts
//! for uids >= 10000 only), and [`HOOK_UID`], which goes through it. On each CPU it times blocks
//! of pairs as stock, hook, hook, stock, [`QUADS`] times over: drift, frequency changes and
//! whatever one process has that another lacks fall on both paths alike (separate processes
//! differed by up to ~40 ns). The hook may add at most [`LIMIT_NS`] to A-B, the limit
//! `device_selinux_timing_test.sh` holds it to by switching the feature off and on.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde_json::{Value, json};

/// Below 10000: `my_setprocattr` calls SELinux's handler at once (nobody's uid, no app's).
pub const STOCK_UID: u32 = 9_999;
/// At or above 10000, so the hook acts, but neither an app nor an isolated uid: no allowlist
/// entry or profile applies, and kernel umount's setresuid hook returns before it logs (it
/// would log every switch to an isolated uid, `kernel/feature/kernel_umount.c`).
pub const HOOK_UID: u32 = 20_000;
/// Pairs timed after each uid switch before a block starts.
pub const WARMUP: usize = 16;
pub const BLOCK_PAIRS: usize = 256;
/// Stock, hook, hook, stock blocks, this many times per CPU.
pub const QUADS: usize = 4;
/// CPUs measured, the first ones the process may use.
pub const MAX_CPUS: usize = 16;
/// ns the hook may add to A-B over stock.
pub const LIMIT_NS: f64 = 50.0;
/// Quads of a CPU that must be over the limit for the CPU to count as shifted.
pub const SHIFTED_QUADS: usize = 3;

/// Mean of the middle 80% of `deltas` (sorted in place): the clock steps 52 ns on a TB323FU, so
/// a median cannot show a gap of a few ns, and the tails are interrupts.
pub fn trimmed_mean(deltas: &mut [i64]) -> Option<f64> {
    deltas.sort_unstable();
    let cut = deltas.len() / 10;
    let middle = &deltas[cut..deltas.len() - cut];
    if middle.is_empty() {
        return None;
    }
    Some(middle.iter().map(|&d| d as f64).sum::<f64>() / middle.len() as f64)
}

/// Whether the block at `index` of a CPU's run is timed through the hook.
pub const fn hook_block(index: usize) -> bool {
    matches!(index % 4, 1 | 2)
}

/// What the child measured on one CPU.
#[derive(Debug, Clone, PartialEq)]
pub struct CpuSample {
    pub cpu: u32,
    /// trimmed mean of A-B over every stock pair and every hook pair, ns
    pub stock_ns: f64,
    pub hook_ns: f64,
    /// hook minus stock within each quad, ns
    pub quads: Vec<f64>,
}

impl CpuSample {
    pub fn shift(&self) -> f64 {
        self.hook_ns - self.stock_ns
    }

    fn from_json(c: &Value) -> Option<Self> {
        let quads: Vec<f64> = c["quads"]
            .as_array()?
            .iter()
            .map(Value::as_f64)
            .collect::<Option<_>>()?;
        Some(Self {
            cpu: u32::try_from(c["cpu"].as_u64()?).ok()?,
            stock_ns: c["stockNs"].as_f64()?,
            hook_ns: c["hookNs"].as_f64()?,
            quads,
        })
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Verdict {
    /// every CPU within the limit; the largest shift, ns
    Clean(f64),
    /// CPUs over the limit in [`SHIFTED_QUADS`] quads or more, with their shift, ns
    Shifted(Vec<(u32, f64)>),
    /// the child failed, measured no CPU fully, or a CPU over the limit in too few quads
    Inconclusive,
}

/// The child's report.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Reading {
    /// collected, unavailable, unsupported, permissionLimited or inconclusive
    pub state: String,
    pub cpus: Vec<CpuSample>,
}

impl Reading {
    pub fn from_json(report: &Value) -> Option<Self> {
        let cpus = report["cpus"]
            .as_array()
            .map(|list| list.iter().filter_map(CpuSample::from_json).collect())
            .unwrap_or_default();
        Some(Self {
            state: report["state"].as_str()?.to_owned(),
            cpus,
        })
    }

    pub fn verdict(&self) -> Verdict {
        let full: Vec<&CpuSample> = self
            .cpus
            .iter()
            .filter(|c| c.quads.len() == QUADS)
            .collect();
        if self.state != "collected" || full.is_empty() {
            return Verdict::Inconclusive;
        }
        let shifted: Vec<(u32, f64)> = full
            .iter()
            .filter(|c| {
                c.shift() > LIMIT_NS
                    && c.quads.iter().filter(|&&q| q > LIMIT_NS).count() >= SHIFTED_QUADS
            })
            .map(|c| (c.cpu, c.shift()))
            .collect();
        if !shifted.is_empty() {
            return Verdict::Shifted(shifted);
        }
        let worst = full
            .iter()
            .map(|c| c.shift())
            .fold(f64::NEG_INFINITY, f64::max);
        if worst <= LIMIT_NS {
            Verdict::Clean(worst)
        } else {
            Verdict::Inconclusive
        }
    }

    /// The finding's items: one per CPU over the limit.
    pub fn describe(shifted: &[(u32, f64)]) -> Vec<String> {
        shifted
            .iter()
            .map(|(cpu, shift)| {
                format!("CPU {cpu}: A-B {shift:+.0} ns over stock (limit {LIMIT_NS:.0} ns)")
            })
            .collect()
    }

    pub fn verdict_json(&self) -> Value {
        match self.verdict() {
            Verdict::Clean(worst) => json!({ "verdict": "clean", "worstNs": worst }),
            Verdict::Shifted(cpus) => {
                json!({ "verdict": "shifted", "cpus": Self::describe(&cpus) })
            }
            Verdict::Inconclusive => json!({ "verdict": "inconclusive" }),
        }
    }
}

#[cfg(target_os = "android")]
pub mod device {
    use std::fs::{File, OpenOptions};
    use std::io::Read;
    use std::os::fd::AsRawFd;
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};

    use serde_json::{Value, json};

    use super::{BLOCK_PAIRS, HOOK_UID, MAX_CPUS, QUADS, STOCK_UID, WARMUP};
    use crate::avc_lookup::device::{errno, is_carrier, read_identity, read_text};
    use crate::avc_lookup::{CARRIER, PAYLOAD_A, PAYLOAD_B};

    const ATTR: &str = "/proc/thread-self/attr/current";
    const ENFORCE: &str = "/sys/fs/selinux/enforce";
    /// The child takes some tens of ms on a TB323FU; slow cores and a hooked handler get headroom.
    const DEADLINE: Duration = Duration::from_secs(4);

    fn now_ns() -> Result<i64, i32> {
        let mut t = libc::timespec {
            tv_sec: 0,
            tv_nsec: 0,
        };
        // SAFETY: t is a valid timespec the call fills in.
        if unsafe { libc::clock_gettime(libc::CLOCK_MONOTONIC_RAW, &raw mut t) } != 0 {
            return Err(errno());
        }
        Ok(t.tv_sec * 1_000_000_000 + t.tv_nsec)
    }

    /// One write, timed like Duck's trial; anything but EINVAL left the measured path.
    fn timed(attr: &File, payload: &[u8]) -> Result<i64, i32> {
        let begin = now_ns()?;
        // SAFETY: payload is a live slice and attr an open descriptor.
        let written =
            unsafe { libc::write(attr.as_raw_fd(), payload.as_ptr().cast(), payload.len()) };
        let error = errno();
        let end = now_ns()?;
        if written != -1 {
            return Err(libc::EPROTO);
        }
        if error != libc::EINVAL {
            return Err(error);
        }
        Ok(end - begin)
    }

    fn current_cpu() -> i32 {
        // SAFETY: no preconditions.
        unsafe { libc::sched_getcpu() }
    }

    fn real_uid() -> u32 {
        // SAFETY: no preconditions.
        unsafe { libc::getuid() }
    }

    /// The CPUs this process may run on, ascending, at most [`MAX_CPUS`].
    fn allowed_cpus() -> Result<Vec<usize>, i32> {
        // SAFETY: cpu_set_t is plain data; the call only writes it.
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            if libc::sched_getaffinity(0, size_of::<libc::cpu_set_t>(), &raw mut set) != 0 {
                return Err(errno());
            }
            Ok((0..size_of::<libc::cpu_set_t>() * 8)
                .filter(|&cpu| libc::CPU_ISSET(cpu, &set))
                .take(MAX_CPUS)
                .collect())
        }
    }

    /// Pinning to a CPU the thread is not on moves it there before the call returns.
    fn pin(cpu: usize) -> Result<(), i32> {
        // SAFETY: as in allowed_cpus.
        unsafe {
            let mut set: libc::cpu_set_t = std::mem::zeroed();
            libc::CPU_SET(cpu, &mut set);
            if libc::sched_setaffinity(0, size_of::<libc::cpu_set_t>(), &raw const set) != 0 {
                return Err(errno());
            }
        }
        if current_cpu() == cpu as i32 {
            Ok(())
        } else {
            Err(libc::EXDEV)
        }
    }

    /// Real and effective uid `uid`, saved uid 0: 0 is one of the process's own uids, so it
    /// may go back to it and on to the other uid without a capability check.
    fn switch_uid(uid: u32) -> Result<(), i32> {
        // SAFETY: plain syscalls on this process.
        unsafe {
            if libc::setresuid(0, 0, 0) != 0 || libc::setresuid(uid, uid, 0) != 0 {
                return Err(errno());
            }
        }
        if real_uid() == uid {
            Ok(())
        } else {
            Err(libc::EPROTO)
        }
    }

    /// The carrier's domain while still uid 0, so the hook is not on this path; app gid and no
    /// groups for good.
    fn become_carrier() -> Result<(), i32> {
        use std::io::Write;
        OpenOptions::new()
            .write(true)
            .open(ATTR)
            .and_then(|mut f| f.write(CARRIER.as_bytes()))
            .map_err(|e| e.raw_os_error().unwrap_or(libc::EIO))?;
        let gid = HOOK_UID;
        // SAFETY: plain syscalls on this process; no pointers but the null group list.
        unsafe {
            if libc::setgroups(0, std::ptr::null()) != 0 || libc::setresgid(gid, gid, gid) != 0 {
                return Err(errno());
            }
        }
        Ok(())
    }

    /// One block: warmups, then AB/BA pairs on `cpu`. A pair the CPU changed in is dropped, as
    /// Duck does; so is a block that lost more than half of them.
    fn block(attr: &File, cpu: usize, out: &mut Vec<i64>, migrations: &mut u32) -> Result<(), i32> {
        let mut kept = Vec::with_capacity(BLOCK_PAIRS);
        for i in 0..WARMUP + BLOCK_PAIRS {
            let before = current_cpu();
            let (a, b) = if i % 2 == 0 {
                let a = timed(attr, PAYLOAD_A)?;
                (a, timed(attr, PAYLOAD_B)?)
            } else {
                let b = timed(attr, PAYLOAD_B)?;
                (timed(attr, PAYLOAD_A)?, b)
            };
            if i < WARMUP {
                continue;
            }
            if before != cpu as i32 || current_cpu() != before {
                *migrations += 1;
                continue;
            }
            kept.push(a - b);
        }
        if kept.len() < BLOCK_PAIRS / 2 {
            return Err(libc::EXDEV);
        }
        out.extend(kept);
        Ok(())
    }

    fn mean(deltas: &mut [i64]) -> Result<f64, i32> {
        super::trimmed_mean(deltas).ok_or(libc::ENODATA)
    }

    /// Every block on `cpu`; the uid is left at [`STOCK_UID`] or [`HOOK_UID`].
    fn measure(attr: &File, cpu: usize) -> Result<Value, i32> {
        pin(cpu)?;
        let mut stock_all = Vec::new();
        let mut hook_all = Vec::new();
        let mut quads = Vec::with_capacity(QUADS);
        let mut migrations = 0u32;
        for _ in 0..QUADS {
            let mut stock = Vec::new();
            let mut hook = Vec::new();
            for index in 0..4 {
                let through_hook = super::hook_block(index);
                switch_uid(if through_hook { HOOK_UID } else { STOCK_UID })?;
                pin(cpu)?;
                let out = if through_hook { &mut hook } else { &mut stock };
                block(attr, cpu, out, &mut migrations)?;
            }
            quads.push(mean(&mut hook.clone())? - mean(&mut stock.clone())?);
            stock_all.extend(stock);
            hook_all.extend(hook);
        }
        Ok(json!({
            "cpu": cpu,
            "pairs": stock_all.len() + hook_all.len(),
            "migrations": migrations,
            "stockNs": mean(&mut stock_all)?,
            "hookNs": mean(&mut hook_all)?,
            "quads": quads,
        }))
    }

    fn probe() -> Value {
        let mut report = json!({
            "state": "unavailable", "step": "carrier", "error": 0,
            "stockUid": STOCK_UID, "hookUid": HOOK_UID, "blockPairs": BLOCK_PAIRS,
        });
        if real_uid() != 0 {
            report["error"] = json!(libc::EPERM);
            return report;
        }
        let cpus = match allowed_cpus() {
            Ok(cpus) if !cpus.is_empty() => cpus,
            Ok(_) => {
                report["error"] = json!(libc::ESRCH);
                return report;
            }
            Err(e) => {
                report["error"] = json!(e);
                return report;
            }
        };
        if let Err(e) = become_carrier().and_then(|()| switch_uid(STOCK_UID)) {
            report["error"] = json!(e);
            return report;
        }
        let initial = match read_identity() {
            Ok(context) => context,
            Err(e) => {
                report["error"] = json!(e);
                return report;
            }
        };
        if !is_carrier(&initial) {
            report["state"] = json!("unsupported");
            return report;
        }
        report["step"] = json!("enforce");
        match read_text(ENFORCE) {
            Ok(mode) if mode.starts_with('1') => {}
            Ok(_) => {
                report["state"] = json!("unsupported");
                return report;
            }
            Err(e) => {
                report["error"] = json!(e);
                return report;
            }
        }
        report["step"] = json!("open");
        let attr = match OpenOptions::new().write(true).open(ATTR) {
            Ok(f) => f,
            Err(e) => {
                let e = e.raw_os_error().unwrap_or(0);
                report["error"] = json!(e);
                if e == libc::EACCES || e == libc::EPERM {
                    report["state"] = json!("permissionLimited");
                }
                return report;
            }
        };
        // Duck's preflight, B then A, on both paths.
        report["step"] = json!("controls");
        for uid in [STOCK_UID, HOOK_UID] {
            let checked = switch_uid(uid)
                .and_then(|()| timed(&attr, PAYLOAD_B))
                .and_then(|_| timed(&attr, PAYLOAD_A));
            if let Err(e) = checked {
                report["error"] = json!(e);
                report["state"] = json!(if e == libc::EACCES || e == libc::EPERM {
                    "permissionLimited"
                } else {
                    "inconclusive"
                });
                return report;
            }
        }
        // The first block of a fresh process read ~100 ns off on a TB323FU (both paths): one of
        // each, thrown away, before anything counts.
        report["step"] = json!("prime");
        let mut discarded = (Vec::new(), 0);
        for uid in [STOCK_UID, HOOK_UID] {
            let primed = switch_uid(uid)
                .and_then(|()| pin(cpus[0]))
                .and_then(|()| block(&attr, cpus[0], &mut discarded.0, &mut discarded.1));
            if let Err(e) = primed {
                report["error"] = json!(e);
                report["state"] = json!("inconclusive");
                return report;
            }
        }
        report["step"] = json!("timing");
        let mut samples = Vec::with_capacity(cpus.len());
        let mut skipped = Vec::new();
        for cpu in cpus {
            match measure(&attr, cpu) {
                Ok(sample) => samples.push(sample),
                // a CPU the cpuset or hotplug took away, or one too busy to keep the pairs on it
                Err(libc::EINVAL | libc::EXDEV) => skipped.push(cpu),
                Err(e) => {
                    report["error"] = json!(e);
                    report["state"] = json!("inconclusive");
                    report["cpus"] = json!(samples);
                    return report;
                }
            }
        }
        report["cpus"] = json!(samples);
        report["skippedCpus"] = json!(skipped);
        drop(attr);
        match read_identity() {
            Ok(current) if current == initial => {
                report["state"] = json!("collected");
                report["step"] = json!("finished");
            }
            other => {
                report["state"] = json!("inconclusive");
                report["step"] = json!("identity");
                report["error"] = json!(other.err().unwrap_or(0));
            }
        }
        report
    }

    /// `camd debug zygote-timing --child`: becomes the carrier and prints the report. The process
    /// runs as app_zygote afterwards; only [`collect`] should start it.
    pub fn child() {
        println!("{}", probe());
    }

    /// Runs the probe in a child camd and returns its report; None when it did not answer in time.
    pub fn collect() -> Option<Value> {
        let exe = std::env::current_exe().ok()?;
        let mut child = Command::new(exe)
            .args(["debug", "zygote-timing", "--child"])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .ok()?;
        let started = Instant::now();
        loop {
            match child.try_wait() {
                Ok(Some(_)) => break,
                Ok(None) if started.elapsed() < DEADLINE => {
                    std::thread::sleep(Duration::from_millis(10));
                }
                _ => {
                    let _ = child.kill();
                    let _ = child.wait();
                    return None;
                }
            }
        }
        let mut out = String::new();
        child.stdout.take()?.read_to_string(&mut out).ok()?;
        serde_json::from_str(out.trim()).ok()
    }

    /// `camd debug zygote-timing`: the verdict, then the report.
    pub fn run() {
        match collect() {
            Some(report) => {
                if let Some(reading) = super::Reading::from_json(&report) {
                    println!("{}", reading.verdict_json());
                }
                println!("{report}");
            }
            None => println!("{{\"state\":\"inconclusive\",\"step\":\"child\"}}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample(cpu: u32, stock: f64, quads: [f64; QUADS]) -> CpuSample {
        CpuSample {
            cpu,
            stock_ns: stock,
            hook_ns: stock + quads.iter().sum::<f64>() / QUADS as f64,
            quads: quads.to_vec(),
        }
    }

    fn reading(cpus: Vec<CpuSample>) -> Reading {
        Reading {
            state: "collected".to_owned(),
            cpus,
        }
    }

    #[test]
    fn trims_the_tails() {
        let mut deltas: Vec<i64> = (0..10).map(|_| 52).collect();
        deltas[0] = 100_000;
        deltas[9] = -100_000;
        assert_eq!(trimmed_mean(&mut deltas), Some(52.0));
        assert_eq!(trimmed_mean(&mut []), None);
    }

    #[test]
    fn blocks_go_stock_hook_hook_stock() {
        let order: Vec<bool> = (0..8).map(hook_block).collect();
        assert_eq!(order, [false, true, true, false, false, true, true, false]);
    }

    #[test]
    fn small_shifts_are_clean() {
        // after the 2026-10-23 fix: 6-15 ns on a TB323FU
        let r = reading(vec![
            sample(0, 60.0, [6.0, 8.0, 10.0, 12.0]),
            sample(1, 60.0, [15.0, 15.0, 15.0, 15.0]),
        ]);
        assert_eq!(r.verdict(), Verdict::Clean(15.0));
        // the hook making A relatively faster does not help Duck either
        let r = reading(vec![sample(0, 10.0, [-80.0; 4])]);
        assert_eq!(r.verdict(), Verdict::Clean(-80.0));
    }

    #[test]
    fn the_old_hook_is_found() {
        // before the fix: 110-260 ns
        let r = reading(vec![
            sample(0, 0.0, [110.0; 4]),
            sample(1, 0.0, [20.0; 4]),
            sample(2, 0.0, [260.0, 250.0, 270.0, 10.0]),
        ]);
        let Verdict::Shifted(cpus) = r.verdict() else {
            panic!("not shifted: {:?}", r.verdict())
        };
        assert_eq!(cpus.iter().map(|c| c.0).collect::<Vec<_>>(), [0, 2]);
        assert_eq!(
            Reading::describe(&cpus)[0],
            "CPU 0: A-B +110 ns over stock (limit 50 ns)"
        );
    }

    #[test]
    fn a_noisy_cpu_is_inconclusive() {
        // over the limit on average, but in two quads only
        let r = reading(vec![sample(0, 0.0, [200.0, 200.0, 0.0, 0.0])]);
        assert_eq!(r.verdict(), Verdict::Inconclusive);
    }

    #[test]
    fn failed_or_short_runs_are_inconclusive() {
        let mut r = reading(vec![sample(0, 0.0, [0.0; 4])]);
        r.state = "permissionLimited".to_owned();
        assert_eq!(r.verdict(), Verdict::Inconclusive);
        assert_eq!(reading(Vec::new()).verdict(), Verdict::Inconclusive);
        // a CPU with fewer quads is left out
        let mut short = sample(1, 0.0, [300.0; 4]);
        short.quads.pop();
        let r = reading(vec![sample(0, 0.0, [0.0; 4]), short]);
        assert_eq!(r.verdict(), Verdict::Clean(0.0));
    }

    #[test]
    fn reads_child_reports() {
        let report = json!({
            "state": "collected",
            "cpus": [{ "cpu": 3, "pairs": 2048, "migrations": 2, "stockNs": 60.5, "hookNs": 70.5,
                       "quads": [10.0, 9.0, 11.0, 10.0] }],
        });
        let r = Reading::from_json(&report).unwrap();
        assert_eq!(r.cpus[0].cpu, 3);
        assert!((r.cpus[0].shift() - 10.0).abs() < 1e-9);
        assert_eq!(r.verdict(), Verdict::Clean(10.0));
        assert!(Reading::from_json(&json!({ "cpus": [] })).is_none());
        // a CPU missing a number is dropped
        let broken = json!({ "state": "collected", "cpus": [{ "cpu": 0, "quads": [] }] });
        assert!(Reading::from_json(&broken).unwrap().cpus.is_empty());
    }
}
