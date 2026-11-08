//! The native control protocol: camd is the client, the injected interceptor is the server.
//!
//! Bit-exact with `native/teesim/common/control.cpp`: a frame is `[u32 big-endian length][UTF-8 JSON]`
//! (length in `1..=8 MiB`); the server sends a `hello` on connect, camd sends `config`/`ping`, the
//! server replies `ack`/`pong`. The frame codec and message parsing are pure and host-tested; the
//! `ControlClient` that drives a real `UnixStream` is Android-only.

use anyhow::{Context, Result, bail};
use std::io::Read;
use std::path::Path;

/// Frame length ceiling, matching `kMaxFrame` in control.cpp.
const MAX_FRAME: usize = 8 * 1024 * 1024;

/// Encode one control frame: a 4-byte big-endian length prefix followed by the JSON bytes.
pub fn encode_frame(json: &str) -> Vec<u8> {
    let bytes = json.as_bytes();
    // Control messages are small; a frame body over u32::MAX cannot occur here.
    let len = u32::try_from(bytes.len()).unwrap_or(u32::MAX);
    let mut out = Vec::with_capacity(4 + bytes.len());
    out.extend_from_slice(&len.to_be_bytes());
    out.extend_from_slice(bytes);
    out
}

/// Read one length-prefixed frame. Rejects a zero or over-`MAX_FRAME` length before reading the body.
pub fn read_frame<R: Read>(r: &mut R) -> Result<String> {
    let mut hdr = [0u8; 4];
    r.read_exact(&mut hdr).context("read frame length")?;
    let len = usize::try_from(u32::from_be_bytes(hdr)).context("frame length overflow")?;
    if len == 0 || len > MAX_FRAME {
        bail!("invalid frame length {len}");
    }
    let mut buf = vec![0u8; len];
    r.read_exact(&mut buf).context("read frame body")?;
    String::from_utf8(buf).context("frame not UTF-8")
}

/// The interceptor's hello: proof it loaded and bound the control socket.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hello {
    pub protocol: u32,
    pub hook: String,
    pub android_api: i32,
    pub keystore_pid: i32,
}

/// Parse a `hello` frame. Errors if the JSON is bad or its `type` is not `"hello"`.
pub fn parse_hello(json: &str) -> Result<Hello> {
    let v: serde_json::Value = serde_json::from_str(json).context("parse hello")?;
    if v.get("type").and_then(serde_json::Value::as_str) != Some("hello") {
        bail!("not a hello frame");
    }
    Ok(Hello {
        protocol: v
            .get("protocol")
            .and_then(serde_json::Value::as_u64)
            .and_then(|n| u32::try_from(n).ok())
            .unwrap_or(0),
        hook: v
            .get("hook")
            .and_then(serde_json::Value::as_str)
            .unwrap_or_default()
            .to_owned(),
        android_api: v
            .get("androidApi")
            .and_then(serde_json::Value::as_i64)
            .and_then(|n| i32::try_from(n).ok())
            .unwrap_or(0),
        keystore_pid: v
            .get("keystorePid")
            .and_then(serde_json::Value::as_i64)
            .and_then(|n| i32::try_from(n).ok())
            .unwrap_or(0),
    })
}

/// The interceptor's ack to a `config` push.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Ack {
    pub epoch: u64,
    pub ok: bool,
    pub profiles_applied: i32,
    pub profiles_failed: i32,
}

/// Parse an `ack` frame. Errors if the JSON is bad or its `type` is not `"ack"`.
pub fn parse_ack(json: &str) -> Result<Ack> {
    let v: serde_json::Value = serde_json::from_str(json).context("parse ack")?;
    if v.get("type").and_then(serde_json::Value::as_str) != Some("ack") {
        bail!("not an ack frame");
    }
    Ok(Ack {
        epoch: v
            .get("epoch")
            .and_then(serde_json::Value::as_u64)
            .unwrap_or(0),
        ok: v
            .get("ok")
            .and_then(serde_json::Value::as_bool)
            .unwrap_or(false),
        profiles_applied: v
            .get("profilesApplied")
            .and_then(serde_json::Value::as_i64)
            .and_then(|n| i32::try_from(n).ok())
            .unwrap_or(0),
        profiles_failed: v
            .get("profilesFailed")
            .and_then(serde_json::Value::as_i64)
            .and_then(|n| i32::try_from(n).ok())
            .unwrap_or(0),
    })
}

/// Read the Manager-resolved push config from `path` and stamp it with `epoch`, returning the
/// serialized JSON ready to frame. Errors if the file is unreadable or not a JSON object.
pub fn load_push_with_epoch(path: &Path, epoch: u64) -> Result<String> {
    let raw = std::fs::read_to_string(path).with_context(|| format!("read {}", path.display()))?;
    let mut v: serde_json::Value = serde_json::from_str(&raw).context("parse push.json")?;
    let obj = v
        .as_object_mut()
        .context("push.json is not a JSON object")?;
    obj.insert("epoch".to_owned(), serde_json::json!(epoch));
    serde_json::to_string(&v).context("serialize push")
}

#[cfg(target_os = "android")]
pub use client::ControlClient;

#[cfg(target_os = "android")]
mod client {
    use super::{Ack, Hello, encode_frame, parse_ack, parse_hello, read_frame};
    use anyhow::{Context, Result};
    use std::io::Write;
    use std::os::unix::net::UnixStream;
    use std::path::Path;

    /// A connected control session to the interceptor injected into keystore2.
    pub struct ControlClient {
        stream: UnixStream,
    }

    impl ControlClient {
        /// Connect to the control socket and read the interceptor's hello (its proof of life).
        pub fn connect(path: &Path) -> Result<(Self, Hello)> {
            let mut stream =
                UnixStream::connect(path).with_context(|| format!("connect {}", path.display()))?;
            let hello = parse_hello(&read_frame(&mut stream)?)?;
            Ok((Self { stream }, hello))
        }

        /// Push a resolved `config` message and return the interceptor's ack.
        pub fn push(&mut self, config_json: &str) -> Result<Ack> {
            self.stream
                .write_all(&encode_frame(config_json))
                .context("write config frame")?;
            parse_ack(&read_frame(&mut self.stream)?)
        }

        /// Send a `ping` and return the epoch echoed back in the `pong`.
        pub fn ping(&mut self, epoch: u64) -> Result<u64> {
            let msg = serde_json::json!({ "type": "ping", "epoch": epoch }).to_string();
            self.stream
                .write_all(&encode_frame(&msg))
                .context("write ping frame")?;
            let resp = read_frame(&mut self.stream)?;
            let v: serde_json::Value = serde_json::from_str(&resp).context("parse pong")?;
            Ok(v.get("epoch")
                .and_then(serde_json::Value::as_u64)
                .unwrap_or(0))
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{
        Ack, Hello, encode_frame, load_push_with_epoch, parse_ack, parse_hello, read_frame,
    };
    use std::io::Cursor;

    #[test]
    fn frame_roundtrip() {
        let msg = r#"{"type":"ping","epoch":3}"#;
        let framed = encode_frame(msg);
        assert_eq!(&framed[..4], &(msg.len() as u32).to_be_bytes());
        let mut cur = Cursor::new(framed);
        assert_eq!(read_frame(&mut cur).unwrap(), msg);
    }

    #[test]
    fn read_frame_rejects_zero_length() {
        let mut cur = Cursor::new(vec![0u8, 0, 0, 0]);
        assert!(read_frame(&mut cur).is_err());
    }

    #[test]
    fn read_frame_rejects_oversized_length() {
        // 0xFFFFFFFF bytes >> 8 MiB: rejected before any body read.
        let mut cur = Cursor::new(vec![0xFFu8, 0xFF, 0xFF, 0xFF]);
        assert!(read_frame(&mut cur).is_err());
    }

    #[test]
    fn parse_hello_ok() {
        let s = r#"{"type":"hello","role":"lib","protocol":1,"hook":"keymint","androidApi":34,"keystorePid":1234}"#;
        assert_eq!(
            parse_hello(s).unwrap(),
            Hello {
                protocol: 1,
                hook: "keymint".to_owned(),
                android_api: 34,
                keystore_pid: 1234
            }
        );
    }

    #[test]
    fn parse_hello_rejects_other_type() {
        assert!(parse_hello(r#"{"type":"ack","epoch":1}"#).is_err());
    }

    #[test]
    fn parse_ack_ok() {
        let s = r#"{"type":"ack","epoch":7,"ok":true,"profilesApplied":1,"profilesFailed":0}"#;
        assert_eq!(
            parse_ack(s).unwrap(),
            Ack {
                epoch: 7,
                ok: true,
                profiles_applied: 1,
                profiles_failed: 0
            }
        );
    }

    #[test]
    fn epoch_injected_keeps_profiles() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("push.json");
        std::fs::write(
            &path,
            r#"{"type":"config","epoch":0,"profiles":[{"id":"default"}]}"#,
        )
        .unwrap();
        let out = load_push_with_epoch(&path, 7).unwrap();
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["epoch"].as_u64(), Some(7));
        assert_eq!(v["profiles"][0]["id"].as_str(), Some("default"));
    }

    #[test]
    fn load_push_rejects_bad_json() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("push.json");
        std::fs::write(&path, "{ not json").unwrap();
        assert!(load_push_with_epoch(&path, 1).is_err());
    }
}
