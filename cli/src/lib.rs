//! 待办投递（TodoDrop）PC 接收端。
//!
//! 协议见仓库根目录的 `docs/PROTOCOL.md`。

pub mod config;
pub mod markdown;
pub mod net;
pub mod qr;
pub mod server;
pub mod storage;
pub mod tls;

/// 接口协议版本，对应 `/api/v1/info` 返回的和请求体里的 `protocol` 字段。
/// 二维码格式没有变，仍然是 `tododrop://v1`。
pub const PROTOCOL_VERSION: u32 = 2;

/// 默认监听端口。
pub const DEFAULT_PORT: u16 = 53320;

/// 请求体大小上限（2 MiB）。
pub const MAX_BODY_BYTES: usize = 2 * 1024 * 1024;

/// 生成 16 字节随机 token，base64url 无填充编码（22 个字符）。
pub fn generate_token() -> anyhow::Result<String> {
    use base64::Engine as _;
    use ring::rand::SecureRandom as _;
    let mut buf = [0u8; 16];
    ring::rand::SystemRandom::new()
        .fill(&mut buf)
        .map_err(|_| anyhow::anyhow!("无法生成随机 token"))?;
    Ok(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(buf))
}

/// 常量时间比较两个字节串。
pub fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn token_is_22_chars_url_safe() {
        let t = generate_token().unwrap();
        assert_eq!(t.len(), 22);
        assert!(t.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'));
        assert_ne!(t, generate_token().unwrap());
    }

    #[test]
    fn constant_time_eq_works() {
        assert!(constant_time_eq(b"abc", b"abc"));
        assert!(!constant_time_eq(b"abc", b"abd"));
        assert!(!constant_time_eq(b"abc", b"abcd"));
        assert!(constant_time_eq(b"", b""));
    }
}
