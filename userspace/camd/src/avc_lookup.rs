//! Duck Detector's AVC lookup probe (`avc_lookup_probe.cpp`, nightly 3aa2749c, pinned at 78aabb41),
//! run by camd as a regression test for the SELinux hide hook (`kernel/feature/selinux_hide.c`).
//!
//! A child of app_zygote pinned to one CPU writes two payloads to /proc/thread-self/attr/current
//! 4096 times each and reads how far that CPU's AVC `lookups` counter
//! (/sys/fs/selinux/avc/cache_stats) moved. A names a type no policy defines and fails conversion;
//! B is A with a leading newline and skips conversion. Both fail with EINVAL after one SETCURRENT
//! check on a stock kernel (1/1); a hook that checks permissions again shows 1/2 or 2/2 (Duck's
//! table: KernelSU `3f388ef`..`ab23091` gave 1/2).
//!
//! The hook only acts for uids >= 10000 and the measured path needs SETCURRENT, so camd cannot
//! measure as itself and the Manager's isolated probe cannot reach it. `camd debug avc-lookup
//! --child` becomes what Duck's carrier is (`u:r:app_zygote:s0`, an app uid) and prints its report
//! as JSON; [`device::collect`] runs that child with a deadline. The parsers and the verdict are
//! Duck's, so both apps read the counters alike.
#![cfg_attr(not(target_os = "android"), allow(dead_code))]

use serde_json::Value;

pub const CARRIER: &str = "u:r:app_zygote:s0";
/// What the child becomes: an isolated-process uid, so no app's allowlist entry or profile applies.
pub const PROBE_UID: u32 = 99_999;
pub const PAYLOAD_A: &[u8] = b"u:r:__selinux_hide_probe_invalid__:s0";
pub const PAYLOAD_B: &[u8] = b"\n:r:__selinux_hide_probe_invalid__:s0";
pub const WARMUP: usize = 32;
pub const PAIRS: usize = 128;
pub const ROUNDS: usize = 4;
pub const WRITES: u32 = 4096;
/// Largest CPU number plus one, and most cache_stats rows, the parsers accept.
pub const CPU_LIMIT: usize = 256;

// Linux errno values the parsers report (libc is an Android-only dependency).
const E2BIG: i32 = 7;
const ENODATA: i32 = 61;
const EPROTO: i32 = 71;

const STATS_HEADER: &str = "lookups hits misses allocations reclaims frees\n";

/// Leading decimal digits of `text` as a u32, and what follows them.
fn number(text: &str) -> Option<(u32, &str)> {
    let end = text
        .bytes()
        .position(|b| !b.is_ascii_digit())
        .unwrap_or(text.len());
    if end == 0 {
        return None;
    }
    let value = u32::try_from(text[..end].parse::<u64>().ok()?).ok()?;
    Some((value, &text[end..]))
}

/// The `lookups` column of each cache_stats row (one row per possible CPU, ascending), or the
/// errno that says why the text was rejected. ACK selinuxfs prints the header, then
/// "%u %u %u %u %u %u\n" per CPU with hits computed as lookups - misses.
pub fn parse_cache_stats(text: &str) -> Result<Vec<u32>, i32> {
    let mut rest = text.strip_prefix(STATS_HEADER).ok_or(EPROTO)?;
    let mut lookups = Vec::new();
    while !rest.is_empty() {
        if lookups.len() == CPU_LIMIT {
            return Err(E2BIG);
        }
        let mut columns = [0u32; 6];
        for (i, column) in columns.iter_mut().enumerate() {
            let (value, after) = number(rest).ok_or(EPROTO)?;
            let sep = if i == 5 { '\n' } else { ' ' };
            rest = after.strip_prefix(sep).ok_or(EPROTO)?;
            *column = value;
        }
        // A row whose hits and misses do not add up to its first column is not this format.
        if columns[1].wrapping_add(columns[2]) != columns[0] {
            return Err(EPROTO);
        }
        lookups.push(columns[0]);
    }
    if lookups.is_empty() {
        Err(ENODATA)
    } else {
        Ok(lookups)
    }
}

/// /sys/devices/system/cpu/possible: ascending "n" or "n-m" items joined by commas, ended by a
/// newline. Index i is true when CPU i is possible.
pub fn parse_cpu_list(text: &str) -> Result<Vec<bool>, i32> {
    let mut possible = vec![false; CPU_LIMIT];
    let mut rest = text;
    let mut previous: i64 = -1;
    loop {
        let (first, after) = number(rest).ok_or(EPROTO)?;
        rest = after;
        let mut last = first;
        if let Some(after) = rest.strip_prefix('-') {
            let (value, after) = number(after).ok_or(EPROTO)?;
            last = value;
            rest = after;
        }
        if i64::from(first) <= previous || last < first {
            return Err(EPROTO);
        }
        if last as usize >= CPU_LIMIT {
            return Err(E2BIG);
        }
        for cpu in first..=last {
            possible[cpu as usize] = true;
        }
        previous = i64::from(last);
        match rest.strip_prefix(',') {
            Some(after) => rest = after,
            None => break,
        }
    }
    let rest = rest.strip_prefix('\n').unwrap_or(rest);
    if rest.is_empty() {
        Ok(possible)
    } else {
        Err(EPROTO)
    }
}

/// The cache_stats row of `cpu`, or None when it is not possible.
pub fn stats_row(possible: &[bool], cpu: i32) -> Option<usize> {
    let cpu = usize::try_from(cpu).ok()?;
    if !*possible.get(cpu)? {
        return None;
    }
    Some(possible[..cpu].iter().filter(|p| **p).count())
}

/// Lookups per write of A and of B, as Duck names them.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Profile {
    OneOne,
    OneTwo,
    TwoTwo,
    /// counts outside the 1x and 2x bands
    Unclassified,
}

impl Profile {
    pub const fn label(self) -> &'static str {
        match self {
            Self::OneOne => "1/1",
            Self::OneTwo => "1/2",
            Self::TwoTwo => "2/2",
            Self::Unclassified => "?",
        }
    }
}

/// Every rejected write adds its handler's lookups to the pinned CPU's counter, and other tasks
/// on that CPU and the counter reads only add more: an n-lookup path leaves a batch in
/// [n, n + 0.1] x writes unless that extra activity passed a tenth of the batch.
fn lookups_per_write(batch: u32, writes: u32) -> Option<u32> {
    let (batch, writes) = (u64::from(batch), u64::from(writes));
    if (writes..=writes * 11 / 10).contains(&batch) {
        Some(1)
    } else if (2 * writes..=writes * 21 / 10).contains(&batch) {
        Some(2)
    } else {
        None
    }
}

pub fn profile(writes: u32, a: &[u32], b: &[u32]) -> Profile {
    if writes == 0 || a.is_empty() || a.len() != b.len() {
        return Profile::Unclassified;
    }
    let single = |batches: &[u32]| {
        let first = lookups_per_write(batches[0], writes)?;
        batches
            .iter()
            .all(|&batch| lookups_per_write(batch, writes) == Some(first))
            .then_some(first)
    };
    match (single(a), single(b)) {
        (Some(1), Some(1)) => Profile::OneOne,
        (Some(1), Some(2)) => Profile::OneTwo,
        (Some(2), Some(2)) => Profile::TwoTwo,
        _ => Profile::Unclassified,
    }
}

/// What the hiding audit keeps of a child's report.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Reading {
    /// Duck's collection state: collected, timingOnly, permissionLimited, unsupported,
    /// unavailable or inconclusive
    pub state: String,
    pub writes: u32,
    pub a: Vec<u32>,
    pub b: Vec<u32>,
}

impl Reading {
    pub fn from_json(report: &Value) -> Option<Self> {
        let batches = |key: &str| -> Vec<u32> {
            report[key]
                .as_array()
                .map(|list| {
                    list.iter()
                        .filter_map(Value::as_u64)
                        .map(|v| v as u32)
                        .collect()
                })
                .unwrap_or_default()
        };
        Some(Self {
            state: report["state"].as_str()?.to_owned(),
            writes: report["writes"].as_u64().unwrap_or(0) as u32,
            a: batches("a"),
            b: batches("b"),
        })
    }

    /// Only collected counts are classified.
    pub fn profile(&self) -> Option<Profile> {
        (self.state == "collected").then(|| profile(self.writes, &self.a, &self.b))
    }

    /// Lookups per write over every batch, times 100.
    pub fn rate_percent(batches: &[u32], writes: u32) -> u64 {
        if batches.is_empty() || writes == 0 {
            return 0;
        }
        batches.iter().map(|&b| u64::from(b)).sum::<u64>() * 100
            / (batches.len() as u64 * u64::from(writes))
    }

    pub fn describe(&self) -> String {
        format!(
            "A/B = {} ({:.2}/{:.2} lookups per write, {} writes x {} rounds)",
            self.profile().unwrap_or(Profile::Unclassified).label(),
            Self::rate_percent(&self.a, self.writes) as f64 / 100.0,
            Self::rate_percent(&self.b, self.writes) as f64 / 100.0,
            self.writes,
            self.a.len(),
        )
    }
}

#[cfg(target_os = "android")]
pub mod device {
    use std::fs::{File, OpenOptions};
    use std::io::{Read, Write};
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};

    use serde_json::{Value, json};

    use super::{
        CARRIER, CPU_LIMIT, PAIRS, PAYLOAD_A, PAYLOAD_B, PROBE_UID, ROUNDS, WARMUP, WRITES,
    };

    const ATTR: &str = "/proc/thread-self/attr/current";
    const ENFORCE: &str = "/sys/fs/selinux/enforce";
    const STATS: &str = "/sys/fs/selinux/avc/cache_stats";
    const POSSIBLE_CPUS: &str = "/sys/devices/system/cpu/possible";
    /// A normal run takes well under a second; slow cores and a hooked handler get headroom.
    const DEADLINE: Duration = Duration::from_secs(3);

    fn errno() -> i32 {
        std::io::Error::last_os_error().raw_os_error().unwrap_or(0)
    }

    fn read_text(path: &str) -> Result<String, i32> {
        let mut text = String::new();
        File::open(path)
            .and_then(|mut f| f.read_to_string(&mut text))
            .map_err(|e| e.raw_os_error().unwrap_or(libc::EIO))?;
        Ok(text)
    }

    /// The calling thread's context, without selinux_getprocattr's trailing NUL or newline.
    fn read_identity() -> Result<String, i32> {
        let text = read_text(ATTR)?;
        let context = text.trim_end_matches(['\n', '\0']).to_owned();
        if context.is_empty() {
            Err(libc::EINVAL)
        } else {
            Ok(context)
        }
    }

    /// AOSP seapp_contexts gives app_zygote MLS categories from the app uid; accept them.
    fn is_carrier(context: &str) -> bool {
        context.strip_prefix(CARRIER).is_some_and(|rest| {
            rest.is_empty()
                || rest
                    .strip_prefix(":c")
                    .is_some_and(|c| c.starts_with(|ch: char| ch.is_ascii_digit()))
        })
    }

    /// Duck's carrier: app_zygote's domain and an app uid, without capabilities. The context is
    /// set while still uid 0, so the SELinux hide hook (uids >= 10000) is not on this path.
    fn become_carrier() -> Result<(), i32> {
        OpenOptions::new()
            .write(true)
            .open(ATTR)
            .and_then(|mut f| f.write(CARRIER.as_bytes()))
            .map_err(|e| e.raw_os_error().unwrap_or(libc::EIO))?;
        let id = PROBE_UID;
        // SAFETY: plain syscalls on this process; no pointers but the null group list.
        unsafe {
            if libc::setgroups(0, std::ptr::null()) != 0
                || libc::setresgid(id, id, id) != 0
                || libc::setresuid(id, id, id) != 0
            {
                return Err(errno());
            }
        }
        Ok(())
    }

    #[derive(Clone, Copy)]
    enum Payload {
        A,
        B,
    }

    struct Session {
        attr: File,
        report: Value,
    }

    impl Session {
        /// One write per call and no retry: proc_pid_attr_write treats every call as a new
        /// transaction. Anything but EINVAL means the payload left the measured path.
        fn issue(&mut self, payload: Payload) -> bool {
            let bytes = match payload {
                Payload::A => PAYLOAD_A,
                Payload::B => PAYLOAD_B,
            };
            match self.attr.write(bytes) {
                Err(e) if e.raw_os_error() == Some(libc::EINVAL) => true,
                outcome => {
                    self.report["unexpected"] = json!(match payload {
                        Payload::A => "A",
                        Payload::B => "B",
                    });
                    match outcome {
                        Ok(count) => {
                            self.report["unexpectedBytes"] = json!(count);
                            self.report["error"] = json!(0);
                        }
                        Err(e) => {
                            self.report["unexpectedBytes"] = json!(-1);
                            self.report["error"] = json!(e.raw_os_error().unwrap_or(0));
                        }
                    }
                    self.report["state"] = json!("inconclusive");
                    false
                }
            }
        }

        fn fail_stats(&mut self, error: i32) -> bool {
            self.report["error"] = json!(error);
            false
        }
    }

    fn median(values: &mut [i64]) -> i64 {
        values.sort_unstable();
        values[values.len() / 2]
    }

    fn time_pairs(session: &mut Session) -> bool {
        let mut a = Vec::with_capacity(PAIRS);
        let mut b = Vec::with_capacity(PAIRS);
        let mut delta = Vec::with_capacity(PAIRS);
        for i in 0..WARMUP + PAIRS {
            let a_first = i % 2 == 0;
            let (first, second) = if a_first {
                (Payload::A, Payload::B)
            } else {
                (Payload::B, Payload::A)
            };
            let start = Instant::now();
            if !session.issue(first) {
                return false;
            }
            let middle = Instant::now();
            if !session.issue(second) {
                return false;
            }
            let end = Instant::now();
            if i < WARMUP {
                continue;
            }
            let one = (middle - start).as_nanos() as i64;
            let two = (end - middle).as_nanos() as i64;
            let (ta, tb) = if a_first { (one, two) } else { (two, one) };
            a.push(ta);
            b.push(tb);
            delta.push(ta - tb);
        }
        session.report["pairs"] = json!(a.len());
        session.report["medianANs"] = json!(median(&mut a));
        session.report["medianBNs"] = json!(median(&mut b));
        session.report["medianDeltaNs"] = json!(median(&mut delta));
        true
    }

    fn pin_to_allowed_cpu() -> Result<i32, i32> {
        // SAFETY: cpu_set_t is plain data; the calls only read and write it.
        unsafe {
            let mut allowed: libc::cpu_set_t = std::mem::zeroed();
            if libc::sched_getaffinity(0, size_of::<libc::cpu_set_t>(), &raw mut allowed) != 0 {
                return Err(errno());
            }
            for cpu in 0..CPU_LIMIT {
                if !libc::CPU_ISSET(cpu, &allowed) {
                    continue;
                }
                let mut target: libc::cpu_set_t = std::mem::zeroed();
                libc::CPU_SET(cpu, &mut target);
                if libc::sched_setaffinity(0, size_of::<libc::cpu_set_t>(), &raw const target) != 0
                {
                    continue;
                }
                let cpu = cpu as i32;
                return verify_pinned(cpu).map(|()| cpu);
            }
        }
        Err(libc::ESRCH)
    }

    /// A cpuset move resets the affinity of the tasks it moves, and a paused or offline CPU
    /// pushes them elsewhere, so being on the CPU now is not enough.
    fn verify_pinned(cpu: i32) -> Result<(), i32> {
        // SAFETY: as in pin_to_allowed_cpu.
        unsafe {
            let mut current: libc::cpu_set_t = std::mem::zeroed();
            if libc::sched_getaffinity(0, size_of::<libc::cpu_set_t>(), &raw mut current) != 0 {
                return Err(errno());
            }
            let running = libc::sched_getcpu();
            if running < 0 {
                return Err(errno());
            }
            if running == cpu
                && libc::CPU_COUNT(&current) == 1
                && libc::CPU_ISSET(cpu as usize, &current)
            {
                Ok(())
            } else {
                Err(libc::EXDEV)
            }
        }
    }

    fn read_stats() -> Result<Vec<u32>, i32> {
        super::parse_cache_stats(&read_text(STATS)?)
    }

    /// Each batch lies between two reads of the pinned CPU's row. The counter is shared by every
    /// task on that CPU, so other work and the second read's own path walk can only add to it.
    fn count_batch(
        session: &mut Session,
        payload: Payload,
        cpu: i32,
        row: usize,
        rows: usize,
    ) -> Option<u32> {
        let step = |session: &mut Session, r: Result<Vec<u32>, i32>| match r {
            Ok(stats) if stats.len() == rows => Some(stats),
            Ok(_) => {
                session.fail_stats(libc::EPROTO);
                None
            }
            Err(e) => {
                session.fail_stats(e);
                None
            }
        };
        if let Err(e) = verify_pinned(cpu) {
            session.fail_stats(e);
            return None;
        }
        let before = step(session, read_stats())?;
        for _ in 0..WRITES {
            if !session.issue(payload) {
                return None;
            }
        }
        let after = step(session, read_stats())?;
        if let Err(e) = verify_pinned(cpu) {
            session.fail_stats(e);
            return None;
        }
        Some(after[row].wrapping_sub(before[row]))
    }

    fn count_rounds(session: &mut Session) -> bool {
        session.report["step"] = json!("affinity");
        let cpu = match pin_to_allowed_cpu() {
            Ok(cpu) => cpu,
            Err(e) => return session.fail_stats(e),
        };
        session.report["cpu"] = json!(cpu);
        session.report["step"] = json!("cpuMap");
        let possible = match read_text(POSSIBLE_CPUS).and_then(|t| super::parse_cpu_list(&t)) {
            Ok(possible) => possible,
            Err(e) => return session.fail_stats(e),
        };
        let possible_count = possible.iter().filter(|p| **p).count();
        session.report["possibleCpus"] = json!(possible_count);
        let Some(row) = super::stats_row(&possible, cpu) else {
            return session.fail_stats(libc::EDOM);
        };
        session.report["cpuRow"] = json!(row);
        session.report["step"] = json!("stats");
        let rows = match read_stats() {
            Ok(stats) => stats.len(),
            Err(e) => return session.fail_stats(e),
        };
        session.report["cpuRows"] = json!(rows);
        // sel_avc_get_stat_idx prints one row per possible CPU; any other count leaves the row unknown.
        if rows != possible_count {
            return session.fail_stats(libc::EPROTO);
        }
        session.report["step"] = json!("counting");
        let mut a = Vec::with_capacity(ROUNDS);
        let mut b = Vec::with_capacity(ROUNDS);
        for round in 0..ROUNDS {
            // AB, BA, AB, BA: order effects and slow drift fall on both payloads alike.
            for order in 0..2 {
                let is_a = (round + order) % 2 == 0;
                let payload = if is_a { Payload::A } else { Payload::B };
                let Some(lookups) = count_batch(session, payload, cpu, row, rows) else {
                    return false;
                };
                if is_a {
                    a.push(lookups);
                } else {
                    b.push(lookups);
                }
            }
            session.report["rounds"] = json!(round + 1);
        }
        session.report["a"] = json!(a);
        session.report["b"] = json!(b);
        true
    }

    fn probe() -> Value {
        let mut report =
            json!({ "state": "unavailable", "step": "carrier", "error": 0, "writes": WRITES });
        // SAFETY: getuid has no preconditions.
        if unsafe { libc::getuid() } != 0 {
            report["error"] = json!(libc::EPERM);
            return report;
        }
        if let Err(e) = become_carrier() {
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
        // SAFETY: as above.
        let uid = unsafe { libc::getuid() };
        if uid < 10_000 || !is_carrier(&initial) {
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
        let mut session = Session { attr, report };
        session.report["step"] = json!("controls");
        if !session.issue(Payload::A) || !session.issue(Payload::B) {
            // A refused SETCURRENT check fails before conversion; the measured path is out of reach.
            let error = session.report["error"].as_i64().unwrap_or(0) as i32;
            if session.report["unexpectedBytes"] == json!(-1)
                && (error == libc::EACCES || error == libc::EPERM)
            {
                session.report["state"] = json!("permissionLimited");
            }
        } else {
            session.report["step"] = json!("timing");
            if time_pairs(&mut session) {
                session.report["state"] = json!("timingOnly");
                if count_rounds(&mut session) {
                    session.report["state"] = json!("collected");
                }
            }
        }
        let Session { attr, mut report } = session;
        drop(attr);
        // SAFETY: as above.
        let changed_uid = unsafe { libc::getuid() } != uid;
        match read_identity() {
            Ok(current) if current == initial && !changed_uid => {}
            other => {
                report["identityChanged"] = json!(other.is_ok());
                if report["state"] != "inconclusive" {
                    report["state"] = json!("inconclusive");
                    report["step"] = json!("identity");
                    report["error"] = json!(other.err().unwrap_or(0));
                }
            }
        }
        if report["state"] == "collected" {
            report["step"] = json!("finished");
        }
        report
    }

    /// `camd debug avc-lookup --child`: becomes the carrier and prints the report. The process
    /// cannot go back to root afterwards; only [`collect`] should start it.
    pub fn child() {
        println!("{}", probe());
    }

    /// Runs the probe in a child camd and returns its report; None when it did not answer in time.
    pub fn collect() -> Option<Value> {
        let exe = std::env::current_exe().ok()?;
        let mut child = Command::new(exe)
            .args(["debug", "avc-lookup", "--child"])
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
                    std::thread::sleep(Duration::from_millis(20));
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

    /// `camd debug avc-lookup`: the report, and Duck's verdict on it.
    pub fn run() {
        match collect() {
            Some(report) => {
                if let Some(reading) = super::Reading::from_json(&report)
                    && reading.profile().is_some()
                {
                    println!("{}", reading.describe());
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

    #[test]
    fn parses_cache_stats() {
        let text = "lookups hits misses allocations reclaims frees\n10 7 3 3 0 0\n20 20 0 1 0 0\n";
        assert_eq!(parse_cache_stats(text), Ok(vec![10, 20]));
        assert_eq!(
            parse_cache_stats("lookups hits misses allocations reclaims frees\n"),
            Err(ENODATA)
        );
        assert_eq!(parse_cache_stats("nope\n1 1 0 0 0 0\n"), Err(EPROTO));
        // hits + misses must equal lookups
        assert_eq!(
            parse_cache_stats("lookups hits misses allocations reclaims frees\n10 7 2 0 0 0\n"),
            Err(EPROTO)
        );
        // no trailing newline
        assert_eq!(
            parse_cache_stats("lookups hits misses allocations reclaims frees\n1 1 0 0 0 0"),
            Err(EPROTO)
        );
    }

    #[test]
    fn parses_cpu_lists_and_rows() {
        let possible = parse_cpu_list("0-3,6\n").unwrap();
        assert_eq!(possible.iter().filter(|p| **p).count(), 5);
        assert_eq!(stats_row(&possible, 0), Some(0));
        assert_eq!(stats_row(&possible, 6), Some(4));
        assert_eq!(stats_row(&possible, 4), None);
        assert_eq!(stats_row(&possible, -1), None);
        assert_eq!(parse_cpu_list("0-7"), parse_cpu_list("0-7\n"));
        assert_eq!(parse_cpu_list("3,1\n"), Err(EPROTO));
        assert_eq!(parse_cpu_list("0-300\n"), Err(E2BIG));
        assert_eq!(parse_cpu_list("0-3 \n"), Err(EPROTO));
    }

    #[test]
    fn classifies_like_duck() {
        let w = WRITES;
        assert_eq!(profile(w, &[w; 4], &[w + 100; 4]), Profile::OneOne);
        assert_eq!(profile(w, &[w; 4], &[2 * w; 4]), Profile::OneTwo);
        assert_eq!(profile(w, &[2 * w; 4], &[2 * w + 5; 4]), Profile::TwoTwo);
        // one noisy batch outside the band leaves the profile unclassified
        assert_eq!(
            profile(w, &[w, w, w, w * 3 / 2], &[w; 4]),
            Profile::Unclassified
        );
        assert_eq!(profile(w, &[], &[]), Profile::Unclassified);
    }

    #[test]
    fn reads_child_reports() {
        let report = serde_json::json!({
            "state": "collected", "writes": 4096,
            "a": [4096, 4100, 4096, 4097], "b": [8192, 8200, 8192, 8193],
        });
        let reading = Reading::from_json(&report).unwrap();
        assert_eq!(reading.profile(), Some(Profile::OneTwo));
        assert!(reading.describe().starts_with("A/B = 1/2 (1.00/2.00"));
        let timing = Reading::from_json(&serde_json::json!({ "state": "timingOnly" })).unwrap();
        assert_eq!(timing.profile(), None);
    }
}
