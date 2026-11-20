//! 端到端测试：真实 HTTPS 监听 + 按指纹校验证书的 TLS 客户端（模拟 Android 端的做法）。

use std::net::{Ipv4Addr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{DigitallySignedStruct, SignatureScheme};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio_rustls::TlsConnector;

use tododrop::server::{self, AppState, Saved};
use tododrop::tls::{TlsIdentity, sha256};

/// 只信任 SHA-256 指纹等于 `expected` 的证书，与 Android 端 PinnedHttpClient 的逻辑一致。
#[derive(Debug)]
struct FingerprintVerifier {
    expected: [u8; 32],
    provider: Arc<rustls::crypto::CryptoProvider>,
}

impl ServerCertVerifier for FingerprintVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if tododrop::constant_time_eq(&sha256(end_entity.as_ref()), &self.expected) {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("证书指纹不匹配".into()))
        }
    }
    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }
    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}

fn connector(expected: [u8; 32]) -> TlsConnector {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let cfg = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_safe_default_protocol_versions()
        .unwrap()
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(FingerprintVerifier { expected, provider }))
        .with_no_client_auth();
    TlsConnector::from(Arc::new(cfg))
}

/// 发一个 HTTP/1.1 请求，返回 (状态码, 响应体)。
async fn request(
    addr: SocketAddr,
    fp: [u8; 32],
    method: &str,
    path: &str,
    token: &str,
    body: &str,
) -> std::io::Result<(u16, String)> {
    let tcp = tokio::net::TcpStream::connect(addr).await?;
    let mut tls = connector(fp).connect(ServerName::try_from("tododrop.local").unwrap(), tcp).await?;
    let req = format!(
        "{method} {path} HTTP/1.1\r\nHost: {addr}\r\nAuthorization: Bearer {token}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
        body.len()
    );
    tls.write_all(req.as_bytes()).await?;
    let mut resp = Vec::new();
    let _ = tls.read_to_end(&mut resp).await; // 服务端关闭时可能不发 close_notify
    let resp = String::from_utf8_lossy(&resp).into_owned();
    let status: u16 = resp.split_whitespace().nth(1).and_then(|s| s.parse().ok()).unwrap_or(0);
    let body = resp.split_once("\r\n\r\n").map(|(_, b)| b.to_string()).unwrap_or_default();
    Ok((status, body))
}

#[tokio::test]
async fn https_roundtrip_with_fingerprint_pinning() {
    let tmp = tempfile::tempdir().unwrap();
    let identity = TlsIdentity::generate().unwrap();
    let fp = identity.fingerprint;
    let token = tododrop::generate_token().unwrap();

    let saved = Arc::new(Mutex::new(Vec::<Saved>::new()));
    let saved2 = saved.clone();
    let state = Arc::new(AppState {
        token: token.clone(),
        name: "E2E-PC".into(),
        out_dir: tmp.path().to_path_buf(),
        on_saved: Arc::new(move |s: &Saved| saved2.lock().unwrap().push(s.clone())),
    });

    let listener = server::bind_listener(0).unwrap();
    let addr = SocketAddr::from((Ipv4Addr::LOCALHOST, listener.local_addr().unwrap().port()));
    let handle = axum_server::Handle::new();
    let task = tokio::spawn(server::serve(listener, identity.server_config().unwrap(), state, handle.clone()));

    // info
    let (status, body) = request(addr, fp, "GET", "/api/v1/info", &token, "").await.unwrap();
    assert_eq!(status, 200, "{body}");
    assert!(body.contains("\"name\":\"E2E-PC\""), "{body}");

    // 错误 token
    let (status, _) = request(addr, fp, "GET", "/api/v1/info", "bad", "").await.unwrap();
    assert_eq!(status, 401);

    // 上传两次（同一秒内会触发 _2 后缀，跨秒则是两个不同时间戳，都应成功）
    let body = serde_json::json!({
        "protocol": 2,
        "device": "Pixel",
        "projects": [
            {"name": "工作", "todos": ["写周报
第二行正文"], "notes": ["周五前交"], "children": [{"name": "后端", "todos": ["接口文档"]}]},
            {"name": "生活", "todos": ["买牛奶"]}
        ]
    })
    .to_string();
    let (s1, b1) = request(addr, fp, "POST", "/api/v2/todos", &token, &body).await.unwrap();
    let (s2, b2) = request(addr, fp, "POST", "/api/v2/todos", &token, &body).await.unwrap();
    assert_eq!((s1, s2), (201, 201), "{b1} / {b2}");
    assert!(b1.contains("TODOS_工作_") && b1.contains("TODOS_生活_"), "{b1}");

    let mut files: Vec<_> = std::fs::read_dir(tmp.path()).unwrap().map(|e| e.unwrap().path()).collect();
    files.sort();
    assert_eq!(files.len(), 4);
    for f in &files {
        let name = f.file_name().unwrap().to_string_lossy().into_owned();
        let content = std::fs::read_to_string(f).unwrap();
        if name.starts_with("TODOS_工作_") {
            assert_eq!(content, "- [ ] 写周报
  第二行正文

## 后端

- [ ] 接口文档
");
        } else {
            assert!(name.starts_with("TODOS_生活_") && name.ends_with(".md"), "{name}");
            assert_eq!(content, "- [ ] 买牛奶
");
        }
    }
    {
        let saved = saved.lock().unwrap();
        assert_eq!(saved.len(), 2);
        assert_eq!(saved[0].notes.len(), 1);
    }

    // 指纹不对时握手必须失败
    let mut wrong = fp;
    wrong[0] ^= 0xFF;
    assert!(request(addr, wrong, "GET", "/api/v1/info", &token, "").await.is_err());

    handle.graceful_shutdown(Some(Duration::from_secs(1)));
    tokio::time::timeout(Duration::from_secs(5), task).await.unwrap().unwrap().unwrap();
}
