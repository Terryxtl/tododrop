//! 自签证书与指纹。

use std::sync::Arc;

use anyhow::{Context, Result};
use base64::Engine as _;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};

/// 本次运行使用的 TLS 身份。
pub struct TlsIdentity {
    pub cert_der: Vec<u8>,
    pub key_der: Vec<u8>,
    /// 证书 DER 的 SHA-256。
    pub fingerprint: [u8; 32],
}

impl TlsIdentity {
    /// 生成新的自签 ECDSA P-256 证书。
    pub fn generate() -> Result<Self> {
        let certified = rcgen::generate_simple_self_signed(vec!["tododrop.local".to_string()])
            .context("无法生成自签证书")?;
        let cert_der = certified.cert.der().to_vec();
        let key_der = certified.signing_key.serialize_der();
        let fingerprint = sha256(&cert_der);
        Ok(Self { cert_der, key_der, fingerprint })
    }

    /// 指纹的 base64url 无填充编码（43 个字符），放进二维码的 `f` 参数。
    pub fn fingerprint_b64(&self) -> String {
        base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(self.fingerprint)
    }

    /// 便于人工核对的短指纹，例如 `3F:A2:91:0C`。
    pub fn fingerprint_short(&self) -> String {
        self.fingerprint[..4].iter().map(|b| format!("{b:02X}")).collect::<Vec<_>>().join(":")
    }

    /// 构建使用 ring 加密后端的 rustls 服务端配置。
    pub fn server_config(&self) -> Result<Arc<rustls::ServerConfig>> {
        let provider = Arc::new(rustls::crypto::ring::default_provider());
        let mut cfg = rustls::ServerConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .context("无法初始化 TLS")?
            .with_no_client_auth()
            .with_single_cert(
                vec![CertificateDer::from(self.cert_der.clone())],
                PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key_der.clone())),
            )
            .context("无法加载证书")?;
        cfg.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(Arc::new(cfg))
    }
}

/// 计算 SHA-256。
pub fn sha256(data: &[u8]) -> [u8; 32] {
    let d = ring::digest::digest(&ring::digest::SHA256, data);
    let mut out = [0u8; 32];
    out.copy_from_slice(d.as_ref());
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_fingerprint_matches_cert() {
        let id = TlsIdentity::generate().unwrap();
        assert_eq!(id.fingerprint, sha256(&id.cert_der));
        assert_eq!(id.fingerprint_b64().len(), 43);
        assert_eq!(id.fingerprint_short().len(), 11);
        id.server_config().unwrap();
    }

    #[test]
    fn each_run_gets_new_cert() {
        let a = TlsIdentity::generate().unwrap();
        let b = TlsIdentity::generate().unwrap();
        assert_ne!(a.fingerprint, b.fingerprint);
    }
}
