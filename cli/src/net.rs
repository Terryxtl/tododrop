//! 枚举本机可供手机连接的 IPv4 地址。

use std::net::{IpAddr, Ipv4Addr};

/// 一个候选地址。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Candidate {
    /// 网卡名称（Windows 上是“WLAN”“以太网”之类的友好名称）。
    pub iface: String,
    pub ip: Ipv4Addr,
    /// 优先级，越小越优先。
    pub rank: u8,
}

/// 名字里带这些关键词的网卡是虚拟机 / 容器网卡，手机连不上，直接排除。
const EXCLUDED: &[&str] = &[
    "vethernet", "wsl", "hyper-v", "vmware", "virtualbox", "vbox", "docker", "loopback", "npcap",
    "bluetooth", "蓝牙",
];

/// 名字里带这些关键词的网卡是 VPN / 组网工具，保留但排在后面。
const VPN: &[&str] = &[
    "zerotier", "tailscale", "wireguard", "openvpn", "tap", "tun", "utun", "wg", "zt", "radmin",
    "hamachi", "easytier", "vpn",
];

/// 根据网卡名和地址给出优先级；返回 `None` 表示排除。
///
/// - 0：普通网卡上的私有网段地址（家里 / 公司的 Wi-Fi、有线网）
/// - 1：VPN / 组网工具的地址，或 100.64.0.0/10（Tailscale 等常用）
/// - 2：其他地址（例如公网地址）
pub fn rank(iface: &str, ip: Ipv4Addr) -> Option<u8> {
    if ip.is_loopback() || ip.is_link_local() || ip.is_unspecified() || ip.is_broadcast() || ip.is_multicast() {
        return None;
    }
    let o = ip.octets();
    // 198.18.0.0/15 是基准测试保留网段，Clash / sing-box 等代理的 TUN 模式会用它，手机连不上
    if o[0] == 198 && (o[1] == 18 || o[1] == 19) {
        return None;
    }
    let name = iface.to_lowercase();
    if EXCLUDED.iter().any(|k| name.contains(k)) {
        return None;
    }
    let is_vpn_name = VPN.iter().any(|k| {
        // 短关键词（tap/tun/wg/zt）只匹配单词开头，避免误伤“Ethernet”之类的名字
        if k.len() <= 3 {
            name.split(|c: char| !c.is_ascii_alphanumeric()).any(|w| w.starts_with(k))
        } else {
            name.contains(k)
        }
    });
    let is_cgnat = o[0] == 100 && (64..128).contains(&o[1]);
    if is_vpn_name || is_cgnat {
        Some(1)
    } else if ip.is_private() {
        Some(0)
    } else {
        Some(2)
    }
}

/// 对网卡列表打分、过滤、排序并去重，最多返回 `max` 个。
pub fn select(ifaces: &[(String, IpAddr)], max: usize) -> Vec<Candidate> {
    let mut out: Vec<Candidate> = Vec::new();
    for (name, ip) in ifaces {
        let IpAddr::V4(v4) = ip else { continue };
        let Some(r) = rank(name, *v4) else { continue };
        if out.iter().any(|c| c.ip == *v4) {
            continue;
        }
        out.push(Candidate { iface: name.clone(), ip: *v4, rank: r });
    }
    // sort_by_key 是稳定排序，同优先级保持系统给出的顺序
    out.sort_by_key(|c| c.rank);
    out.truncate(max);
    out
}

/// 读取本机网卡并返回候选地址。
pub fn candidates(max: usize) -> anyhow::Result<Vec<Candidate>> {
    let ifaces = local_ip_address::list_afinet_netifas()
        .map_err(|e| anyhow::anyhow!("无法读取网卡列表：{e}"))?;
    Ok(select(&ifaces, max))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v4(s: &str) -> IpAddr {
        IpAddr::V4(s.parse().unwrap())
    }

    #[test]
    fn rank_rules() {
        assert_eq!(rank("WLAN", "192.168.1.5".parse().unwrap()), Some(0));
        assert_eq!(rank("以太网", "10.0.0.8".parse().unwrap()), Some(0));
        assert_eq!(rank("Ethernet", "172.16.3.4".parse().unwrap()), Some(0));
        assert_eq!(rank("ZeroTier One [8056c2e21c000001]", "10.147.17.3".parse().unwrap()), Some(1));
        assert_eq!(rank("Tailscale", "100.101.102.103".parse().unwrap()), Some(1));
        assert_eq!(rank("eth0", "100.80.1.1".parse().unwrap()), Some(1));
        assert_eq!(rank("WLAN", "8.8.8.8".parse().unwrap()), Some(2));
        assert_eq!(rank("vEthernet (WSL)", "172.20.0.1".parse().unwrap()), None);
        assert_eq!(rank("VMware Network Adapter VMnet8", "192.168.80.1".parse().unwrap()), None);
        assert_eq!(rank("WLAN", "127.0.0.1".parse().unwrap()), None);
        assert_eq!(rank("WLAN", "169.254.3.3".parse().unwrap()), None);
        assert_eq!(rank("xdmcloud", "198.18.0.1".parse().unwrap()), None);
        assert_eq!(rank("Meta", "198.19.255.1".parse().unwrap()), None);
    }

    #[test]
    fn select_sorts_dedups_and_truncates() {
        let ifaces = vec![
            ("ZeroTier One".to_string(), v4("10.147.17.3")),
            ("vEthernet (WSL)".to_string(), v4("172.20.0.1")),
            ("WLAN".to_string(), v4("192.168.1.5")),
            ("以太网".to_string(), v4("192.168.1.5")),
            ("Loopback Pseudo-Interface 1".to_string(), v4("127.0.0.1")),
            ("WLAN".to_string(), "fe80::1".parse().unwrap()),
            ("以太网 2".to_string(), v4("10.0.0.2")),
        ];
        let got = select(&ifaces, 4);
        let ips: Vec<String> = got.iter().map(|c| c.ip.to_string()).collect();
        assert_eq!(ips, vec!["192.168.1.5", "10.0.0.2", "10.147.17.3"]);

        let got = select(&ifaces, 1);
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].ip.to_string(), "192.168.1.5");
    }
}
