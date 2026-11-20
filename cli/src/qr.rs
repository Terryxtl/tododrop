//! 生成二维码内容并渲染成终端字符画。

use std::fmt::Write as _;
use std::net::Ipv4Addr;

use anyhow::{Context, Result};
use qrcode::{Color, EcLevel, QrCode};

/// 二维码承载的连接信息。
#[derive(Debug, Clone)]
pub struct Payload {
    pub hosts: Vec<Ipv4Addr>,
    pub port: u16,
    pub token: String,
    pub fingerprint_b64: String,
    pub name: String,
}

/// PC 名最长字符数。
pub const MAX_NAME_CHARS: usize = 32;

impl Payload {
    /// 拼成 `tododrop://v1?h=..&p=..&t=..&f=..&n=..`。
    pub fn to_url(&self) -> String {
        let hosts = self.hosts.iter().map(|h| h.to_string()).collect::<Vec<_>>().join(",");
        let mut url = format!(
            "tododrop://v1?h={hosts}&p={}&t={}&f={}",
            self.port, self.token, self.fingerprint_b64
        );
        let name: String = self.name.chars().take(MAX_NAME_CHARS).collect();
        if !name.is_empty() {
            url.push_str("&n=");
            url.push_str(&urlencoding::encode(&name));
        }
        url
    }
}

/// 渲染风格。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Style {
    /// 半块字符 + ANSI 颜色，固定白底黑码，深浅色终端都能扫。
    Ansi,
    /// 半块字符，不用颜色：用终端前景色画“亮”的模块，适合深色背景的终端。
    Plain,
}

/// 二维码四周的静区宽度（模块数）。
const QUIET: usize = 4;

/// 生成二维码模块矩阵（含静区），`true` 表示深色模块。
fn matrix(data: &str) -> Result<Vec<Vec<bool>>> {
    let code = QrCode::with_error_correction_level(data.as_bytes(), EcLevel::M).context("二维码生成失败")?;
    let w = code.width();
    let colors = code.to_colors();
    let n = w + QUIET * 2;
    let mut m = vec![vec![false; n]; n];
    for y in 0..w {
        for x in 0..w {
            m[y + QUIET][x + QUIET] = colors[y * w + x] == Color::Dark;
        }
    }
    Ok(m)
}

/// 把 `data` 渲染成终端字符画。每个字符单元表示上下两个模块。
///
/// `invert` 为真时黑白互换（Ansi 风格下变成黑底白码；Plain 风格下改为画深色模块，适合浅色背景终端）。
pub fn render(data: &str, style: Style, invert: bool) -> Result<String> {
    let m = matrix(data)?;
    let n = m.len();
    let get = |x: usize, y: usize| -> bool { y < n && m[y][x] };
    let mut out = String::new();

    match style {
        Style::Ansi => {
            // 深色模块 = 黑，浅色模块 = 亮白
            let (dark, light) = if invert { (97u8, 30u8) } else { (30u8, 97u8) };
            for y in (0..n).step_by(2) {
                let mut last: Option<(u8, u8)> = None;
                for x in 0..n {
                    let fg = if get(x, y) { dark } else { light };
                    let bg = if get(x, y + 1) { dark } else { light } + 10;
                    if last != Some((fg, bg)) {
                        let _ = write!(out, "\x1b[{fg};{bg}m");
                        last = Some((fg, bg));
                    }
                    out.push('▀');
                }
                out.push_str("\x1b[0m\n");
            }
        }
        Style::Plain => {
            // 默认画“浅色”模块（深色背景终端上看起来就是正常的白底黑码）
            let ink = |x: usize, y: usize| -> bool { if y >= n { false } else if invert { get(x, y) } else { !get(x, y) } };
            for y in (0..n).step_by(2) {
                for x in 0..n {
                    out.push(match (ink(x, y), ink(x, y + 1)) {
                        (true, true) => '█',
                        (true, false) => '▀',
                        (false, true) => '▄',
                        (false, false) => ' ',
                    });
                }
                out.push('\n');
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn payload() -> Payload {
        Payload {
            hosts: vec!["192.168.110.123".parse().unwrap(), "10.147.17.200".parse().unwrap()],
            port: 53320,
            token: "AAAAAAAAAAAAAAAAAAAAAA".into(),
            fingerprint_b64: "B".repeat(43),
            name: "DESKTOP-ABC 书房".into(),
        }
    }

    #[test]
    fn url_format() {
        let url = payload().to_url();
        assert_eq!(
            url,
            format!(
                "tododrop://v1?h=192.168.110.123,10.147.17.200&p=53320&t=AAAAAAAAAAAAAAAAAAAAAA&f={}&n=DESKTOP-ABC%20%E4%B9%A6%E6%88%BF",
                "B".repeat(43)
            )
        );
    }

    #[test]
    fn name_is_truncated_and_optional() {
        let mut p = payload();
        p.name = "名".repeat(50);
        let url = p.to_url();
        let n = url.split("&n=").nth(1).unwrap();
        assert_eq!(urlencoding::decode(n).unwrap().chars().count(), MAX_NAME_CHARS);

        p.name.clear();
        assert!(!p.to_url().contains("&n="));
    }

    #[test]
    fn render_dimensions() {
        let url = payload().to_url();
        let m = matrix(&url).unwrap();
        let n = m.len();
        let plain = render(&url, Style::Plain, false).unwrap();
        let lines: Vec<&str> = plain.lines().collect();
        assert_eq!(lines.len(), n.div_ceil(2));
        assert!(lines.iter().all(|l| l.chars().count() == n));
        // 第一行全在静区里：默认模式下全是“浅色”，画成满块
        assert!(lines[0].chars().all(|c| c == '█'));

        let ansi = render(&url, Style::Ansi, false).unwrap();
        assert_eq!(ansi.lines().count(), n.div_ceil(2));
        assert!(ansi.contains('▀'));
    }

    #[test]
    fn typical_payload_fits_small_qr() {
        let m = matrix(&payload().to_url()).unwrap();
        // 版本 ≤ 10（57 模块）加静区
        assert!(m.len() <= 57 + QUIET * 2, "二维码过大：{}", m.len());
    }
}
